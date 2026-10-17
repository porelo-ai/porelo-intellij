package org.fuin.porelo.intellij.gate;

/** A gate call did not do what it was asked; the message says what the daemon answered. */
public class GateException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates it.
     *
     * @param message What the daemon answered.
     */
    public GateException(String message) {
        super(message);
    }

    /**
     * Creates it with its cause.
     *
     * @param message What the daemon answered.
     * @param cause What failed.
     */
    public GateException(String message, Throwable cause) {
        super(message, cause);
    }
}
