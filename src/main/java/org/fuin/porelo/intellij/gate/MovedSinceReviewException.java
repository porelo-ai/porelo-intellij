package org.fuin.porelo.intellij.gate;

/**
 * A call was refused because the push moved after it was shown: nothing was forwarded or stored,
 * and what it holds now has to be read before it can be approved or commented on.
 */
public final class MovedSinceReviewException extends GateException {

    private static final long serialVersionUID = 1L;

    private final String reviewed;

    private final String now;

    /**
     * Creates it.
     *
     * @param name The push.
     * @param reviewed The commit that was shown.
     * @param now The commit the push holds now.
     */
    public MovedSinceReviewException(String name, String reviewed, String now) {
        this(name, reviewed, now, "nothing was forwarded");
    }

    /**
     * Creates it, saying what did not happen.
     *
     * @param name The push.
     * @param reviewed The commit that was shown.
     * @param now The commit the push holds now.
     * @param undone What did not happen, such as {@code "nothing was stored"}.
     */
    public MovedSinceReviewException(String name, String reviewed, String now, String undone) {
        super(name + " moved from " + reviewed + " to " + now + " after it was shown; " + undone);
        this.reviewed = reviewed;
        this.now = now;
    }

    /**
     * Returns the commit that was shown.
     *
     * @return Its full id.
     */
    public String reviewed() {
        return reviewed;
    }

    /**
     * Returns the commit the push holds now.
     *
     * @return Its full id.
     */
    public String now() {
        return now;
    }
}
