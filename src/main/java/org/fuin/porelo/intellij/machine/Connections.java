package org.fuin.porelo.intellij.machine;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.fuin.porelo.wire.LimitFactors;

/**
 * The tunnels, relays and WSL bridges the plugin holds to reach its machines' daemons, each kept
 * while it forwards and raised again when it dropped.
 */
public final class Connections implements AutoCloseable {

    private final Map<String, Tunnel> tunnels = new ConcurrentHashMap<>();

    private final Map<String, WslBridge> bridges = new ConcurrentHashMap<>();

    private final Map<String, Relay> relays = new ConcurrentHashMap<>();

    private final Supplier<Path> runtimeDirectory;

    private final List<String> ssh;

    private final List<String> wsl;

    private final boolean relay;

    private volatile boolean closed;

    /**
     * Holds no connection yet.
     *
     * @param runtimeDirectory Where the plugin's ends go, made when asked.
     * @param ssh The ssh command, {@code ssh}, or Windows' own {@code ssh.exe} there, unless a test
     *     stands in for it.
     * @param wsl The {@code wsl.exe} command, {@code wsl.exe} unless a test stands in for it.
     * @param relay Whether a machine over ssh without a socket of the interface's is relayed to
     *     through {@code porelo daemon connect}, as on Windows, which cannot forward to a unix
     *     socket; elsewhere the plugin raises its own tunnel to it.
     */
    public Connections(
            Supplier<Path> runtimeDirectory, List<String> ssh, List<String> wsl, boolean relay) {
        this.runtimeDirectory = runtimeDirectory;
        this.ssh = List.copyOf(ssh);
        this.wsl = List.copyOf(wsl);
        this.relay = relay;
    }

    /**
     * Returns the socket a gate to a machine connects to, raising its tunnel, relay or bridge first
     * when it needs one that is not up.
     *
     * @param machine The machine.
     * @param factors The factors last read from the machine, or all four at 1.0; one raised now
     *     waits on its start by them.
     * @return Its daemon's socket, or the plugin's end of a tunnel, relay or bridge to it.
     * @throws MachinesException If the tunnel, relay or bridge cannot be raised.
     */
    public Path socketOf(Machine machine, LimitFactors factors) {
        // A read or a decision that ends after the IDE closed would leave a new ssh behind.
        if (closed) {
            throw new MachinesException(
                    "The plugin is closing; '" + machine.name() + "' is not reached");
        }
        if (machine.inWsl()) {
            return bridges.compute(
                            machine.name(),
                            (name, existing) -> {
                                if (existing != null && existing.up()) {
                                    return existing;
                                }
                                if (existing != null) {
                                    existing.close();
                                }
                                return WslBridge.open(
                                        machine, runtimeDirectory.get(), wsl, factors);
                            })
                    .socket();
        }
        if (machine.relayedOverSsh() && relay) {
            return relays.compute(
                            machine.name(),
                            (name, existing) -> {
                                if (existing != null && existing.up()) {
                                    return existing;
                                }
                                if (existing != null) {
                                    existing.close();
                                }
                                return Relay.overSsh(machine, runtimeDirectory.get(), ssh, factors);
                            })
                    .socket();
        }
        if (!machine.needsATunnel() && !machine.relayedOverSsh()) {
            return Objects.requireNonNull(
                    machine.socket(), "a machine reached directly has a socket");
        }
        return tunnels.compute(
                        machine.name(),
                        (name, existing) -> {
                            if (existing != null && existing.up()) {
                                return existing;
                            }
                            if (existing != null) {
                                existing.close();
                            }
                            return Tunnel.raise(machine, runtimeDirectory.get(), ssh, factors);
                        })
                .socket();
    }

    @Override
    public void close() {
        closed = true;
        // Each is closed even if another fails, so no ssh or wsl.exe outlives the IDE for that.
        final List<AutoCloseable> all = new java.util.ArrayList<>(tunnels.values());
        all.addAll(bridges.values());
        all.addAll(relays.values());
        tunnels.clear();
        bridges.clear();
        relays.clear();
        MachinesException failed = null;
        for (final AutoCloseable connection : all) {
            try {
                connection.close();
            } catch (Exception ex) {
                if (failed == null) {
                    failed =
                            new MachinesException(
                                    "Cannot close a connection: " + ex.getMessage(), ex);
                } else {
                    failed.addSuppressed(ex);
                }
            }
        }
        if (failed != null) {
            throw failed;
        }
    }
}
