package org.fuin.porelo.intellij.gate;

/**
 * The daemon refused a comment call for a reason it named; nothing was stored or changed, and the
 * message says why in words.
 */
public final class ReviewRefusedException extends GateException {

    private static final long serialVersionUID = 1L;

    private final String reason;

    /**
     * Creates it.
     *
     * @param reason The daemon's error, without its interface, such as {@code AnchorOutsideBlob}.
     * @param message Why, in words.
     */
    public ReviewRefusedException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    /**
     * Returns the daemon's error.
     *
     * @return Its name, without the interface.
     */
    public String reason() {
        return reason;
    }
}
