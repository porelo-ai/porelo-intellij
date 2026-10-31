package org.fuin.porelo.intellij.review;

import com.intellij.collaboration.ui.codereview.diff.viewer.DiffViewerUtilKt;
import com.intellij.collaboration.ui.codereview.editor.CodeReviewComponentInlayRenderer;
import com.intellij.diff.DiffContext;
import com.intellij.diff.DiffExtension;
import com.intellij.diff.FrameDiffTool;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.diff.tools.util.base.DiffViewerBase;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import java.util.concurrent.atomic.AtomicBoolean;
import kotlin.Unit;
import kotlinx.coroutines.Job;
import kotlinx.coroutines.JobKt;
import org.jspecify.annotations.Nullable;

/**
 * Lets comment threads into a diff viewer only when its request carries a {@link ReviewContext} and
 * its {@link Threads}: every other diff in the IDE is left as it is.
 */
public final class ReviewDiffExtension extends DiffExtension {

    @Override
    public void onViewerCreated(
            FrameDiffTool.DiffViewer viewer, DiffContext context, DiffRequest request) {
        final @Nullable ReviewContext review = request.getUserData(ReviewContext.KEY);
        final @Nullable Threads threads = request.getUserData(Threads.KEY);
        if (review != null && threads != null && viewer instanceof DiffViewerBase base) {
            attach(base, review, threads);
        }
    }

    /**
     * Shows a file's threads in a viewer, and offers to start one on any of its lines, until the
     * viewer is disposed.
     */
    private static Job attach(DiffViewerBase viewer, ReviewContext review, Threads threads) {
        return Coroutines.runUntilDisposed(
                viewer,
                (scope, resume) ->
                        DiffViewerUtilKt.showCodeReview(
                                viewer,
                                (editorScope, editor, side, toEditor, fromEditor, toUnified) -> {
                                    final EditorModel model =
                                            new EditorModel(review, threads, toEditor, fromEditor);
                                    final Disposable stop =
                                            once(threads.follow(review, model::show));
                                    // Stopped as the viewer is disposed, before its editors are
                                    // gone; the scope itself ends later, on the UI thread.
                                    if (!Disposer.tryRegister(viewer, stop)) {
                                        Disposer.dispose(stop);
                                    }
                                    JobKt.getJob(editorScope.getCoroutineContext())
                                            .invokeOnCompletion(
                                                    ignored -> {
                                                        Disposer.dispose(stop);
                                                        return Unit.INSTANCE;
                                                    });
                                    return model;
                                },
                                (inlayScope, inlay) ->
                                        new CodeReviewComponentInlayRenderer(
                                                ThreadView.of(inlay.thread())),
                                resume));
    }

    /** Runs a follower's stop once, however often it is disposed. */
    private static Disposable once(Runnable stop) {
        final AtomicBoolean stopped = new AtomicBoolean();
        return () -> {
            if (stopped.compareAndSet(false, true)) {
                stop.run();
            }
        };
    }
}
