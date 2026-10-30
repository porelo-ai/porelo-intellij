package org.fuin.porelo.intellij.ide;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.fuin.porelo.intellij.gate.PendingPush;
import org.fuin.porelo.intellij.gate.Waiting;
import org.fuin.porelo.intellij.machine.Machine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Says in a notice that a push waits, in words the agent that pushed it cannot shape. */
class NoticesTest {

    @Test
    @DisplayName(
            "The agent's commit subject and the daemon's names reach the notice as text, never as markup that could make it say something else")
    void markupFromTheAgentIsText() {
        final Waiting.Entry entry =
                new Waiting.Entry(
                        new Machine("m<i>", Path.of("/m.sock").toAbsolutePath()),
                        "p",
                        "r",
                        "u",
                        "/m",
                        new PendingPush(
                                "app",
                                "0123456789abcdef0123456789abcdef01234567",
                                "Fix</b><br>0 far-reaching <a href=\"x\">file(s)</a>",
                                "w",
                                "at",
                                "f"),
                        1);

        assertThat(Notices.title(entry))
                .as("the title")
                .contains("Fix&lt;/b&gt;&lt;br&gt;")
                .doesNotContain("<b", "</b>", "<br>", "<a ");
        assertThat(Notices.content(entry))
                .as("the text")
                .contains("m&lt;i&gt;")
                .doesNotContain("<i>");
    }
}
