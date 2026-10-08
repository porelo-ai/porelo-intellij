package org.fuin.sokar.intellij.machine;

/**
 * The machines cannot be read as the interface keeps them; the message says where and why.
 */
public final class MachinesException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates it.
     *
     * @param message Where and why.
     */
    public MachinesException(String message) {
        super(message);
    }

    /**
     * Creates it with its cause.
     *
     * @param message Where and why.
     * @param cause What failed.
     */
    public MachinesException(String message, Throwable cause) {
        super(message, cause);
    }

}
