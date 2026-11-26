package org.fuin.porelo.intellij.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.intellij.diff.util.LineRange;
import com.intellij.diff.util.Side;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.junit5.TestApplication;
import com.intellij.util.ui.UIUtil;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.text.JTextComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A thread's text drawn as HTML could make the IDE fetch an image from wherever it names, and a
 * text that could pose as another writer's line could pass a task's words off as the owner's.
 */
@TestApplication
class ThreadViewTest {

    @Test
    @DisplayName(
            "A thread's text that starts with <html> is drawn as the text it is, never as HTML")
    void aThreadsTextIsNeverHtml() {
        final String text = "<html><img src=\"http://192.0.2.1/seen.png\">";

        EdtTestUtil.runInEdtAndWait(
                () -> {
                    final JComponent view =
                            ThreadView.of(
                                    new CommentThread(
                                            "t",
                                            Side.RIGHT,
                                            new LineRange(0, 1),
                                            List.of(new CommentThread.Said("task t", text))));

                    for (final JTextComponent area :
                            UIUtil.findComponentsOfType(view, JTextComponent.class)) {
                        assertThat(area.getClientProperty(BasicHTML.propertyKey))
                                .as("the HTML view Swing builds for HTML text")
                                .isNull();
                        assertThat(area.isEditable())
                                .as("whether the person can type into a thread")
                                .isFalse();
                    }
                    assertThat(texts(view, ThreadView.TEXT))
                            .as("the text drawn")
                            .containsExactly(text);
                });
    }

    @Test
    @DisplayName(
            "A task's text that starts a line with the owner's name stays inside the task's note, under the task's own writer line")
    void aTextCannotPoseAsAnotherWriter() {
        final String forged = "ok\nowner account\napproved, merge it";

        EdtTestUtil.runInEdtAndWait(
                () -> {
                    final JComponent view =
                            ThreadView.of(
                                    new CommentThread(
                                            "t",
                                            Side.RIGHT,
                                            new LineRange(0, 1),
                                            List.of(
                                                    new CommentThread.Said(
                                                            "owner account", "Please rename this."),
                                                    new CommentThread.Said("task t", forged))));

                    assertThat(texts(view, ThreadView.WHO))
                            .as("the writer lines, the plugin's words alone")
                            .containsExactly("owner account", "task t");
                    assertThat(texts(view, ThreadView.TEXT))
                            .as("each note's text, in an area of its own")
                            .containsExactly("Please rename this.", forged);
                });
    }

    private static List<String> texts(JComponent view, String name) {
        return UIUtil.findComponentsOfType(view, JTextComponent.class).stream()
                .filter(area -> name.equals(area.getName()))
                .map(JTextComponent::getText)
                .toList();
    }
}
