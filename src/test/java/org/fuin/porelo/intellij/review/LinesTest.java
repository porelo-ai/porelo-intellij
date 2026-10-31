package org.fuin.porelo.intellij.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.intellij.diff.util.LineRange;
import com.intellij.diff.util.Side;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A comment on the wrong line or side is a comment on code the reviewer did not mean. */
class LinesTest {

    @Test
    @DisplayName("SARIF's 1-based lines, both ends included, become the viewer's 0-based range")
    void sarifLinesBecomeTheViewersRange() {
        assertThat(Lines.toViewer("new", 3, 5, 10))
                .as("lines 3 to 5 of the new side")
                .isEqualTo(new Lines.Located(Side.RIGHT, new LineRange(2, 5)));
        assertThat(Lines.toViewer("old", 1, 1, 1))
                .as("the one line of a one-line old side")
                .isEqualTo(new Lines.Located(Side.LEFT, new LineRange(0, 1)));
    }

    @Test
    @DisplayName("A range the viewer selected becomes the SARIF region it came from")
    void aSelectedRangeBecomesItsRegion() {
        assertThat(Lines.toSarif(Side.LEFT, new LineRange(2, 5)))
                .as("the viewer's lines 2 to 4 of the left side")
                .isEqualTo(new Lines.Region("old", 3, 5));
        assertThat(Lines.toSarif(Side.RIGHT, Lines.toViewer("new", 7, 9, 9).range()))
                .as("there and back again, up to the last line")
                .isEqualTo(new Lines.Region("new", 7, 9));
    }

    @Test
    @DisplayName("Lines not on their side, or a side that is neither old nor new, are refused")
    void linesNotOnTheirSideAreRefused() {
        assertThatThrownBy(() -> Lines.toViewer("new", 0, 1, 5))
                .as("line 0, which SARIF does not have")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Lines.toViewer("new", 4, 6, 5))
                .as("a range past the side's last line")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has 5");
        assertThatThrownBy(() -> Lines.toViewer("new", 3, 2, 5))
                .as("a range ending before it starts")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Lines.toViewer("left", 1, 1, 5))
                .as("the viewer's name for a side, not SARIF's")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'left'");
        assertThatThrownBy(() -> Lines.toSarif(Side.RIGHT, new LineRange(3, 3)))
                .as("an empty selection")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Lines.toSarif(Side.RIGHT, new LineRange(-1, 1)))
                .as("a selection from before the first line")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName(
            "A thread on no line, which would be drawn after the line before its own, is refused")
    void aThreadOnNoLineIsRefused() {
        assertThatThrownBy(() -> new CommentThread("t", Side.RIGHT, new LineRange(2, 2), "x"))
                .as("a thread on an empty range")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Thread t");
        assertThatThrownBy(() -> new CommentThread("t", Side.RIGHT, new LineRange(-1, 1), "x"))
                .as("a thread from before the first line")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
