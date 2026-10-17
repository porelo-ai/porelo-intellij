package org.fuin.porelo.intellij.gate;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Approving was refused because the branch already holds a commit the reviewed work did not grow
 * from: nothing was forwarded, and the existing branch is never forced.
 */
public final class BranchExistsException extends GateException {

    private static final long serialVersionUID = 1L;

    private static final Pattern NUMBERED = Pattern.compile("(.*)-([0-9]+)");

    private final String branch;

    private final String at;

    /**
     * Creates it.
     *
     * @param branch The branch that is taken.
     * @param at The commit it holds.
     */
    public BranchExistsException(String branch, String at) {
        super(branch + " already holds " + at + ", from earlier work. Nothing was forwarded.");
        this.branch = branch;
        this.at = at;
    }

    /**
     * Returns the branch that is taken.
     *
     * @return Its name.
     */
    public String branch() {
        return branch;
    }

    /**
     * Returns the commit the branch holds.
     *
     * @return Its id, as the daemon names it.
     */
    public String at() {
        return at;
    }

    /**
     * Returns the name to offer instead: {@code <branch>-2}, or the next number where the branch
     * already ends in one, so a second refusal offers {@code -3}.
     *
     * @return The name.
     */
    public String next() {
        final Matcher numbered = NUMBERED.matcher(branch);
        if (numbered.matches()) {
            return numbered.group(1) + "-" + (Long.parseLong(numbered.group(2)) + 1);
        }
        return branch + "-2";
    }
}
