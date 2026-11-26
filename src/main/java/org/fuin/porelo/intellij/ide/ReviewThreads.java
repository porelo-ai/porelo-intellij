package org.fuin.porelo.intellij.ide;

import com.intellij.diff.util.LineRange;
import com.intellij.diff.util.Side;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.fuin.porelo.intellij.review.CommentThread;
import org.fuin.porelo.intellij.review.ReviewContext;
import org.fuin.porelo.intellij.review.Threads;
import org.fuin.porelo.intellij.sarif.Note;

/**
 * The threads a review read, each shown at its lines in the diff of its own file; it only shows
 * them, so no gutter control offers a new one.
 */
final class ReviewThreads implements Threads {

    private final Map<String, List<CommentThread>> byPath;

    /**
     * Takes the threads placed at their lines.
     *
     * @param placed The review's threads.
     */
    ReviewThreads(ReviewTab.Placed placed) {
        final Map<String, List<CommentThread>> shown = new LinkedHashMap<>();
        placed.lines()
                .forEach(
                        (path, discussions) -> {
                            final List<CommentThread> threads = new ArrayList<>();
                            for (final ReviewTab.Discussion discussion : discussions) {
                                threads.add(thread(discussion));
                            }
                            shown.put(path, List.copyOf(threads));
                        });
        this.byPath = java.util.Collections.unmodifiableMap(shown);
    }

    @Override
    public Runnable follow(ReviewContext context, Consumer<List<CommentThread>> threads) {
        // Read once, with the review: what is shown changes only when the push is read again.
        threads.accept(byPath.getOrDefault(context.path(), List.of()));
        return () -> {};
    }

    @Override
    public boolean canStart() {
        return false;
    }

    @Override
    public void requestNew(ReviewContext context, Side side, LineRange range) {
        throw new UnsupportedOperationException("A review only shows the threads it read");
    }

    /**
     * Returns what is said of a note's writer and state, in the plugin's own words.
     *
     * @param note The note.
     * @return Who wrote it, and {@code resolved} when its thread is.
     */
    static String who(Note note) {
        final String author = note.origin().author();
        final String who = author == null ? "unnamed writer" : author;
        return "resolved".equals(note.thread().state()) ? who + " · resolved" : who;
    }

    /**
     * Returns the notes of a thread as they are shown.
     *
     * @param notes The thread's notes, its first one first.
     * @return Each with its writer.
     */
    static List<CommentThread.Said> said(List<Note> notes) {
        return notes.stream().map(note -> new CommentThread.Said(who(note), note.text())).toList();
    }

    private static CommentThread thread(ReviewTab.Discussion discussion) {
        final var at = java.util.Objects.requireNonNull(discussion.at(), "a placed thread");
        return new CommentThread(discussion.id(), at.side(), at.range(), said(discussion.notes()));
    }
}
