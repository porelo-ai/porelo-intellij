package org.fuin.sokar.intellij.cache;

/**
 * The cache could not do what it was asked; the message says what git said.
 */
public final class CacheException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates it.
     *
     * @param message What failed, and what git said.
     */
    public CacheException(String message) {
        super(message);
    }

    /**
     * Creates it with its cause.
     *
     * @param message What failed.
     * @param cause What failed underneath.
     */
    public CacheException(String message, Throwable cause) {
        super(message, cause);
    }

}
