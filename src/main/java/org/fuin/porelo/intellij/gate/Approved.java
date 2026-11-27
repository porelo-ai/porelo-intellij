package org.fuin.porelo.intellij.gate;

import org.jspecify.annotations.Nullable;

/**
 * What an approve did: the push went onto its branch, and whether its end could be kept for the
 * task that pushed it.
 *
 * @param forwarded What was forwarded and onto which branch, as the daemon says it.
 * @param endUnkept The daemon's sentence saying the push went but its end could not be kept for its
 *     task; {@code null} when it was kept or there was none to keep.
 */
public record Approved(String forwarded, @Nullable String endUnkept) {}
