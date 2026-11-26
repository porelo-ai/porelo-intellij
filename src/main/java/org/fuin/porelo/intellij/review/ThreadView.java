package org.fuin.porelo.intellij.review;

import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import java.awt.Font;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;

/** How a thread is drawn after its lines. */
public final class ThreadView {

    /** The name of the area that says who wrote a note. */
    public static final String WHO = "porelo.thread.who";

    /** The name of the area that holds what a note says. */
    public static final String TEXT = "porelo.thread.text";

    private ThreadView() {}

    /**
     * Draws a thread one note below the other: who wrote it on a line of its own, then its text,
     * indented, in an area of its own, so no text can pose as another writer's line. Every string
     * is a plain text area, as Swing reads label text that starts with {@code <html>} as HTML,
     * which could load images from anywhere, and a thread's text comes from whoever wrote it.
     *
     * @param thread The thread.
     * @return Its view.
     */
    public static JComponent of(CommentThread thread) {
        return of(thread.notes());
    }

    /**
     * Draws notes as {@link #of(CommentThread)} does, for a thread that stands at no line.
     *
     * @param notes Its notes, its first one first.
     * @return Their view.
     */
    public static JComponent of(java.util.List<CommentThread.Said> notes) {
        final JPanel view = new JPanel();
        view.setLayout(new BoxLayout(view, BoxLayout.Y_AXIS));
        view.setOpaque(false);
        for (final CommentThread.Said said : notes) {
            final JBTextArea who = area(said.who());
            who.setName(WHO);
            who.setFont(who.getFont().deriveFont(Font.BOLD));
            final JBTextArea text = area(said.text());
            text.setName(TEXT);
            text.setBorder(JBUI.Borders.emptyLeft(16));
            view.add(who);
            view.add(text);
        }
        return view;
    }

    /**
     * Returns a plain text area that is never read as HTML and cannot be typed into.
     *
     * @param shown Its text.
     * @return The area.
     */
    public static JBTextArea area(String shown) {
        final JBTextArea area = new JBTextArea(shown);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        return area;
    }
}
