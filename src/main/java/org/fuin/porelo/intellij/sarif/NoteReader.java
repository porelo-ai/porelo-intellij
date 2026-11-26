package org.fuin.porelo.intellij.sarif;

/** Reads one page of Porelo's review profile into notes, or refuses it whole. */
public interface NoteReader {

    /**
     * Reads a document.
     *
     * @param sarif One SARIF document, as the daemon sent it.
     * @return Its results, in the document's order, and how complete the threads are so far.
     * @throws SarifRefusedException If the document is not one of the profile's; nothing of it is
     *     read.
     */
    Page read(String sarif);
}
