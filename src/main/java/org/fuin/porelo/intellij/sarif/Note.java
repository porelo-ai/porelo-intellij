package org.fuin.porelo.intellij.sarif;

import org.jspecify.annotations.Nullable;

/**
 * One result of a review document, as the plugin shows it; a field the daemon did not send is
 * absent, never empty.
 *
 * @param guid The daemon's id of it.
 * @param kind {@code review}, {@code fail} or another of SARIF's kinds.
 * @param level {@code none}, {@code note}, {@code warning} or {@code error}.
 * @param text What it says, as plain text.
 * @param anchor Where it is.
 * @param thread Which thread it belongs to, and what it answers.
 * @param origin Who wrote it, as the daemon stamped it.
 * @param outdated Whether the daemon marked its place as gone since it was written.
 * @param fix Its suggested replacement; absent when it has none.
 */
public record Note(
        String guid,
        String kind,
        String level,
        String text,
        Anchor anchor,
        Threading thread,
        Origin origin,
        boolean outdated,
        @Nullable Fix fix) {

    /**
     * Where a note is.
     *
     * @param kind {@code line}, {@code file} or {@code general}, or a newer word, kept as it is.
     * @param path The file, repository-relative and canonical; absent for {@code general}.
     * @param side {@code old} or {@code new}; absent where the profile has none.
     * @param region The lines; absent for {@code file} and {@code general}.
     */
    public record Anchor(
            String kind, @Nullable String path, @Nullable String side, @Nullable Region region) {}

    /**
     * Lines of one side, from 1, both ends included.
     *
     * @param startLine The first.
     * @param endLine The last.
     */
    public record Region(int startLine, int endLine) {}

    /**
     * A note's thread.
     *
     * @param id The thread's id.
     * @param replyTo The note it answers; absent for the thread's first.
     * @param state {@code open} or {@code resolved}, or a newer word; absent when not stamped.
     */
    public record Threading(String id, @Nullable String replyTo, @Nullable String state) {}

    /**
     * Who wrote a note, as the daemon stamped it; the body never speaks for this.
     *
     * @param sourceKind {@code person}, {@code task}, {@code forge} or {@code scanner}.
     * @param sourceLevel {@code E0}, {@code E1}, {@code I1} or {@code A}; absent is treated as
     *     {@code E0}.
     * @param author The author as a person reads it; absent when not stamped.
     */
    public record Origin(String sourceKind, @Nullable String sourceLevel, @Nullable String author) {

        /**
         * Tells whether the source is trusted at least as a project's own account: {@code E1},
         * {@code I1} or {@code A}, never {@code E0}, {@code unknown} or absent.
         *
         * @return {@code true} for a known, trusted level.
         */
        public boolean trusted() {
            final String level = sourceLevel;
            return level != null && (level.equals("E1") || level.equals("I1") || level.equals("A"));
        }
    }

    /**
     * A suggested replacement of lines.
     *
     * @param deleted The lines it replaces, on the {@code new} side.
     * @param inserted What it puts there.
     */
    public record Fix(Region deleted, String inserted) {}
}
