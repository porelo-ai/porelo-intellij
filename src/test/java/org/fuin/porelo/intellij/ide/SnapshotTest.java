package org.fuin.porelo.intellij.ide;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.fuin.porelo.intellij.gate.PendingPush;
import org.fuin.porelo.intellij.gate.Waiting;
import org.fuin.porelo.intellij.machine.Machine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    @Test
    @DisplayName(
            "An open review is told what a read says of its push, and nothing before the first read or after a read that failed, never that it waits no more")
    void nothingIsSaidWithoutARead(@TempDir Path dir) {
        final Waiting.Entry shown =
                new Waiting.Entry(
                        new Machine("m", dir.resolve("m.sock")),
                        "p",
                        "r",
                        "u",
                        "/m",
                        new PendingPush("push", "c", "s", "w", "at", "f"),
                        0);
        final Snapshot read =
                Snapshot.after(Snapshot.NONE, () -> new Waiting(List.of(), List.of()));
        final Snapshot failed =
                Snapshot.after(
                        read,
                        () -> {
                            throw new IllegalStateException("boom");
                        });

        assertThat(read.now(shown))
                .as("read, and it waits no more")
                .isEqualTo(new Waiting.Now.Gone());
        assertThat(Snapshot.NONE.now(shown))
                .as("nothing read yet")
                .isEqualTo(new Waiting.Now.Unknown());
        assertThat(failed.now(shown))
                .as("the read after it failed")
                .isEqualTo(new Waiting.Now.Unknown());
    }
}
