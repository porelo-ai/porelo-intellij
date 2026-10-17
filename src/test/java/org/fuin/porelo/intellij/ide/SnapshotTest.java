package org.fuin.porelo.intellij.ide;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.fuin.porelo.intellij.gate.Waiting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Reads what waits into the snapshot the watch shows. */
class SnapshotTest {

    @Test
    @DisplayName(
            "A read that fails in any way is said and the last list kept, so the watch never stops reading without a word")
    void aReadThatFailsIsSaid() {
        final Snapshot before =
                new Snapshot(
                        new Waiting(List.of(), List.of(new Waiting.Silent("m", "why"))),
                        null,
                        null);

        final Snapshot after =
                Snapshot.after(
                        before,
                        () -> {
                            throw new IllegalStateException("boom");
                        });

        assertThat(after.unreadable()).as("why nothing was read").contains("boom");
        assertThat(after.waiting()).as("what is shown meanwhile").isEqualTo(before.waiting());
    }
}
