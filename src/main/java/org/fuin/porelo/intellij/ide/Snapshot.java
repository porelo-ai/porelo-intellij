package org.fuin.porelo.intellij.ide;

import java.time.Instant;
import java.util.List;
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
}
