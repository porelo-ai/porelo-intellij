package org.fuin.porelo.intellij.sarif;

import java.util.List;

/**
 * One page of a push's threads, as read.
 *
 * @param notes Its results, in the page's order.
 * @param completeness The run's {@code porelo/completeness}: {@code complete}, {@code partial},
 *     {@code failed} or {@code unknown}.
 */
public record Page(List<Note> notes, String completeness) {

    /**
     * Copies the list.
     *
     * @param notes The notes.
     * @param completeness The word.
     */
    public Page {
        notes = List.copyOf(notes);
    }
}
