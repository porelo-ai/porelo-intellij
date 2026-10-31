package org.fuin.porelo.intellij.review;

import com.intellij.diff.util.LineRange;
import com.intellij.diff.util.Side;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Threads kept in memory, each file starting with one on its first pushed line. */
final class FakeThreads implements Threads {

    private final Map<ReviewContext, List<CommentThread>> threads = new ConcurrentHashMap<>();

    private final Map<ReviewContext, List<Consumer<List<CommentThread>>>> followers =
            new ConcurrentHashMap<>();

    private final AtomicInteger stoppedAgain = new AtomicInteger();

    @Override
    public Runnable follow(ReviewContext context, Consumer<List<CommentThread>> follower) {
        final List<Consumer<List<CommentThread>>> those =
                followers.computeIfAbsent(context, ignored -> new CopyOnWriteArrayList<>());
        those.add(follower);
        follower.accept(of(context));
        return () -> {
            if (!those.remove(follower)) {
                stoppedAgain.incrementAndGet();
            }
        };
    }

    @Override
    public void requestNew(ReviewContext context, Side side, LineRange range) {
        throw new UnsupportedOperationException("No thread is asked for here");
    }

    /**
     * Adds a thread to a file and tells those who follow it; on the UI thread, as a source does.
     */
    void add(ReviewContext context, CommentThread thread) {
        tell(
                context,
                threads.compute(
                        context,
                        (ignored, before) ->
                                Stream.concat(
                                                (before == null ? seed() : before).stream(),
                                                Stream.of(thread))
                                        .toList()));
    }

    /** Puts a thread in place of the one with its id and tells those who follow its file. */
    void replace(ReviewContext context, CommentThread thread) {
        tell(
                context,
                threads.compute(
                        context,
                        (ignored, before) ->
                                (before == null ? seed() : before)
                                        .stream()
                                                .map(t -> t.id().equals(thread.id()) ? thread : t)
                                                .toList()));
    }

    /** How many follow the threads of a file now. */
    int following(ReviewContext context) {
        return followers.getOrDefault(context, List.of()).size();
    }

    /** How often a follower was stopped that had stopped before: a real source may count them. */
    int stoppedAgain() {
        return stoppedAgain.get();
    }

    /** How many follow the threads of any file now. */
    int following() {
        return followers.values().stream().mapToInt(List::size).sum();
    }

    private void tell(ReviewContext context, List<CommentThread> now) {
        followers.getOrDefault(context, List.of()).forEach(follower -> follower.accept(now));
    }

    private List<CommentThread> of(ReviewContext context) {
        return threads.computeIfAbsent(context, ignored -> seed());
    }

    private static List<CommentThread> seed() {
        return List.of(
                new CommentThread("first", Side.RIGHT, new LineRange(0, 1), "On the first line"));
    }
}
