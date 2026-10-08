package org.fuin.sokar.intellij.machine;

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
import java.util.concurrent.TimeUnit;

/**
 * A socket of the plugin's own that reaches the daemon in a WSL distribution: each connection to it starts
 * {@code wsl.exe -d <distribution> --exec sokar daemon connect}, a process with its own byte streams, and joins the
 * two.
 * <p>
 * No shell is in between, on Windows or in the distribution, so nothing re-encodes the bytes, and no port is opened.
 * {@code sokar daemon connect} finds the account's socket under {@code /run/user/<uid>} without a login's
 * environment. A distribution that is not running
 * is never started: it is refused with what {@code wsl.exe -l -v} says of it.
 */
public final class WslBridge implements AutoCloseable {

    private static final Duration READY = Duration.ofSeconds(10);

    private static final byte[] GET_INFO = "{\"method\":\"org.varlink.service.GetInfo\"}\0"
            .getBytes(StandardCharsets.UTF_8);

    private final Machine machine;

    private final Path socket;

    private final List<String> relay;

    private final ServerSocketChannel server;

    private final Thread acceptor;

    private volatile boolean closed;

    // On Windows a child does not end with its parent: each wsl.exe is ended here, with the bridge.
    private final java.util.Set<Process> relays = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private WslBridge(Machine machine, Path socket, List<String> relay, ServerSocketChannel server) {
        this.machine = machine;
        this.socket = socket;
        this.relay = List.copyOf(relay);
        this.server = server;
        this.acceptor = Thread.ofVirtual().name("sokar-wsl-" + machine.name()).start(this::accept);
    }

    /**
     * Opens the bridge once the distribution is seen running and its daemon answers.
     *
     * @param machine A machine in a WSL distribution.
     * @param runtimeDirectory Where the plugin's end goes.
     * @param wsl The {@code wsl.exe} command, {@code wsl.exe} unless a test stands in for it.
     * @return The bridge, open.
     * @throws MachinesException If the distribution is not there, not WSL2 or not running, or its daemon does not
     *     answer - with {@code wsl.exe}'s own words.
     */
    public static WslBridge open(Machine machine, Path runtimeDirectory, List<String> wsl) {
        final String distribution = Objects.requireNonNull(machine.distribution(), "a machine outside WSL");
        // wsl.exe would read a name that starts with '-' as one of its own options.
        if (distribution.startsWith("-")) {
            throw new MachinesException("Machine '" + machine.name() + "' names the distribution '" + distribution
                    + "', which wsl.exe would read as an option");
        }
        running(machine, distribution, wsl);
        final List<String> relay = new ArrayList<>(wsl);
        relay.addAll(List.of("-d", distribution, "--exec", "sokar", "daemon", "connect"));
        answers(machine, relay);
        final Path socket = Tunnel.socketFor(runtimeDirectory, machine);
        final ServerSocketChannel server;
        try {
            Files.deleteIfExists(socket);
            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(socket));
            ownerOnly(socket);
        } catch (IOException ex) {
            throw new MachinesException("Cannot open the plugin's end for '" + machine.name() + "' at " + socket + ": "
                    + ex.getMessage(), ex);
        }
        return new WslBridge(machine, socket, relay, server);
    }

    /**
     * Reads what {@code wsl.exe -l -v} prints: one row per distribution, its name, state and WSL version.
     *
     * @param printed What it printed, in UTF-16LE as {@code wsl.exe} prints by default, or in UTF-8.
     * @return Each row as {@code [name, state, version]}, the default's {@code *} taken off.
     */
    static List<String[]> distributions(byte[] printed) {
        // UTF-8 text never holds a NUL byte; UTF-16LE text of ASCII names holds one in every second.
        boolean utf16 = false;
        for (final byte b : printed) {
            utf16 |= b == 0;
        }
        final String text = new String(printed, utf16 ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_8)
                .replace("﻿", "");
        final List<String[]> rows = new ArrayList<>();
        for (final String line : text.split("\\R")) {
            final String[] words = line.replace("*", " ").strip().split("\\s+");
            if (words.length == 3 && !words[0].equals("NAME")) {
                rows.add(words);
            }
        }
        return rows;
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
     * Tells whether the bridge still takes connections.
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
            for (final Process relay : relays) {
                end(relay);
            }
            acceptor.join(READY);
            Files.deleteIfExists(socket);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (IOException ex) {
            throw new MachinesException("Cannot remove " + socket + ": " + ex.getMessage(), ex);
        }
    }

    private static void running(Machine machine, String distribution, List<String> wsl) {
        final List<String> list = new ArrayList<>(wsl);
        list.addAll(List.of("-l", "-v"));
        final Ran ran = run(list, null);
        if (ran.exit() != 0) {
            throw new MachinesException("wsl.exe -l -v ended with exit code " + ran.exit() + " for '" + machine.name()
                    + "': " + ran.said());
        }
        for (final String[] row : distributions(ran.out())) {
            if (!row[0].equals(distribution)) {
                continue;
            }
            if (!row[2].equals("2")) {
                throw new MachinesException("'" + machine.name() + "' is the WSL " + row[2] + " distribution '"
                        + distribution + "'; Sokar needs WSL 2 (wsl --set-version " + distribution + " 2)");
            }
            if (!row[1].equalsIgnoreCase("Running")) {
                throw new MachinesException("'" + machine.name() + "': the WSL distribution '" + distribution + "' is "
                        + row[1].toLowerCase(java.util.Locale.ROOT) + ", and the plugin never starts one; start it,"
                        + " then it is read again");
            }
            return;
        }
        throw new MachinesException("'" + machine.name() + "': this computer has no WSL distribution '" + distribution
                + "'");
    }

    private static void answers(Machine machine, List<String> relay) {
        final Ran ran = run(relay, GET_INFO);
        final String answer = new String(ran.out(), StandardCharsets.UTF_8);
        if (!answer.contains("\"interfaces\"")) {
            throw new MachinesException("Sokar's daemon in '" + machine.name() + "' does not answer through "
                    + String.join(" ", relay.subList(relay.size() - 6, relay.size())) + " (exit code " + ran.exit()
                    + "): " + ran.said());
        }
    }

    private record Ran(int exit, byte[] out, String said) {
    }

    private static Ran run(List<String> command, byte @org.jspecify.annotations.Nullable [] input) {
        final Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException ex) {
            throw new MachinesException("Cannot start " + command.getFirst() + ": " + ex.getMessage(), ex);
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final Thread reader = Thread.ofVirtual().start(() -> copy(process.getInputStream(), out, input != null));
        final Thread errors = Thread.ofVirtual().start(() -> copy(process.getErrorStream(), err, false));
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
        return new Ran(process.isAlive() ? -1 : process.exitValue(), out.toByteArray(),
                said.isEmpty() ? "it said nothing" : said);
    }

    // Reads all, or with untilAnswer up to the first NUL: one Varlink reply is all a probe waits for.
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
            Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-------"));
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
            Thread.ofVirtual().name("sokar-wsl-" + machine.name() + "-relay").start(() -> join(client));
        }
    }

    private void join(SocketChannel client) {
        final Process process;
        try {
            process = new ProcessBuilder(relay).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException ex) {
            close(client);
            return;
        }
        relays.add(process);
        if (closed) {
            end(process);
        }
        final Thread in = Thread.ofVirtual().start(() -> pump(Channels.newInputStream(client),
                process.getOutputStream()));
        pump(process.getInputStream(), Channels.newOutputStream(client));
        end(process);
        relays.remove(process);
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
        try (from; to) {
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
