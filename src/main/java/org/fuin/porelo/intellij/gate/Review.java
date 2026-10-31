package org.fuin.porelo.intellij.gate;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What a pending push changes, as the daemon ranks it.
 *
 * @param diff The unified diff, the far-reaching files first.
 * @param log The commit log over the same range.
 * @param files What it changes, in the order to read them.
 * @param asked What the task that pushed it was asked, {@code ""} when nothing, {@code null} when
 *     the task is gone.
 * @param base The full commit it was diffed against; absent from a daemon that does not name it.
 */
public record Review(
        String diff, String log, List<File> files, @Nullable String asked, @Nullable String base) {

    /**
     * Copies the list.
     *
     * @param diff The unified diff.
     * @param log The commit log.
     * @param files What it changes.
     * @param asked What the task was asked.
     * @param base The commit it was diffed against.
     */
    public Review {
        files = List.copyOf(files);
    }

    /**
     * Creates one as a daemon answers it that does not name its base.
     *
     * @param diff The unified diff.
     * @param log The commit log.
     * @param files What it changes.
     * @param asked What the task was asked.
     */
    public Review(String diff, String log, List<File> files, @Nullable String asked) {
        this(diff, log, files, asked, null);
    }

    /**
     * One file a pending push changes.
     *
     * @param path Its path after the change.
     * @param status {@code A}, {@code M}, {@code D} or {@code T}, as git reports it.
     * @param added Lines added, {@code -1} for a binary file.
     * @param removed Lines removed, {@code -1} for a binary file.
     * @param rank {@code DANGEROUS}, {@code ORDINARY}, {@code GENERATED} or {@code REFORMATTING}.
     * @param reason Why it has that rank, {@code ""} for an ordinary file.
     * @param riskClass {@code C0} to {@code C3}; absent from a daemon that does not name it, and
     *     then shown as unknown, never as {@code C0}.
     */
    public record File(
            String path,
            String status,
            int added,
            int removed,
            String rank,
            String reason,
            @Nullable String riskClass) {

        /**
         * Creates one as a daemon answers it that names no class.
         *
         * @param path Its path.
         * @param status Its status.
         * @param added Lines added.
         * @param removed Lines removed.
         * @param rank Its rank.
         * @param reason Why it has that rank.
         */
        public File(
                String path, String status, int added, int removed, String rank, String reason) {
            this(path, status, added, removed, rank, reason, null);
        }

        /**
         * Tells whether the file is of a kind whose change reaches far: a build or CI definition, a
         * hook, a dependency manifest, something that runs on checkout, a file made executable, a
         * symbolic link.
         *
         * @return {@code true} for a {@code DANGEROUS} file.
         */
        public boolean farReaching() {
            return "DANGEROUS".equals(rank);
        }
    }
}
