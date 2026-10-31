package org.fuin.porelo.intellij.review;

import com.intellij.collaboration.ui.codereview.editor.CodeReviewCommentableEditorModel;
import com.intellij.collaboration.ui.codereview.editor.CodeReviewEditorGutterControlsModel;
import com.intellij.collaboration.ui.codereview.editor.CodeReviewEditorModel;
import com.intellij.collaboration.ui.codereview.editor.CodeReviewInlayModel;
import com.intellij.diff.util.LineRange;
import com.intellij.diff.util.Side;
import java.awt.EventQueue;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kotlin.Pair;
import kotlin.jvm.functions.Function1;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;
import org.jspecify.annotations.Nullable;

/**
 * The threads of one editor of a diff viewer, at that editor's lines: one side's editor in a
 * side-by-side viewer, the one editor of a unified viewer showing both.
 */
final class EditorModel
        implements CodeReviewEditorModel<EditorModel.Inlay>,
                CodeReviewCommentableEditorModel.WithMultilineComments {

    private final ReviewContext context;

    private final Threads threads;

    private final Function1<? super Pair<? extends Side, Integer>, @Nullable Integer> toEditor;

    private final Function1<? super Integer, ? extends @Nullable Pair<? extends Side, Integer>>
            fromEditor;

    private final MutableStateFlow<Collection<Inlay>> inlays =
            StateFlowKt.MutableStateFlow(List.of());

    private final MutableStateFlow<CodeReviewEditorGutterControlsModel.ControlsState> controls;

    /**
     * The inlay of each thread shown, kept while the thread stays as it is, so a thread the person
     * folded stays folded when another one comes or the lines move; the thread is the key, so a
     * changed one is drawn anew.
     */
    private Map<CommentThread, Inlay> shown = Map.of();

    EditorModel(
            ReviewContext context,
            Threads threads,
            Function1<? super Pair<? extends Side, Integer>, @Nullable Integer> toEditor,
            Function1<? super Integer, ? extends @Nullable Pair<? extends Side, Integer>>
                    fromEditor) {
        this.context = context;
        this.threads = threads;
        this.toEditor = toEditor;
        this.fromEditor = fromEditor;
        this.controls = StateFlowKt.MutableStateFlow(new Controls(Set.of()));
    }

    /**
     * Shows these threads, each after its last line; one this editor does not show is left out. On
     * the UI thread only, where the viewer's line mapping may be read.
     *
     * @param all Every thread of the file.
     */
    void show(List<CommentThread> all) {
        if (!EventQueue.isDispatchThread()) {
            throw new IllegalStateException(
                    "Threads are shown on the UI thread, not on "
                            + Thread.currentThread().getName());
        }
        final Map<CommentThread, Inlay> next = new LinkedHashMap<>();
        final Set<Integer> lines = new HashSet<>();
        for (final CommentThread thread : all) {
            final @Nullable Integer first =
                    toEditor.invoke(new Pair<>(thread.side(), thread.range().start));
            final @Nullable Integer last =
                    toEditor.invoke(new Pair<>(thread.side(), thread.range().end - 1));
            if (first == null || last == null) {
                continue;
            }
            final LineRange at = new LineRange(first, last + 1);
            final @Nullable Inlay before = shown.get(thread);
            if (before != null) {
                // The viewer maps lines anew when it diffs again, ignoring whitespace for one.
                before.moveTo(at);
                next.put(thread, before);
            } else {
                next.put(thread, new Inlay(thread, at));
            }
            lines.add(last);
        }
        shown = next;
        inlays.setValue(List.copyOf(next.values()));
        controls.setValue(new Controls(Set.copyOf(lines)));
    }

    @Override
    public StateFlow<Collection<Inlay>> getInlays() {
        return inlays;
    }

    @Override
    public StateFlow<CodeReviewEditorGutterControlsModel.ControlsState> getGutterControlsState() {
        return controls;
    }

    @Override
    public void toggleComments(int line) {
        for (final Inlay inlay : inlays.getValue()) {
            if (inlay.line.getValue() == line) {
                inlay.visible.setValue(!inlay.visible.getValue());
            }
        }
    }

    @Override
    public boolean canCreateComment(LineRange range) {
        return located(range) != null;
    }

    @Override
    public void requestNewComment(LineRange range) {
        final Lines.@Nullable Located at = located(range);
        if (at != null) {
            threads.requestNew(context, at.side(), at.range());
        }
    }

    @Override
    public void requestNewComment(int line) {
        requestNewComment(new LineRange(line, line + 1));
    }

    /**
     * Where editor lines lie in the diff, when every one of them lies on one side, each right after
     * the one before: a unified viewer interleaves the sides, and a range across a removed line
     * would otherwise take in lines the person did not mark.
     */
    private Lines.@Nullable Located located(LineRange range) {
        if (range.start < 0 || range.end <= range.start) {
            return null;
        }
        final @Nullable Pair<? extends Side, Integer> first = fromEditor.invoke(range.start);
        if (first == null) {
            return null;
        }
        for (int line = range.start + 1; line < range.end; line++) {
            final @Nullable Pair<? extends Side, Integer> at = fromEditor.invoke(line);
            if (at == null
                    || at.getFirst() != first.getFirst()
                    || at.getSecond() != first.getSecond() + (line - range.start)) {
                return null;
            }
        }
        return new Lines.Located(
                first.getFirst(),
                new LineRange(first.getSecond(), first.getSecond() + range.end - range.start));
    }

    /** Which lines carry threads, and that any line of the diff can take a new one. */
    private final class Controls implements CodeReviewEditorGutterControlsModel.ControlsState {

        private final Set<Integer> lines;

        Controls(Set<Integer> lines) {
            this.lines = lines;
        }

        @Override
        public Set<Integer> getLinesWithComments() {
            return lines;
        }

        @Override
        public boolean isLineCommentable(int line) {
            return fromEditor.invoke(line) != null;
        }
    }

    /** One thread, shown after the last of its lines in the editor. */
    static final class Inlay implements CodeReviewInlayModel.Ranged {

        private final CommentThread thread;

        private final MutableStateFlow<Integer> line;

        private final MutableStateFlow<Boolean> visible = StateFlowKt.MutableStateFlow(true);

        private final MutableStateFlow<LineRange> range;

        Inlay(CommentThread thread, LineRange range) {
            this.thread = thread;
            this.line = StateFlowKt.MutableStateFlow(range.end - 1);
            this.range = StateFlowKt.MutableStateFlow(range);
        }

        /** Shows it after the last of these lines of the editor from now on. */
        void moveTo(LineRange range) {
            this.range.setValue(range);
            this.line.setValue(range.end - 1);
        }

        /** The thread shown. */
        CommentThread thread() {
            return thread;
        }

        @Override
        public Object getKey() {
            return thread;
        }

        @Override
        public StateFlow<Integer> getLine() {
            return line;
        }

        @Override
        public StateFlow<Boolean> isVisible() {
            return visible;
        }

        @Override
        public StateFlow<LineRange> getRange() {
            return range;
        }
    }
}
