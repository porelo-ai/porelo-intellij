package org.fuin.porelo.intellij.gate;

/**
 * The daemon refused a decision on a held item for a reason it named; nothing was decided, and the
 * message says why in words.
 */
public final class ItemRefusedException extends GateException {

    private static final long serialVersionUID = 1L;

    private final String reason;

    private final String id;

    /**
     * Creates it.
     *
     * @param reason The daemon's error, without its interface, such as {@code DigestChanged}.
     * @param id The item's id, as the error named it.
     * @param message Why, in words.
     */
    public ItemRefusedException(String reason, String id, String message) {
        super(message);
        this.reason = reason;
        this.id = id;
    }

    /**
     * Returns the daemon's error.
     *
     * @return Its name, without the interface.
     */
    public String reason() {
        return reason;
    }

    /**
     * Returns the item the error named.
     *
     * @return Its id.
     */
    public String id() {
        return id;
    }
}
