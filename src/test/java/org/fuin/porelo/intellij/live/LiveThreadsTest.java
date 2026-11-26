package org.fuin.porelo.intellij.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.intellij.gate.Gate;
import org.fuin.porelo.intellij.gate.Waiting;
import org.fuin.porelo.intellij.machine.Machine;
import org.fuin.porelo.intellij.machine.Tunnel;
import org.fuin.porelo.intellij.sarif.Note;
import org.fuin.porelo.intellij.sarif.Page;
import org.fuin.porelo.intellij.sarif.ProfileNoteReader;
import org.fuin.porelo.wire.LimitFactors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reads the threads of a real waiting push with the plugin's profile reader - run alone, as {@code
 * ./gradlew liveTest}, never in the build.
 *
 * <p>The machine and push are the ones {@code LiveGateTest} names. {@code PORELO_LIVE_THREAD} is
 * the text of a line comment the owner account wrote on that push with {@code porelo gate comment},
 * and {@code PORELO_LIVE_BASE} the commit the push and its branch last met, which the plugin's
 * cache compares it with.
 */
@Tag("live")
class LiveThreadsTest {

    @TempDir Path runtime;

    private static String env(String name) {
        final String value = System.getenv(name);
        assertThat(value).as("the environment variable " + name).isNotBlank();
        return value;
    }

    @Test
    @DisplayName(
            "The owner's comment on a real waiting push is read whole through the profile reader, under the owner account's name")
    void readsTheOwnersThreadOfARealPush() {
        final String said = env("PORELO_LIVE_THREAD");
        final Machine machine =
                new Machine(
                        "live",
                        Path.of("/nowhere.sock"),
                        env("PORELO_LIVE_HOST"),
                        env("PORELO_LIVE_SOCKET"),
                        Path.of(env("PORELO_LIVE_SSH_CONFIG")));
        final String task = env("PORELO_LIVE_TASK");
        final String commit = env("PORELO_LIVE_COMMIT");

        try (Tunnel tunnel = Tunnel.raise(machine, runtime, List.of("ssh"), LimitFactors.DEFAULT)) {
            final Waiting waiting =
                    Waiting.read(List.of(machine), any -> LiveGates.opened(tunnel.socket()));
            final Waiting.Entry entry =
                    waiting.pushes().stream()
                            .filter(push -> push.push().name().equals(task))
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("no push of " + task));

            try (Gate gate = LiveGates.opened(tunnel.socket())) {
                final ProfileNoteReader reader =
                        new ProfileNoteReader(
                                entry.project(),
                                entry.repository(),
                                task,
                                commit,
                                env("PORELO_LIVE_BASE"));
                final List<Note> notes = new ArrayList<>();
                final List<String> pages = gate.comments(entry.project(), entry.repository(), task);
                assertThat(pages).as("the pages of the push's threads").isNotEmpty();
                Page last = new Page(List.of(), "none read");
                for (final String page : pages) {
                    last = reader.read(page);
                    notes.addAll(last.notes());
                }

                assertThat(last.completeness()).as("the last page's word").isEqualTo("complete");
                assertThat(notes)
                        .filteredOn(note -> note.text().equals(said))
                        .extracting(note -> note.origin().author())
                        .as("the owner's comment, under its writer")
                        .containsExactly("owner account");
            }
        }
    }
}
