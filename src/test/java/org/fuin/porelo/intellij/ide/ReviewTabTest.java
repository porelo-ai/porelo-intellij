package org.fuin.porelo.intellij.ide;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.fuin.porelo.intellij.cache.Changes;
import org.fuin.porelo.intellij.gate.GateException;
import org.fuin.porelo.intellij.gate.Review;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Puts what the cache says a push changes in the order the daemon ranks it. */
class ReviewTabTest {

    @Test
    @DisplayName(
            "A file the daemon ranks far-reaching but the cache's diff lacks is refused, so a review never shows less than what is approved")
    void aRankedFileTheDiffLacksIsRefused() {
        final Review review =
                new Review(
                        "",
                        "",
                        List.of(
                                new Review.File(
                                        ".github/workflows/x.yml",
                                        "M",
                                        1,
                                        1,
                                        "DANGEROUS",
                                        "a workflow")),
                        "");
        final Changes changes =
                new Changes(
                        "main",
                        "0123456789abcdef0123456789abcdef01234567",
                        "1123456789abcdef0123456789abcdef01234567",
                        List.of(new Changes.Change("M", "A.java")));

        assertThatThrownBy(() -> ReviewTab.ordered(review, changes))
                .as("a ranked file the diff does not show")
                .isInstanceOf(GateException.class)
                .hasMessageContaining(".github/workflows/x.yml");
    }
}
