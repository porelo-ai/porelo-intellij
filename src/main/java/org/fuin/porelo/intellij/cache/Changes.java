package org.fuin.porelo.intellij.cache;

import java.util.List;

/**
 * What a waiting push changes against the branch it started from, as fetched into the cache.
 *
 * @param branch The branch the push is read against: the gate mirror's default branch.
 * @param base The commit the diff starts from: where the branch and the push last met, or the branch's tip when
 *     they never met.
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
     */
    public record Change(String status, String path) {
    }

}
