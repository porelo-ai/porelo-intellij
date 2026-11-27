package org.fuin.porelo.intellij.gate;

import org.jspecify.annotations.Nullable;

/**
 * What a reject did: the push was dropped, whether its task was told, and whether its end could be
 * kept for that task.
 *
 * @param told Whether the task was told; {@code false} when it is gone from the machine.
 * @param endUnkept The daemon's sentence saying the push was dropped but its end could not be kept
 *     for its task; {@code null} when it was kept or there was none to keep.
 */
public record Rejected(boolean told, @Nullable String endUnkept) {}
