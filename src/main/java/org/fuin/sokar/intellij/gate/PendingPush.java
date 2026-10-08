package org.fuin.sokar.intellij.gate;

/**
 * A push waiting at a repository's gate for a decision.
 *
 * @param name Its ref name under {@code refs/sokar/incoming/}, which review, approve and reject take.
 * @param commit The full commit id it holds; approving passes back exactly this, so what goes is what was read.
 * @param subject The first line of its commit message.
 * @param waiting How long it has waited, in words.
 * @param at When it arrived, ISO-8601.
 * @param fetch The {@code git fetch} that brings it into a clone, over the person's ssh.
 */
public record PendingPush(String name, String commit, String subject, String waiting, String at, String fetch) {
}
