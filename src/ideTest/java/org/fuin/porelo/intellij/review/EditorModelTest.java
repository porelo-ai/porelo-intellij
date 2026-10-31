package org.fuin.porelo.intellij.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.intellij.diff.util.LineRange;
import com.intellij.diff.util.Side;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import kotlin.Pair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A comment asked for on marked lines of a unified viewer, which interleaves both sides, would
 * otherwise take in a removed line the person did not mark, or lines of the other side.
 */
class EditorModelTest {

    /** "one\ntwo\nthree\n" against "one\n2\nthree\nfour\n" in a unified viewer, line by line. */
    private static final Map<Integer, Pair<Side, Integer>> UNIFIED =
            Map.of(
                    0, new Pair<>(Side.RIGHT, 0),
                    1, new Pair<>(Side.LEFT, 1),
                    2, new Pair<>(Side.RIGHT, 1),
                    3, new Pair<>(Side.RIGHT, 2),
                    4, new Pair<>(Side.RIGHT, 3));

    private static final Threads NO_THREADS =
            new Threads() {
                @Override
                public Runnable follow(
                        ReviewContext context, Consumer<List<CommentThread>> follower) {
                    return () -> {};
                }

                @Override
                public void requestNew(ReviewContext context, Side side, LineRange range) {
                    throw new UnsupportedOperationException("No thread is asked for here");
                }
            };

    private static boolean visibleOf(EditorModel model, CommentThread thread) {
        return inlayOf(model, thread).isVisible().getValue();
    }

    private static EditorModel.Inlay inlayOf(EditorModel model, CommentThread thread) {
        return model.getInlays().getValue().stream()
                .filter(inlay -> inlay.thread().equals(thread))
                .findFirst()
                .orElseThrow();
    }

    /** The same diff side by side, its pushed side's unchanged three folded away. */
    private static final Map<Integer, Pair<Side, Integer>> COLLAPSED =
            Map.of(0, new Pair<>(Side.RIGHT, 1), 1, new Pair<>(Side.RIGHT, 3));

    @Test
    @DisplayName(
            "A thread folded stays folded when another thread comes or its lines move, follows them, and unfolds when it changes")
    void aFoldedThreadStaysFolded() throws Exception {
        final CommentThread seed =
                new CommentThread("first", Side.RIGHT, new LineRange(0, 1), "On one");
        final CommentThread other =
                new CommentThread("other", Side.RIGHT, new LineRange(2, 3), "On three");
        final CommentThread changed =
                new CommentThread("first", Side.RIGHT, new LineRange(0, 1), "Changed");
        // How far down the viewer shows the side's lines, as after it diffed again.
        final AtomicInteger moved = new AtomicInteger();
        final EditorModel model =
                new EditorModel(
                        new ReviewContext("p", "r", "1", "2", "A.txt"),
                        NO_THREADS,
                        at -> at.getSecond() + moved.get(),
                        line -> new Pair<>(Side.RIGHT, line - moved.get()));
        final List<Object> seen = new ArrayList<>();

        SwingUtilities.invokeAndWait(
                () -> {
                    model.show(List.of(seed));
                    model.toggleComments(0);
                    model.show(List.of(seed, other));
                    seen.add(visibleOf(model, seed));
                    moved.set(2);
                    model.show(List.of(seed, other));
                    seen.add(visibleOf(model, seed));
                    seen.add(inlayOf(model, seed).getLine().getValue());
                    seen.add(model.getGutterControlsState().getValue().getLinesWithComments());
                    model.toggleComments(2);
                    seen.add(visibleOf(model, seed));
                    model.toggleComments(2);
                    model.show(List.of(changed, other));
                    seen.add(visibleOf(model, changed));
                });

        assertThat(seen)
                .as(
                        "the folded seed once another came; once its lines moved down by two, its"
                                + " line and the lines with threads; the seed unfolded at its new"
                                + " line; the seed changed")
                .containsExactly(false, false, 2, Set.of(2, 4), true, true);
    }

    @Test
    @DisplayName(
            "Lines marked in a unified viewer take a comment only when they follow each other on one side")
    void aCommentTakesOnlyLinesThatFollowOnOneSide() {
        final List<String> asked = new ArrayList<>();
        final EditorModel model =
                new EditorModel(
                        new ReviewContext("p", "r", "1", "2", "A.txt"),
                        new Threads() {
                            @Override
                            public Runnable follow(
                                    ReviewContext context, Consumer<List<CommentThread>> follower) {
                                return () -> {};
                            }

                            @Override
                            public void requestNew(
                                    ReviewContext context, Side side, LineRange range) {
                                asked.add(side + " " + range.start + "-" + range.end);
                            }
                        },
                        at -> null,
                        UNIFIED::get);

        assertThat(model.canCreateComment(new LineRange(0, 3)))
                .as("one, the removed two, and 2")
                .isFalse();
        assertThat(model.canCreateComment(new LineRange(0, 1))).as("one alone").isTrue();
        assertThat(model.canCreateComment(new LineRange(3, 6))).as("lines past the end").isFalse();
        assertThat(model.canCreateComment(new LineRange(2, 2))).as("no line").isFalse();
        assertThat(model.canCreateComment(new LineRange(-1, 1)))
                .as("a line before the first")
                .isFalse();
        assertThat(
                        new EditorModel(
                                        new ReviewContext("p", "r", "1", "2", "A.txt"),
                                        NO_THREADS,
                                        at -> null,
                                        COLLAPSED::get)
                                .canCreateComment(new LineRange(0, 2)))
                .as("2, and four past the unchanged lines folded between them")
                .isFalse();

        model.requestNewComment(new LineRange(0, 3));
        model.requestNewComment(new LineRange(2, 5));
        model.requestNewComment(1);
        assertThat(asked)
                .as("the comments asked for, at the lines of their side")
                .containsExactly("RIGHT 1-4", "LEFT 1-2");
    }
}
