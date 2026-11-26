package org.fuin.porelo.intellij.gate;

import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * One item a task's run holds for the owner account's decision: a comment or reply on its way to a
 * forge, or one that came from the forge and was withheld from the task.
 *
 * @param id The item's id within its task.
 * @param task The task that holds it.
 * @param place The name of the place it belongs to.
 * @param direction Whether it goes out to the forge or came in from it.
 * @param destination Where it goes or came from, as the daemon names it.
 * @param operation What it does there: {@code comment} opens a thread at a file's lines, {@code
 *     reply} answers a thread.
 * @param anchor A comment's file, side and lines; {@code null} for a reply.
 * @param thread The thread a reply answers; {@code null} for a comment.
 * @param suggestion A comment's proposed replacement of its lines, exact; {@code null} when it has
 *     none.
 * @param commit The full commit id it was held for.
 * @param text Its exact text, shown as plain text only.
 * @param digest The daemon's digest of everything a release sends, which a decision names.
 * @param from An incoming item's forge account; {@code null} for an outgoing one.
 * @param at When it was held, in UTC to the second.
 * @param run The number of the run that holds it.
 * @param mark What the daemon marks about how the item reached the hold, such as {@code unsigned}
 *     when no certified key of the task signed it; {@code null} when it came signed.
 */
public record HeldItem(
        String id,
        String task,
        String place,
        Direction direction,
        String destination,
        String operation,
        @Nullable Anchor anchor,
        @Nullable String thread,
        @Nullable String suggestion,
        String commit,
        String text,
        String digest,
        @Nullable String from,
        String at,
        int run,
        @Nullable String mark) {

    /**
     * Where a comment stands.
     *
     * @param path The file, relative to the repository.
     * @param side {@code old} for the base's file, {@code new} for the reviewed commit's.
     * @param startLine The first line, counted from 1.
     * @param endLine The last line, inclusive.
     */
    public record Anchor(String path, String side, int startLine, int endLine) {}

    /** Which way an item was going when it was held. */
    public enum Direction {
        /** On its way from the task to the forge: released or refused. */
        OUTGOING(Decision.RELEASE, Decision.REFUSE),
        /** From the forge to the task, withheld: passed or dismissed. */
        INCOMING(Decision.PASS, Decision.DISMISS);

        private final List<Decision> decisions;

        Direction(Decision... decisions) {
            this.decisions = List.of(decisions);
        }

        /**
         * Returns the decisions an item of this direction takes.
         *
         * @return Its two decisions, the one that lets it through first.
         */
        public List<Decision> decisions() {
            return decisions;
        }

        /**
         * Returns the daemon's name of it.
         *
         * @return {@code outgoing} or {@code incoming}.
         */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** What the owner account decides about one item. */
    public enum Decision {
        /** An outgoing item goes to the forge. */
        RELEASE("Release"),
        /** An outgoing item never goes out; the task sees that in its next run. */
        REFUSE("Refuse"),
        /** An incoming item reaches the task. */
        PASS("Pass"),
        /** An incoming item never reaches the task. */
        DISMISS("Dismiss");

        private final String label;

        Decision(String label) {
            this.label = label;
        }

        /**
         * Returns how a person is shown it.
         *
         * @return Its label.
         */
        public String label() {
            return label;
        }

        /**
         * Returns the daemon's name of it.
         *
         * @return Such as {@code release}.
         */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
