package org.fuin.porelo.intellij.machine;

import java.nio.file.Path;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One machine whose daemon the person reaches.
 *
 * <p>Either its socket is opened as it is - this computer's daemon, or a forward somebody else
 * keeps up - or the plugin forwards {@code remoteSocket} over ssh to {@code host}, through a socket
 * of its own, or it is a WSL distribution the plugin reaches through {@code wsl.exe}, again through
 * a socket of its own. On Windows, which cannot forward to a unix socket, a machine over ssh has no
 * socket of the interface's: the plugin relays to {@code porelo daemon connect} there. Nothing
 * about a key is ever part of it: ssh finds that in the person's own configuration and agent.
 *
 * @param name What the person calls it.
 * @param socket The unix socket that is opened as it is; for a machine reached through a tunnel,
 *     the interface's own end of its tunnel, which the plugin never opens; {@code null} for a WSL
 *     distribution and for a machine the plugin relays to over ssh.
 * @param host Where the machine is, as {@code ssh} is given it, or {@code null} when no tunnel is
 *     needed.
 * @param remoteSocket The daemon's socket on that machine, or {@code null} when no tunnel is
 *     needed.
 * @param distribution The WSL distribution, or {@code null} for any other machine.
 */
public record Machine(
        String name,
        @Nullable Path socket,
        @Nullable String host,
        @Nullable String remoteSocket,
        @Nullable String distribution) {

    /**
     * Checks that a machine is reached one way, and that a tunnel's two ends come together.
     *
     * @param name What the person calls it.
     * @param socket The unix socket that is opened as it is, or {@code null} for a WSL distribution
     *     and for a machine relayed to over ssh.
     * @param host Where the machine is, or {@code null}.
     * @param remoteSocket The daemon's socket there, or {@code null}.
     * @param distribution The WSL distribution, or {@code null}.
     */
    public Machine {
        Objects.requireNonNull(name, "name");
        if (socket != null && distribution != null
                || socket == null && distribution == null && host == null) {
            throw new IllegalArgumentException(
                    "Machine '"
                            + name
                            + "' needs either a socket, a host or a WSL distribution,"
                            + " and not a socket and a distribution both");
        }
        if ((host == null) != (remoteSocket == null)) {
            throw new IllegalArgumentException(
                    "Machine '"
                            + name
                            + "' names "
                            + (host == null ? "a remote socket" : "a host")
                            + " without "
                            + (host == null ? "a host" : "a remote socket")
                            + "; a tunnel needs both");
        }
        // A relative socket would be opened where the IDE happens to run, and a remote one that is
        // no path, such as localhost:22, would make the tunnel forward to a port.
        if (socket != null && !socket.isAbsolute()) {
            throw new IllegalArgumentException(
                    "Machine '"
                            + name
                            + "' names the socket '"
                            + socket
                            + "', which is no absolute path");
        }
        if (remoteSocket != null && !remoteSocket.startsWith("/")) {
            throw new IllegalArgumentException(
                    "Machine '"
                            + name
                            + "' names the remote socket '"
                            + remoteSocket
                            + "', which is no absolute path");
        }
        if (distribution != null && host != null) {
            throw new IllegalArgumentException(
                    "Machine '" + name + "' is a WSL distribution and names a host too");
        }
    }

    /**
     * Creates a machine reached through a socket, directly or through an ssh tunnel.
     *
     * @param name What the person calls it.
     * @param socket The unix socket that is opened as it is.
     * @param host Where the machine is, or {@code null}.
     * @param remoteSocket The daemon's socket there, or {@code null}.
     */
    public Machine(String name, Path socket, @Nullable String host, @Nullable String remoteSocket) {
        this(name, Objects.requireNonNull(socket, "socket"), host, remoteSocket, null);
    }

    /**
     * Creates a machine that is a WSL distribution of this computer.
     *
     * @param name What the person calls it.
     * @param distribution The distribution's name, as {@code wsl.exe -l} lists it.
     * @return The machine.
     */
    public static Machine wsl(String name, String distribution) {
        return new Machine(
                name, null, null, null, Objects.requireNonNull(distribution, "distribution"));
    }

    /**
     * Creates a machine the plugin relays to over ssh, as it does from Windows.
     *
     * @param name What the person calls it.
     * @param host Where the machine is, as {@code ssh} is given it.
     * @param remoteSocket The daemon's socket there.
     * @return The machine.
     */
    public static Machine overSsh(String name, String host, String remoteSocket) {
        return new Machine(
                name,
                null,
                Objects.requireNonNull(host, "host"),
                Objects.requireNonNull(remoteSocket, "remoteSocket"),
                null);
    }

    /**
     * Tells whether the daemon is reached through an ssh tunnel the plugin raises.
     *
     * @return {@code true} when the machine names a socket, a host and a remote socket.
     */
    public boolean needsATunnel() {
        return host != null && socket != null;
    }

    /**
     * Tells whether the daemon is reached by relaying to {@code porelo daemon connect} over ssh.
     *
     * @return {@code true} when the machine names a host and no socket.
     */
    public boolean relayedOverSsh() {
        return host != null && socket == null;
    }

    /**
     * Tells whether the machine is a WSL distribution the plugin reaches through {@code wsl.exe}.
     *
     * @return {@code true} when it names a distribution.
     */
    public boolean inWsl() {
        return distribution != null;
    }
}
