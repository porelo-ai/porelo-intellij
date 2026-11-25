package org.fuin.porelo.intellij.gate;

import java.util.List;

/**
 * What a machine's daemon answers of its held items: every item it could read, and the tasks whose
 * holds it could not read, which are said, never shown as holding nothing.
 *
 * @param items The undecided items, in the daemon's order.
 * @param unreadable The tasks whose holds cannot be read, each named once.
 */
public record HeldList(List<HeldItem> items, List<String> unreadable) {

    /**
     * Copies the lists.
     *
     * @param items The items.
     * @param unreadable The tasks that cannot be read.
     */
    public HeldList {
        items = List.copyOf(items);
        unreadable = List.copyOf(unreadable);
    }
}
