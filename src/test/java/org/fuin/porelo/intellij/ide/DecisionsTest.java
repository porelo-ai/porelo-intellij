package org.fuin.porelo.intellij.ide;

import static org.assertj.core.api.Assertions.assertThat;

import com.intellij.notification.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What a person is told once an approve or reject stood. */
class DecisionsTest {

    @Test
    @DisplayName("A decision whose push's end was kept reads as before, as information")
    void saysAKeptEndAsBefore() {
        final Decisions.Said said = new Decisions.Said("Forwarded app onto main", null);

        assertThat(said.text()).as("its text").isEqualTo("Forwarded app onto main");
        assertThat(new Decisions.Said("Forwarded <b>app</b>", null).text())
                .as("a kept end's text, as text")
                .isEqualTo("Forwarded &lt;b&gt;app&lt;/b&gt;");
        assertThat(said.type()).as("its mark").isEqualTo(NotificationType.INFORMATION);
    }

    @Test
    @DisplayName(
            "A decision whose push's end could not be kept says the daemon's sentence below it, as a warning")
    void warnsOfAnEndNotKept() {
        final Decisions.Said said =
                new Decisions.Said(
                        "Rejected; its task was told",
                        "Rejected, but its task's end could not be kept.");

        assertThat(said.text())
                .as("its text, the sentence on a line of its own in IntelliJ's markup")
                .isEqualTo(
                        "Rejected; its task was told<br>"
                                + "Rejected, but its task&#39;s end could not be kept.");
        assertThat(said.type()).as("its mark").isEqualTo(NotificationType.WARNING);
    }

    @Test
    @DisplayName("What the daemon says is shown as text, never read as markup")
    void showsTheDaemonsWordsAsText() {
        final Decisions.Said said =
                new Decisions.Said("Forwarded <b>app</b> onto main", "Kept <i>nothing</i> & more");

        assertThat(said.text())
                .as("its text")
                .isEqualTo(
                        "Forwarded &lt;b&gt;app&lt;/b&gt; onto main<br>"
                                + "Kept &lt;i&gt;nothing&lt;/i&gt; &amp; more");
    }
}
