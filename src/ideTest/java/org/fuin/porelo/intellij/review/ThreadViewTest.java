package org.fuin.porelo.intellij.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.intellij.diff.util.LineRange;
import com.intellij.diff.util.Side;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.junit5.TestApplication;
import javax.swing.JComponent;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.text.JTextComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A thread's text drawn as HTML could make the IDE fetch an image from wherever it names. */
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
                                    new CommentThread("t", Side.RIGHT, new LineRange(0, 1), text));

                    assertThat(view.getClientProperty(BasicHTML.propertyKey))
                            .as("the HTML view Swing builds for HTML text")
                            .isNull();
                    assertThat(((JTextComponent) view).getText())
                            .as("the text drawn")
                            .isEqualTo(text);
                    assertThat(((JTextComponent) view).isEditable())
                            .as("whether the person can type into a thread's text")
                            .isFalse();
                });
    }
}
