package org.fuin.porelo.intellij.ide;

import org.fuin.porelo.intellij.gate.Waiting;

/**
 * The words of the notice that a push waits for review. IntelliJ shows a notice as markup, and what
 * it says comes from the agent under review and its daemon, so every name is text.
 */
final class Notices {

    private Notices() {}

    /**
     * Returns the notice's title.
     *
     * @param entry The push.
     * @return The title.
     */
    static String title(Waiting.Entry entry) {
        return "Waits for review: " + escaped(entry.push().subject());
    }

    /**
     * Returns the notice's text.
     *
     * @param entry The push.
     * @return The text.
     */
    static String content(Waiting.Entry entry) {
        return escaped(entry.push().name())
                + " in "
                + escaped(entry.project())
                + " / "
                + escaped(entry.repository())
                + " on "
                + escaped(entry.machine().name())
                + (entry.farReaching() > 0
                        ? " - " + entry.farReaching() + " far-reaching file(s)"
                        : "");
    }

    private static String escaped(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
