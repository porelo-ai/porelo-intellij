package org.fuin.porelo.intellij.ide;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.fuin.porelo.intellij.gate.Waiting;
import org.jspecify.annotations.Nullable;

/**
 * What the watch last read.
 *
 * @param waiting What waits, and which machines did not answer.
 * @param unreadable Why the machines could not be read at all, or {@code null} when they could.
 * @param at When it was read, or {@code null} before the first read.
 */
public record Snapshot(Waiting waiting, @Nullable String unreadable, @Nullable Instant at) {

    /** Nothing read yet. */
    public static final Snapshot NONE = new Snapshot(new Waiting(List.of(), List.of()), null, null);

    /**
     * Reads what waits, or says why it could not be read, keeping what was read before.
     *
     * @param before What was read last.
     * @param read Reads what waits now.
     * @return The new snapshot.
     */
    static Snapshot after(Snapshot before, Supplier<Waiting> read) {
        try {
            return new Snapshot(read.get(), null, Instant.now());
        } catch (RuntimeException ex) {
            // Whatever failed is said: a scheduled read that threw would never run again.
            return new Snapshot(before.waiting(), String.valueOf(ex.getMessage()), Instant.now());
        }
    }
}
