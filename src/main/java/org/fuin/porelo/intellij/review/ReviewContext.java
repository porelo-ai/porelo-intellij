package org.fuin.porelo.intellij.review;

import com.intellij.openapi.util.Key;

/**
 * Which file of which review a diff shows; set on every diff request a review makes, and the only
 * thing that lets comment threads into a diff viewer.
 *
 * @param project The daemon's project.
 * @param repository The repository in the project.
 * @param base The commit the push is compared with.
 * @param head The pushed commit shown.
 * @param path The file's path in the repository.
 */
public record ReviewContext(
        String project, String repository, String base, String head, String path) {

    /** Where a review's diff request carries its context. */
    public static final Key<ReviewContext> KEY = Key.create("porelo.review.context");
}
