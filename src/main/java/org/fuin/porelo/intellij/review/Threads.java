package org.fuin.porelo.intellij.review;

import com.intellij.diff.util.LineRange;
import com.intellij.diff.util.Side;
import com.intellij.openapi.util.Key;
import java.util.List;
import java.util.function.Consumer;

/**
 * Where the threads of a review's files come from and where a new one is asked for; set on a diff
 * request beside its {@link ReviewContext}, and without it a diff shows no thread.
 */
public interface Threads {

    /** Where a review's diff request carries its threads. */
    Key<Threads> KEY = Key.create("porelo.review.threads");

    /**
     * Follows the threads of one file of a review.
     *
     * @param context The file.
     * @param threads Told every list of the file's threads, at once and on every change, always on
     *     the UI thread.
     * @return Stops following when run.
     */
    Runnable follow(ReviewContext context, Consumer<List<CommentThread>> threads);

    /**
     * Asks for a new thread on lines of one side, as the person marked them in the gutter.
     *
     * @param context The file.
     * @param side The side.
     * @param range The lines, from 0, the end excluded; not empty.
     */
    void requestNew(ReviewContext context, Side side, LineRange range);
}
