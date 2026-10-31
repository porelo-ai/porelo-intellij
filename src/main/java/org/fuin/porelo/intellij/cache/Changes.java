package org.fuin.porelo.intellij.cache;

import java.util.List;

/**
 * What a waiting push changes against the branch it started from, as fetched into the cache.
 *
 * @param branch The branch the push is read against: the gate mirror's default branch.
 * @param base The commit the diff starts from: where the branch and the push last met, or the
 *     branch's tip when they never met.
 * @param commit The push's commit.
 * @param files Every file that differs, as git names it.
 */
public record Changes(String branch, String base, String commit, List<Change> files) {

    /**
     * Copies the list.
     *
     * @param branch The branch.
     * @param base The base commit.
     * @param commit The push's commit.
     * @param files The files.
     */
    public Changes {
        files = List.copyOf(files);
    }

    /**
     * One file that differs.
     *
     * @param status {@code A}, {@code M}, {@code D} or {@code T}, as git reports it.
     * @param path Its path in the repository.
     * @param baseMode Its mode at the base, as git writes it: {@code 100644} for a regular file,
     *     {@code 000000} where the base has none.
     * @param pushedMode Its mode in the push, the same way.
     */
    public record Change(String status, String path, String baseMode, String pushedMode) {

        /**
         * Tells whether a side holds the file as a regular blob: not a symbolic link, a submodule
         * or nothing, which a comment's lines could not be placed on.
         *
         * @param pushed {@code true} for the push's side, {@code false} for the base's.
         * @return {@code true} for a regular file, executable or not.
         */
        public boolean regular(boolean pushed) {
            final String mode = pushed ? pushedMode : baseMode;
            return mode.equals("100644") || mode.equals("100755");
        }
    }
}
