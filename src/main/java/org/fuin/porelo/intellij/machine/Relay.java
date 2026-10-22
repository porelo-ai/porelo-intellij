package org.fuin.porelo.intellij.machine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * A socket of the plugin's own whose every connection starts a command that speaks to a daemon on
 * its standard input and output, such as {@code porelo daemon connect}, and joins the two.
 *
 * <p>The command is a process with its own byte streams and no shell in between, so nothing
 * re-encodes the bytes, and no port is opened. It is how a daemon is reached that has no socket
 * this computer can open: in a WSL distribution through {@code wsl.exe}, or on another machine
 * through Windows' own {@code ssh.exe}.
 */
public final class Relay implements AutoCloseable {

    private static final Duration READY = Duration.ofSeconds(10);

    private static final java.util.regex.Pattern PLAIN_PATH =
            java.util.regex.Pattern.compile("/[A-Za-z0-9._/-]*");

    private static final byte[] GET_INFO =
            "{\"method\":\"org.varlink.service.GetInfo\"}\0".getBytes(StandardCharsets.UTF_8);

    private final Machine machine;

    private final Path socket;

    private final List<String> command;

    private final ServerSocketChannel server;

    private final Thread acceptor;

    private volatile boolean closed;

    // On Windows a child does not end with its parent: each command is ended here, with the relay.
    private final Set<Process> running = ConcurrentHashMap.newKeySet();

    private Relay(Machine machine, Path socket, List<String> command, ServerSocketChannel server) {
        this.machine = machine;
        this.socket = socket;
        this.command = List.copyOf(command);
        this.server = server;
        this.acceptor =
                Thread.ofVirtual().name("porelo-relay-" + machine.name()).start(this::accept);
    }

    /**
     * Opens the relay to a machine reached over ssh once its daemon answers through it.
     *
     * <p>Each connection runs {@code ssh -T -o BatchMode=yes <host> porelo daemon connect --socket
     * <remote socket>}: ssh never asks at a terminal, and the key comes from the person's own
     * configuration and agent.
     *
     * @param machine A machine that names a host and the daemon's socket there.
     * @param runtimeDirectory Where the plugin's end goes.
     * @param ssh The ssh command, Windows' {@code ssh.exe} unless a test stands in for it.
     * @return The relay, open.
     * @throws MachinesException If ssh cannot reach the machine or its daemon does not answer -
     *     with ssh's own words.
     */
    public static Relay overSsh(Machine machine, Path runtimeDirectory, List<String> ssh) {
        final String host = Objects.requireNonNull(machine.host(), "a machine without a host");
        final String remote =
                Objects.requireNonNull(machine.remoteSocket(), "a machine without a remote socket");
        // ssh would read a host that starts with '-' as one of its own options.
        if (host.startsWith("-")) {
            throw new MachinesException(
                    "Machine '"
                            + machine.name()
                            + "' names the host '"
                            + host
                            + "', which ssh would read as an option");
        }
        // The far end's login shell reads the line, and not every shell keeps single quotes as
        // POSIX
        // does: only plain path characters are taken.
        if (!PLAIN_PATH.matcher(remote).matches()) {
            throw new MachinesException(
                    "Machine '"
                            + machine.name()
                            + "' names the remote socket '"
                            + remote
                            + "', which the remote shell could read as more than a path;"
                            + " only letters, digits and . _ / - are taken");
        }
        final List<String> command = new ArrayList<>(ssh);
        command.addAll(
                List.of(
                        "-T",
                        "-o",
                        "BatchMode=yes",
                        "-o",
                        "ServerAliveInterval=15",
                        "-o",
                        "ControlMaster=no",
                        "-o",
                        "ControlPath=none",
                        "--",
                        host,
                        // The remote shell reads this line, so the socket is one word to it
                        // whatever it holds.
                        "porelo daemon connect --socket " + quoted(remote)));
        return open(machine, runtimeDirectory, command, "ssh to " + host);
    }

    /**
     * Opens a relay once its daemon answers through the command.
     *
     * @param machine The machine it reaches.
     * @param runtimeDirectory Where the plugin's end goes.
     * @param command What each connection runs.
     * @param through How the command reaches the daemon, as the refusal names it.
     * @return The relay, open.
     * @throws MachinesException If the daemon does not answer through the command, with its own
     *     words, or the plugin's end cannot be opened.
     */
    static Relay open(
            Machine machine, Path runtimeDirectory, List<String> command, String through) {
        answers(machine, command, through);
        final Path socket = Tunnel.socketFor(runtimeDirectory, machine);
        // A relay another IDE holds there is left alone; only a stale socket is removed.
        Tunnel.clear(machine, socket);
        final ServerSocketChannel server;
        try {
            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(socket));
            ownerOnly(socket);
        } catch (IOException ex) {
            throw new MachinesException(
                    "Cannot open the plugin's end for '"
                            + machine.name()
                            + "' at "
                            + socket
                            + ": "
                            + ex.getMessage(),
                    ex);
        }
        return new Relay(machine, socket, command, server);
    }

    /**
     * Returns the plugin's end, which a gate connects to.
     *
     * @return The socket.
     */
    public Path socket() {
        return socket;
    }

    /**
     * Tells whether the relay still takes connections.
     *
     * @return {@code true} until it is closed.
     */
    public boolean up() {
        return !closed && server.isOpen();
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ex) {
            // Closed either way; the commands below are ended all the same.
        }
        // Every command is ended even if what follows fails: none may outlive the relay.
        for (final Process process : running) {
            end(process);
        }
        try {
            acceptor.join(READY);
            Files.deleteIfExists(socket);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (IOException ex) {
            throw new MachinesException("Cannot remove " + socket + ": " + ex.getMessage(), ex);
        }
    }

    /** What a command printed and said when it ran to its end. */
    record Ran(int exit, byte[] out, String said) {}

    /**
     * Runs a command to its end, or up to its first reply when it is given input.
     *
     * @param command The command.
     * @param input What it reads, or {@code null} for nothing.
     * @return Its exit code ({@code -1} if it would not end), what it printed and what it said on
     *     standard error.
     * @throws MachinesException If it cannot be started.
     */
    static Ran run(List<String> command, byte @Nullable [] input) {
        final Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException ex) {
            throw new MachinesException(
                    "Cannot start " + command.getFirst() + ": " + ex.getMessage(), ex);
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final Thread reader =
                Thread.ofVirtual().start(() -> copy(process.getInputStream(), out, input != null));
        final Thread errors =
                Thread.ofVirtual().start(() -> copy(process.getErrorStream(), err, false));
        try (OutputStream stdin = process.getOutputStream()) {
            if (input != null) {
                stdin.write(input);
            }
        } catch (IOException ex) {
            // The process ended before it read; what it said tells why.
        }
        try {
            reader.join(READY);
            if (process.isAlive()) {
                process.destroy();
            }
            if (!process.waitFor(READY.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
            errors.join(READY);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        final String said = err.toString(StandardCharsets.UTF_8).strip();
        return new Ran(
                process.isAlive() ? -1 : process.exitValue(),
                out.toByteArray(),
                said.isEmpty() ? "it said nothing" : said);
    }

    // A POSIX shell's single quotes keep everything as it is but a single quote, which is closed,
    // escaped and opened again.
    private static String quoted(String word) {
        return "'" + word.replace("'", "'\\''") + "'";
    }

    private static void answers(Machine machine, List<String> command, String through) {
        final Ran ran = run(command, GET_INFO);
        final String answer = new String(ran.out(), StandardCharsets.UTF_8);
        if (!answer.contains("\"interfaces\"")) {
            throw new MachinesException(
                    "Porelo's daemon in '"
                            + machine.name()
                            + "' does not answer through "
                            + through
                            + " (exit code "
                            + ran.exit()
                            + "): "
                            + ran.said());
        }
    }

    // Reads all, or with untilAnswer up to the first NUL: one Varlink reply is all a probe waits
    // for.
    private static void copy(InputStream from, ByteArrayOutputStream to, boolean untilAnswer) {
        try {
            int read;
            while ((read = from.read()) >= 0) {
                to.write(read);
                if (untilAnswer && read == 0) {
                    return;
                }
            }
        } catch (IOException ex) {
            // Ended; what was read stands.
        }
    }

    private static void ownerOnly(Path socket) throws IOException {
        // Windows has no POSIX permissions; there the socket lies under the person's own profile.
        if (Files.getFileAttributeView(socket, PosixFileAttributeView.class) != null) {
            final var ownerOnly = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(socket, ownerOnly);
            if (!Files.getPosixFilePermissions(socket).equals(ownerOnly)) {
                throw new IOException(socket + " is not readable by its owner alone");
            }
        }
    }

    private void accept() {
        while (!closed) {
            final SocketChannel client;
            try {
                client = server.accept();
            } catch (IOException ex) {
                return;
            }
            Thread.ofVirtual()
                    .name("porelo-relay-" + machine.name() + "-connection")
                    .start(() -> join(client));
        }
    }

    private void join(SocketChannel client) {
        final Process process;
        try {
            process =
                    new ProcessBuilder(command)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start();
        } catch (IOException ex) {
            close(client);
            return;
        }
        running.add(process);
        if (closed) {
            end(process);
        }
        final Thread in =
                Thread.ofVirtual()
                        .start(
                                () ->
                                        pump(
                                                Channels.newInputStream(client),
                                                process.getOutputStream()));
        pump(process.getInputStream(), Channels.newOutputStream(client));
        end(process);
        running.remove(process);
        in.interrupt();
        close(client);
    }

    private static void end(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(READY.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static void pump(InputStream from, OutputStream to) {
        try (from;
                to) {
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = from.read(buffer)) >= 0) {
                to.write(buffer, 0, read);
                to.flush();
            }
        } catch (IOException ex) {
            // One side ended; the other ends with it.
        }
    }

    private static void close(SocketChannel client) {
        try {
            client.close();
        } catch (IOException ex) {
            // It is gone either way.
        }
    }
}
