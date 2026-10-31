package org.fuin.porelo.intellij.review;

import com.intellij.diff.util.LineRange;
import com.intellij.diff.util.Side;

/**
 * Between a SARIF region - 1-based lines, both ends included, on the old or the new side of a diff
 * - and the diff viewer's 0-based line range, whose end is excluded, on its left or right side.
 */
public final class Lines {

    private Lines() {}

    /**
     * Where a SARIF region lies in a diff viewer.
     *
     * @param diffSide {@code old} or {@code new}, as {@code porelo/diffSide} says.
     * @param startLine The first line, from 1.
     * @param endLine The last line, included.
     * @param lineCount How many lines that side has.
     * @return The side and the range.
     * @throws IllegalArgumentException if the side is neither, or the lines are not on that side.
     */
    public static Located toViewer(String diffSide, int startLine, int endLine, int lineCount) {
        final Side side =
                switch (diffSide) {
                    case "old" -> Side.LEFT;
                    case "new" -> Side.RIGHT;
                    default ->
                            throw new IllegalArgumentException(
                                    "porelo/diffSide is '" + diffSide + "', not old or new");
                };
        if (startLine < 1 || endLine < startLine || endLine > lineCount) {
            throw new IllegalArgumentException(
                    "Lines "
                            + startLine
                            + "-"
                            + endLine
                            + " are not on the "
                            + diffSide
                            + " side, which has "
                            + lineCount);
        }
        return new Located(side, new LineRange(startLine - 1, endLine));
    }

    /**
     * The SARIF region of a range the viewer selected.
     *
     * @param side The viewer's side.
     * @param range The selected lines; not empty.
     * @return The region.
     * @throws IllegalArgumentException if the range is empty.
     */
    public static Region toSarif(Side side, LineRange range) {
        if (range.start < 0 || range.end <= range.start) {
            throw new IllegalArgumentException("No lines in " + range);
        }
        return new Region(side == Side.LEFT ? "old" : "new", range.start + 1, range.end);
    }

    /**
     * A place in the diff viewer.
     *
     * @param side The side.
     * @param range The lines, from 0, the end excluded.
     */
    public record Located(Side side, LineRange range) {}

    /**
     * A place as SARIF and {@code porelo/diffSide} say it.
     *
     * @param diffSide {@code old} or {@code new}.
     * @param startLine The first line, from 1.
     * @param endLine The last line, included.
     */
    public record Region(String diffSide, int startLine, int endLine) {}
}
