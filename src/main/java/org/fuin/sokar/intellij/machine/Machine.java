package org.fuin.sokar.intellij.machine;

import java.nio.file.Path;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * One machine whose daemon the person reaches.
 * <p>
 * Either its socket is opened as it is - this computer's daemon, or a forward somebody else keeps up - or the plugin
 * forwards {@code remoteSocket} over ssh to {@code host}, through a socket of its own, or it is a WSL distribution the
 * plugin reaches through {@code wsl.exe}, again through a socket of its own. Nothing about a key is ever part of it:
 * ssh finds that in the person's own configuration and agent.
 *
 * @param name What the person calls it.
 * @param socket The unix socket that is opened as it is; for a machine reached through a tunnel, the interface's own
 *     end of its tunnel, which the plugin never opens; {@code null} for a WSL distribution.
 * @param host Where the machine is, as {@code ssh} is given it, or {@code null} when no tunnel is needed.
 * @param remoteSocket The daemon's socket on that machine, or {@code null} when no tunnel is needed.
 * @param distribution The WSL distribution, or {@code null} for any other machine.
 */
public record Machine(String name, @Nullable Path socket, @Nullable String host, @Nullable String remoteSocket,
        @Nullable String distribution) {

    /**
     * Checks that a machine is reached one way, and that a tunnel's two ends come together.
     *
     * @param name What the person calls it.
     * @param socket The unix socket that is opened as it is, or {@code null} for a WSL distribution.
     * @param host Where the machine is, or {@code null}.
     * @param remoteSocket The daemon's socket there, or {@code null}.
     * @param distribution The WSL distribution, or {@code null}.
     */
    public Machine {
        Objects.requireNonNull(name, "name");
        if ((socket == null) == (distribution == null)) {
            throw new IllegalArgumentException("Machine '" + name + "' needs either a socket or a WSL distribution,"
                    + " and not both");
        }
        if ((host == null) != (remoteSocket == null)) {
            throw new IllegalArgumentException("Machine '" + name + "' names " + (host == null ? "a remote socket"
                    : "a host") + " without " + (host == null ? "a host" : "a remote socket")
                    + "; a tunnel needs both");
        }
        if (distribution != null && host != null) {
            throw new IllegalArgumentException("Machine '" + name + "' is a WSL distribution and names a host too");
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
        return new Machine(name, null, null, null, Objects.requireNonNull(distribution, "distribution"));
    }

    /**
     * Tells whether the daemon is reached through an ssh tunnel the plugin raises.
     *
     * @return {@code true} when the machine names a host and a remote socket.
     */
    public boolean needsATunnel() {
        return host != null;
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
