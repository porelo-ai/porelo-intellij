package org.fuin.porelo.intellij.gate;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * What a pending push changes, as the daemon ranks it.
 *
 * @param diff The unified diff, the far-reaching files first.
 * @param log The commit log over the same range.
 * @param files What it changes, in the order to read them.
 * @param asked What the task that pushed it was asked, {@code ""} when nothing, {@code null} when the task is gone.
 */
public record Review(String diff, String log, List<File> files, @Nullable String asked) {

    /**
     * Copies the list.
     *
     * @param diff The unified diff.
     * @param log The commit log.
     * @param files What it changes.
     * @param asked What the task was asked.
     */
    public Review {
        files = List.copyOf(files);
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
     */
    public record File(String path, String status, int added, int removed, String rank, String reason) {

        /**
         * Tells whether the file is of a kind whose change reaches far: a build or CI definition, a hook, a
         * dependency manifest, something that runs on checkout, a file made executable, a symbolic link.
         *
         * @return {@code true} for a {@code DANGEROUS} file.
         */
        public boolean farReaching() {
            return "DANGEROUS".equals(rank);
        }

    }

}
