package org.fuin.porelo.intellij.gate;

import org.jspecify.annotations.Nullable;

/**
 * A push waiting at a repository's gate for a decision.
 *
 * <p>Its level, class, approvals and checks are absent from a daemon that does not name them, and
 * are then shown as unknown, never guessed.
 *
 * @param name Its ref name under {@code refs/porelo/incoming/}, which review, approve and reject
 *     take.
 * @param commit The full commit id it holds; approving passes back exactly this, so what goes is
 *     what was read.
 * @param subject The first line of its commit message.
 * @param waiting How long it has waited, in words.
 * @param at When it arrived, ISO-8601.
 * @param fetch The {@code git fetch} that brings it into a clone, over the person's ssh.
 * @param sourceLevel {@code E0}, {@code E1}, {@code I1}, {@code A} or {@code unknown}, as the
 *     daemon stamped it.
 * @param sourceWords Who it is from, in words; {@code ""} when there is nothing to say.
 * @param riskClass {@code C0} to {@code C3}, the highest class among the files it changes.
 * @param approvalsNeeded How many people must approve it: 1, or 2.
 * @param task The container name of the task that pushed it; absent when it is gone.
 * @param review Its checks and comments.
 */
public record PendingPush(
        String name,
        String commit,
        String subject,
        String waiting,
        String at,
        String fetch,
        @Nullable String sourceLevel,
        @Nullable String sourceWords,
        @Nullable String riskClass,
        @Nullable Integer approvalsNeeded,
        @Nullable String task,
        @Nullable ReviewSummary review) {

    /**
     * Creates one as a daemon names it that says nothing of level, class or checks.
     *
     * @param name Its ref name.
     * @param commit The full commit id.
     * @param subject The first line of its commit message.
     * @param waiting How long it has waited.
     * @param at When it arrived.
     * @param fetch The fetch that brings it.
     */
    public PendingPush(
            String name, String commit, String subject, String waiting, String at, String fetch) {
        this(name, commit, subject, waiting, at, fetch, null, null, null, null, null, null);
    }
}
