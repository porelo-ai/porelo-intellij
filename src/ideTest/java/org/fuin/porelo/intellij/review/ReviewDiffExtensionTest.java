package org.fuin.porelo.intellij.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.intellij.collaboration.ui.codereview.editor.CodeReviewComponentInlayRenderer;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.DiffRequestPanel;
import com.intellij.diff.DiffTool;
import com.intellij.diff.impl.DiffSettingsHolder;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.tools.fragmented.UnifiedDiffTool;
import com.intellij.diff.tools.simple.SimpleDiffTool;
import com.intellij.diff.util.LineRange;
import com.intellij.diff.util.Side;
import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.CheckedDisposable;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.junit5.TestApplication;
import com.intellij.testFramework.junit5.fixture.FixturesKt;
import com.intellij.testFramework.junit5.fixture.TestFixture;
import com.intellij.util.ui.UIUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.swing.JComponent;
import javax.swing.text.JTextComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Threads shown in a diff that is not a review's would put Porelo's comments on another tool's
 * diff; threads left behind by a closed review would leak its editors and keep following.
 */
@TestApplication
class ReviewDiffExtensionTest {

    /** A diff viewer reads the project's file statuses, which the default project has none of. */
    private static final TestFixture<Project> PROJECT =
            FixturesKt.projectFixture(FixturesKt.tempPathFixture(), OpenProjectTask.build(), false);

    private static final String BASE = "one\ntwo\nthree\n";

    private static final String PUSHED = "one\n2\nthree\nfour\n";

    /** The pushed side's line 4, "four", which only the pushed side has. */
    private static final CommentThread ON_FOUR =
            new CommentThread("four", Side.RIGHT, new LineRange(3, 4), "On four");

    @Test
    @DisplayName(
            "A review's diff shows its threads at their lines side by side and unified, another diff shows none, and switching or closing takes them away")
    void threadsShowOnlyInAReviewsDiffAndGoWithIt() {
        EdtTestUtil.runInEdtAndWait(
                () -> {
                    final Project project = PROJECT.get();
                    final int editorsBefore = EditorFactory.getInstance().getAllEditors().length;
                    final CheckedDisposable review = Disposer.newCheckedDisposable("review");
                    try {
                        final DiffRequestPanel panel =
                                panel(project, review, SimpleDiffTool.INSTANCE);
                        final DiffRequestPanel unifiedPanel =
                                panel(project, review, UnifiedDiffTool.INSTANCE);
                        final FakeThreads threads = new FakeThreads();

                        final ReviewContext sideBySide = context("A.txt");
                        threads.add(sideBySide, ON_FOUR);
                        panel.setRequest(request(sideBySide, threads));
                        waitFor(
                                "both editors of the side-by-side viewer follow",
                                () -> threads.following(sideBySide) == 2);
                        waitFor("two threads shown", () -> shown().size() == 2);
                        assertThat(shown())
                                .as("the seed on line 1 and the thread on four, side by side")
                                .containsExactlyInAnyOrder("one", "four");

                        final ReviewContext switched = context("C.txt");
                        panel.setRequest(request(switched, threads));
                        waitFor(
                                "the next file's editors follow",
                                () -> threads.following(switched) == 2);
                        assertThat(threads.following(sideBySide))
                                .as(
                                        "the first file's followers, once the review switched to the next")
                                .isZero();
                        waitFor("one thread shown", () -> shown().size() == 1);
                        assertThat(shown()).as("the next file's own thread").containsExactly("one");

                        final ReviewContext unified = context("B.txt");
                        threads.add(unified, ON_FOUR);
                        unifiedPanel.setRequest(request(unified, threads));
                        panel.setRequest(null);
                        waitFor(
                                "the unified viewer's editor follows",
                                () -> threads.following(unified) == 1);
                        assertThat(threads.following(switched))
                                .as(
                                        "the side-by-side viewer's followers, once its panel shows nothing")
                                .isZero();
                        waitFor("two threads shown", () -> shown().size() == 2);
                        assertThat(shown())
                                .as(
                                        "the same threads in the unified viewer, after the pushed lines")
                                .containsExactlyInAnyOrder("one", "four");

                        final SimpleDiffRequest other =
                                new SimpleDiffRequest(
                                        "not a review",
                                        DiffContentFactory.getInstance().create(BASE),
                                        DiffContentFactory.getInstance().create(PUSHED),
                                        "left",
                                        "right");
                        unifiedPanel.setRequest(other);
                        waitFor("the unified viewer let go", () -> threads.following(unified) == 0);
                        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
                        assertThat(shown())
                                .as("threads in a diff that is not a review's")
                                .isEmpty();
                        unifiedPanel.setRequest(withoutThreads(context("D.txt")));
                        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
                        assertThat(threads.following())
                                .as("followers of a review without threads")
                                .isZero();
                        assertThat(shown())
                                .as("threads in a review without a source of them")
                                .isEmpty();

                        panel.setRequest(request(sideBySide, threads));
                        waitFor("the review shown again", () -> threads.following(sideBySide) == 2);
                        Disposer.dispose(review);
                        // Before a thread can reach a disposed viewer; its coroutines end later.
                        assertThat(threads.following())
                                .as("followers once the review is closed")
                                .isZero();
                        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
                        assertThat(threads.stoppedAgain())
                                .as("followers stopped twice, once the viewer's coroutines ended")
                                .isZero();
                        assertThat(EditorFactory.getInstance().getAllEditors())
                                .as("editors left once the review is closed")
                                .hasSize(editorsBefore);
                    } finally {
                        if (!review.isDisposed()) {
                            Disposer.dispose(review);
                        }
                    }
                });
    }

    @Test
    @DisplayName(
            "A thread that comes or changes while its file is shown is drawn as it is now, on whichever side, and the view of every other thread is kept")
    void threadsFollowTheirSource() {
        EdtTestUtil.runInEdtAndWait(
                () -> {
                    final Disposable review = Disposer.newDisposable("review");
                    final FakeThreads threads = new FakeThreads();
                    final ReviewContext file = context("E.txt");
                    try {
                        final DiffRequestPanel panel =
                                panel(PROJECT.get(), review, SimpleDiffTool.INSTANCE);
                        panel.setRequest(request(file, threads));
                        waitFor("the seed shown", () -> shown().size() == 1);
                        final Inlay<?> seed = threadInlays().get(0);

                        threads.add(file, ON_TWO_AND_THREE);
                        waitFor("two threads shown", () -> shown().size() == 2);
                        assertThat(shownIn(BASE))
                                .as("the thread on the base's two and three, after three")
                                .containsExactly("three");
                        assertThat(threadInlays())
                                .as("the seed's view, once another thread came")
                                .contains(seed);

                        threads.replace(
                                file,
                                new CommentThread(
                                        "first", Side.RIGHT, new LineRange(0, 1), "Changed"));
                        waitFor("the changed seed drawn", () -> texts().contains("Changed"));
                        assertThat(texts())
                                .as("the threads drawn, once the seed changed")
                                .containsExactlyInAnyOrder("Changed", "On two and three");
                    } finally {
                        Disposer.dispose(review);
                    }
                    assertThat(threads.following())
                            .as("followers once the review is closed")
                            .isZero();
                    PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
                    assertThat(threads.stoppedAgain())
                            .as("followers stopped twice, once the viewer's coroutines ended")
                            .isZero();
                });
    }

    /** The base's lines 2 and 3, "two" and "three", of which the pushed side changed the first. */
    private static final CommentThread ON_TWO_AND_THREE =
            new CommentThread("base", Side.LEFT, new LineRange(1, 3), "On two and three");

    private static ReviewContext context(String path) {
        return new ReviewContext(
                "p",
                "r",
                "1111111111111111111111111111111111111111",
                "2222222222222222222222222222222222222222",
                path);
    }

    /**
     * A diff panel that shows every request in the one viewer named: the panel reads the order of
     * the viewers from the settings once, when it is made, and only that.
     */
    private static DiffRequestPanel panel(Project project, Disposable parent, DiffTool tool) {
        final DiffSettingsHolder.DiffSettings settings =
                DiffSettingsHolder.DiffSettings.getSettings();
        final List<String> before = settings.getDiffToolsOrder();
        settings.setDiffToolsOrder(List.of(tool.getClass().getCanonicalName()));
        try {
            return DiffManager.getInstance().createRequestPanel(project, parent, null);
        } finally {
            settings.setDiffToolsOrder(before);
        }
    }

    private static SimpleDiffRequest request(ReviewContext context, Threads threads) {
        final SimpleDiffRequest request = withoutThreads(context);
        request.putUserData(Threads.KEY, threads);
        return request;
    }

    /** A review's request as the review tab makes it before it has a source of threads. */
    private static SimpleDiffRequest withoutThreads(ReviewContext context) {
        final SimpleDiffRequest request =
                new SimpleDiffRequest(
                        context.path(),
                        DiffContentFactory.getInstance().create(BASE),
                        DiffContentFactory.getInstance().create(PUSHED),
                        "base",
                        "pushed");
        request.putUserData(ReviewContext.KEY, context);
        return request;
    }

    /** The text of the line each thread is shown after, in every editor open. */
    private static List<String> shown() {
        final List<String> lines = new ArrayList<>();
        for (final Editor editor : EditorFactory.getInstance().getAllEditors()) {
            lines.addAll(shown(editor));
        }
        return lines;
    }

    /** The text of the line each thread is shown after, in the editors showing this text. */
    private static List<String> shownIn(String text) {
        final List<String> lines = new ArrayList<>();
        for (final Editor editor : EditorFactory.getInstance().getAllEditors()) {
            if (editor.getDocument().getText().equals(text)) {
                lines.addAll(shown(editor));
            }
        }
        return lines;
    }

    private static List<String> shown(Editor editor) {
        final List<String> lines = new ArrayList<>();
        for (final Inlay<?> inlay : threadInlays(editor)) {
            final int line = editor.getDocument().getLineNumber(inlay.getOffset());
            lines.add(
                    editor.getDocument()
                            .getText()
                            .substring(
                                    editor.getDocument().getLineStartOffset(line),
                                    editor.getDocument().getLineEndOffset(line)));
        }
        return lines;
    }

    /** The text each thread is drawn with, in every editor open, in whatever the IDE wraps it. */
    private static List<String> texts() {
        final List<String> texts = new ArrayList<>();
        for (final Inlay<?> inlay : threadInlays()) {
            final JComponent view =
                    ((CodeReviewComponentInlayRenderer) inlay.getRenderer()).getComponent();
            for (final JTextComponent text :
                    UIUtil.findComponentsOfType(view, JTextComponent.class)) {
                texts.add(text.getText());
            }
        }
        return texts;
    }

    private static List<Inlay<?>> threadInlays() {
        final List<Inlay<?>> inlays = new ArrayList<>();
        for (final Editor editor : EditorFactory.getInstance().getAllEditors()) {
            inlays.addAll(threadInlays(editor));
        }
        return inlays;
    }

    private static List<Inlay<?>> threadInlays(Editor editor) {
        final List<Inlay<?>> inlays = new ArrayList<>();
        final int length = editor.getDocument().getTextLength();
        for (final Inlay<?> inlay : editor.getInlayModel().getBlockElementsInRange(0, length)) {
            if (inlay.getRenderer() instanceof CodeReviewComponentInlayRenderer) {
                inlays.add(inlay);
            }
        }
        return inlays;
    }

    private static void waitFor(String what, BooleanSupplier condition) {
        PlatformTestUtil.waitWithEventsDispatching(what, condition, 20);
    }
}
