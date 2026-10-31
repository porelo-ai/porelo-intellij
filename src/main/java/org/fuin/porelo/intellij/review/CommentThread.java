package org.fuin.porelo.intellij.review;

import com.intellij.diff.util.LineRange;
import com.intellij.diff.util.Side;

/**
 * A comment thread on lines of one side of a diff.
 *
 * @param id What tells it from the others.
 * @param side The side.
 * @param range The lines, from 0, the end excluded; at least one.
 * @param text What it says.
 */
public record CommentThread(String id, Side side, LineRange range, String text) {

    /**
     * Refuses a thread on no line, which would be drawn after the line before its own.
     *
     * @throws IllegalArgumentException if the range holds no line.
     */
    public CommentThread {
        if (range.start < 0 || range.end <= range.start) {
            throw new IllegalArgumentException("Thread " + id + " is on no line: " + range);
        }
    }
}
