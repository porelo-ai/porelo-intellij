package org.fuin.porelo.intellij.sarif;

import java.util.List;

/** Reads one document of Porelo's review profile into notes, or refuses it whole. */
public interface NoteReader {

    /**
     * Refuses every document: the plugin holds no profile reader yet, and reads nothing unchecked.
     */
    NoteReader NONE =
            sarif -> {
                throw new SarifRefusedException(
                        "unread",
                        "",
                        "the plugin cannot read review comments yet: Porelo's profile reader is not"
                                + " part of it");
            };

    /**
     * Reads a document.
     *
     * @param sarif One SARIF document, as the daemon sent it.
     * @return Its results, in the document's order.
     * @throws SarifRefusedException If the document is not one of the profile's; nothing of it is
     *     read.
     */
    List<Note> read(String sarif);
}
