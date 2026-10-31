package org.fuin.porelo.intellij.gate;

import java.util.List;

/**
 * The checks and comments on a waiting push, counted by the daemon; a client counts nothing down
 * itself.
 *
 * @param round The push's review round, 1 for its first push.
 * @param openFindings Open findings over every run of this round.
 * @param openThreads Open threads over every run of this round.
 * @param heldInbound Forge comments held for the push's pull request.
 * @param waitingOutbound Outgoing forge comments waiting.
 * @param runs Each producer's run of this round.
 */
public record ReviewSummary(
        int round,
        int openFindings,
        int openThreads,
        int heldInbound,
        int waitingOutbound,
        List<Run> runs) {

    /**
     * Copies the list.
     *
     * @param round The round.
     * @param openFindings Open findings.
     * @param openThreads Open threads.
     * @param heldInbound Held forge comments.
     * @param waitingOutbound Outgoing forge comments.
     * @param runs The runs.
     */
    public ReviewSummary {
        runs = List.copyOf(runs);
    }

    /**
     * One producer's run.
     *
     * @param run Its id, which {@link Gate#comments} takes.
     * @param name The producer as a person reads it.
     * @param kind {@code person}, {@code task}, {@code forge} or {@code scanner}.
     * @param state {@code complete}, {@code partial}, {@code failed}, {@code unknown}, {@code
     *     running} or {@code not-run}, or a word of a newer daemon, shown as it is.
     * @param findings Its findings.
     * @param openFindings How many of them are open.
     */
    public record Run(
            String run, String name, String kind, String state, int findings, int openFindings) {

        /**
         * Tells whether the run may read clean: complete, and nothing found.
         *
         * @return {@code true} only for a complete run without findings.
         */
        public boolean clean() {
            return "complete".equals(state) && findings == 0;
        }
    }
}
