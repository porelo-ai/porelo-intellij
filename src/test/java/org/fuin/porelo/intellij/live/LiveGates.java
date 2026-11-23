package org.fuin.porelo.intellij.live;

import java.nio.file.Path;
import org.fuin.porelo.intellij.gate.Gate;

/** Opens a gate to a real daemon as the plugin does, its factors read before anything else. */
final class LiveGates {

    private LiveGates() {}

    /**
     * Opens a gate and reads the daemon's factors, so its later calls wait as long as the daemon
     * says.
     *
     * @param socket The daemon's socket, as a tunnel or relay gives it.
     * @return The gate, its factors read.
     */
    static Gate opened(Path socket) {
        final Gate gate = new Gate(socket);
        try {
            gate.limits();
            return gate;
        } catch (RuntimeException ex) {
            gate.close();
            throw ex;
        }
    }
}
