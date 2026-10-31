package org.fuin.porelo.intellij.review;

import com.intellij.ui.components.JBTextArea;
import javax.swing.JComponent;

/** How a thread is drawn after its lines. */
final class ThreadView {

    private ThreadView() {}

    /**
     * Draws a thread's text as it is, every line of it, wrapped: a plain text area, as Swing reads
     * label text that starts with {@code <html>} as HTML, which could load images from anywhere,
     * and a thread's text comes from whoever wrote it.
     *
     * @param thread The thread.
     * @return Its view.
     */
    static JComponent of(CommentThread thread) {
        final JBTextArea text = new JBTextArea(thread.text());
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setOpaque(false);
        return text;
    }
}
