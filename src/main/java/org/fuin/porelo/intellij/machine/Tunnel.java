package org.fuin.porelo.intellij.machine;

import java.io.IOException;
import java.io.InputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * An ssh forward of a machine's daemon socket to a socket of the plugin's own.
 *
 * <p>Its own and never the interface's, so neither ends or refuses the other's. ssh runs without a
 * prompt, with {@code -F} naming the machine's ssh configuration from its host card and the key in
 * the person's agent: a host key other than the card's or a key it cannot use fails, and ssh's
 * words say why.
 */
public final class Tunnel implements AutoCloseable {

    private static final Duration READY = Duration.ofSeconds(10);

    private static final int SAID_AT_MOST = 8192;

    private static final Set<PosixFilePermission> OWNER_ONLY =
            PosixFilePermissions.fromString("rw-------");

    private final Machine machine;

    private final Path socket;

    private final Process ssh;

    private final StringBuilder said = new StringBuilder();

    private final Thread listener;

    private Tunnel(Machine machine, Path socket, Process ssh) {
        this.machine = machine;
        this.socket = socket;
        this.ssh = ssh;
        // Read as it comes: a pipe nobody empties stops ssh once it is full.
        this.listener =
                Thread.ofVirtual().name("porelo-tunnel-" + machine.name()).start(this::listen);
    }

    /**
     * Returns where the plugin's end of a machine's tunnel is.
     *
     * @param runtimeDirectory The person's runtime directory.
     * @param machine The machine.
     * @return {@code <runtime>/porelo-intellij-<name>.sock}, the name with every character but
     *     letters, digits, {@code _} and {@code -} made {@code -}, as the interface names its own.
     */
    public static Path socketFor(Path runtimeDirectory, Machine machine) {
        return runtimeDirectory.resolve(
                "porelo-intellij-" + machine.name().replaceAll("[^A-Za-z0-9_-]", "-") + ".sock");
    }

    /**
     * Raises the forward and waits until its socket answers.
     *
     * @param machine A machine that needs a tunnel.
     * @param runtimeDirectory The person's runtime directory, where the plugin's end goes.
     * @param ssh The ssh command, {@code ssh} unless a test stands in for it.
     * @return The tunnel, up.
     * @throws MachinesException If something already answers at the plugin's end, or ssh ends or
     *     does not forward in time - with ssh's own words.
     */
    public static Tunnel raise(Machine machine, Path runtimeDirectory, List<String> ssh) {
        return raise(machine, runtimeDirectory, ssh, READY);
    }

    static Tunnel raise(Machine machine, Path runtimeDirectory, List<String> ssh, Duration ready) {
        final String host =
                Objects.requireNonNull(machine.host(), "a machine without a tunnel has no host");
        final String remote =
                Objects.requireNonNull(machine.remoteSocket(), "a machine without a tunnel");
        final Path sshConfig =
                Objects.requireNonNull(
                        machine.sshConfig(), "a machine without an ssh configuration");
        // ssh would read a host that starts with '-' as one of its own options.
        if (host.startsWith("-")) {
            throw new MachinesException(
                    "Machine '"
                            + machine.name()
                            + "' names the host '"
                            + host
                            + "', which ssh would read as an option");
        }
        final Path socket = socketFor(runtimeDirectory, machine);
        clear(machine, socket);
        final List<String> command = new ArrayList<>(ssh);
        // Right after the given ssh, so it is the last -F ssh reads and the one that counts.
        command.addAll(List.of("-F", sshConfig.toString()));
        command.addAll(
                List.of(
                        "-N",
                        "-o",
                        "BatchMode=yes",
                        "-o",
                        "ExitOnForwardFailure=yes",
                        "-o",
                        "ServerAliveInterval=15",
                        "-o",
                        "ControlMaster=no",
                        "-o",
                        "ControlPath=none",
                        "-L",
                        socket + ":" + remote,
                        "--",
                        host));
        final Process process;
        try {
            process =
                    new ProcessBuilder(command)
                            .redirectInput(
                                    ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .start();
        } catch (IOException ex) {
            throw new MachinesException(
                    "Cannot start ssh for '" + machine.name() + "': " + ex.getMessage(), ex);
        }
        final Tunnel tunnel = new Tunnel(machine, socket, process);
        try {
            tunnel.awaitReady(ready);
            return tunnel;
        } catch (RuntimeException ex) {
            tunnel.close();
            throw ex;
        }
    }

    /**
     * Returns the plugin's end of the forward, which a gate connects to.
     *
     * @return The socket.
     */
    public Path socket() {
        return socket;
    }

    /**
     * Tells whether ssh still forwards.
     *
     * @return {@code true} while its process runs.
     */
    public boolean up() {
        return ssh.isAlive();
    }

    @Override
    public void close() {
        ssh.destroy();
        try {
            if (!ssh.waitFor(READY.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                ssh.destroyForcibly();
            }
            Files.deleteIfExists(socket);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            ssh.destroyForcibly();
        } catch (IOException ex) {
            throw new MachinesException("Cannot remove " + socket + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Removes a stale socket at the plugin's end, and refuses one something still answers at.
     *
     * @param machine The machine whose end it is.
     * @param socket The plugin's end.
     * @throws MachinesException If something answers there, or the stale socket cannot be removed.
     */
    static void clear(Machine machine, Path socket) {
        if (!Files.exists(socket)) {
            return;
        }
        if (answers(socket)) {
            throw new MachinesException(
                    "Something already answers at "
                            + socket
                            + ", where the plugin's end for '"
                            + machine.name()
                            + "' goes; it is left alone. End it, then try again.");
        }
        try {
            Files.delete(socket);
        } catch (IOException ex) {
            throw new MachinesException(
                    "Cannot remove the stale " + socket + ": " + ex.getMessage(), ex);
        }
    }

    private void awaitReady(Duration ready) {
        final long deadline = System.nanoTime() + ready.toNanos();
        while (System.nanoTime() < deadline) {
            if (!ssh.isAlive()) {
                throw new MachinesException(
                        "ssh to '"
                                + machine.name()
                                + "' ended with exit code "
                                + ssh.exitValue()
                                + ": "
                                + said());
            }
            if (Files.exists(socket) && answers(socket)) {
                ownerOnly();
                return;
            }
            pause();
        }
        throw new MachinesException(
                "ssh to '"
                        + machine.name()
                        + "' did not forward "
                        + machine.remoteSocket()
                        + " within "
                        + ready.toMillis()
                        + " ms");
    }

    private void ownerOnly() {
        try {
            Files.setPosixFilePermissions(socket, OWNER_ONLY);
            if (!Files.getPosixFilePermissions(socket).equals(OWNER_ONLY)) {
                throw new MachinesException(socket + " is not readable by its owner alone");
            }
        } catch (IOException ex) {
            throw new MachinesException(
                    "Cannot make " + socket + " its owner's alone: " + ex.getMessage(), ex);
        }
    }

    private void listen() {
        try (InputStream err = ssh.getErrorStream()) {
            final byte[] buffer = new byte[1024];
            int read;
            while ((read = err.read(buffer)) >= 0) {
                synchronized (said) {
                    said.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    if (said.length() > SAID_AT_MOST) {
                        said.delete(0, said.length() - SAID_AT_MOST);
                    }
                }
            }
        } catch (IOException ex) {
            synchronized (said) {
                said.append("[its words cannot be read: ").append(ex.getMessage()).append(']');
            }
        }
    }

    private String said() {
        try {
            // ssh has ended, so its pipe ends too; what it said last is read once the listener sees
            // that.
            listener.join(READY);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        synchronized (said) {
            final String text = said.toString().strip();
            return text.isEmpty() ? "it said nothing" : text;
        }
    }

    private static boolean answers(Path socket) {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socket));
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    private static void pause() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MachinesException("Interrupted while waiting for the tunnel", ex);
        }
    }
}
