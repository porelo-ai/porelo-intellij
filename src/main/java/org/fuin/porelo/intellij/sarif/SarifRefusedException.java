package org.fuin.porelo.intellij.sarif;

/** A document is not one of the profile's, and nothing of it was read. */
public class SarifRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String rule;

    private final String path;

    /**
     * Creates it.
     *
     * @param rule The profile's rule it breaks.
     * @param path Where in the document, as a JSON pointer; {@code ""} for the whole of it.
     * @param message What is wrong, in words.
     */
    public SarifRefusedException(String rule, String path, String message) {
        super(message);
        this.rule = rule;
        this.path = path;
    }

    /**
     * Returns the rule.
     *
     * @return The profile's rule it breaks.
     */
    public String rule() {
        return rule;
    }

    /**
     * Returns where.
     *
     * @return A JSON pointer into the document.
     */
    public String path() {
        return path;
    }
}
