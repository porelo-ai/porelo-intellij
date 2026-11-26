package org.fuin.porelo.intellij.review;

import com.intellij.diff.util.LineRange;
import com.intellij.diff.util.Side;
import java.util.List;

/**
 * A comment thread on lines of one side of a diff.
 *
 * @param id What tells it from the others.
 * @param side The side.
 * @param range The lines, from 0, the end excluded; at least one.
 * @param notes What was said in it, its first note first; at least one.
 */
public record CommentThread(String id, Side side, LineRange range, List<Said> notes) {

    /**
     * Refuses a thread on no line, which would be drawn after the line before its own, and one in
     * which nothing was said.
     *
     * @throws IllegalArgumentException if the range holds no line or the thread no note.
     */
    public CommentThread {
        if (range.start < 0 || range.end <= range.start) {
            throw new IllegalArgumentException("Thread " + id + " is on no line: " + range);
        }
        if (notes.isEmpty()) {
            throw new IllegalArgumentException("Thread " + id + " holds no note");
        }
        notes = List.copyOf(notes);
    }

    /**
     * One note of a thread.
     *
     * @param who Who wrote it, in the plugin's own words, never the note's.
     * @param text What it says, as written.
     */
    public record Said(String who, String text) {}
}
