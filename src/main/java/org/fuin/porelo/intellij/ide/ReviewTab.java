package org.fuin.porelo.intellij.ide;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.fuin.porelo.intellij.cache.Changes;
import org.fuin.porelo.intellij.gate.Gate;
import org.fuin.porelo.intellij.gate.Review;
import org.fuin.porelo.intellij.gate.Waiting;
import org.jspecify.annotations.Nullable;

/**
 * Opens a waiting push as an editor tab: the tree of the files it changes on the left, IntelliJ's
 * diff of the one selected on the right, against where it and its branch last met, with Approve and
 * Reject above.
 *
 * <p>The files are read from the plugin's own bare cache; nothing is checked out and nothing of the
 * person's is written to.
 */
final class ReviewTab {

    private ReviewTab() {}

    /**
     * Reads the push in the background and opens it in the project's editor.
     *
     * @param project The window to open it in.
     * @param entry The push.
     */
    static void open(Project project, Waiting.Entry entry) {
        // A review already open is shown again, and one being read is not read twice, as a
        // double-click would.
        final String key = key(entry);
        for (final var open : FileEditorManager.getInstance(project).getOpenFiles()) {
            if (open instanceof ReviewFile review && key(review.entry()).equals(key)) {
                FileEditorManager.getInstance(project).openFile(open, true);
                return;
            }
        }
        if (!READING.add(key)) {
            return;
        }
        ProgressManager.getInstance()
                .run(
                        new Task.Backgroundable(project, "Reading " + entry.push().name(), true) {
                            @Override
                            public void run(ProgressIndicator indicator) {
                                final Read read;
                                try {
                                    read = readCancellable(entry, indicator);
                                } catch (ProcessCanceledException ex) {
                                    READING.remove(key);
                                    throw ex;
                                } catch (RuntimeException ex) {
                                    READING.remove(key);
                                    NotificationGroupManager.getInstance()
                                            .getNotificationGroup(PoreloWatch.NOTIFICATIONS)
                                            .createNotification(
                                                    "Cannot read "
                                                            + entry.push().name()
                                                            + ": "
                                                            + ex.getMessage(),
                                                    NotificationType.ERROR)
                                            .notify(project);
                                    return;
                                }
                                READING.remove(key);
                                ApplicationManager.getApplication()
                                        .invokeLater(
                                                () ->
                                                        FileEditorManager.getInstance(project)
                                                                .openFile(
                                                                        new ReviewFile(entry, read),
                                                                        true),
                                                project.getDisposed());
                            }
                        });
    }

    /**
     * What a review shows, read off the UI thread.
     *
     * @param review The daemon's ranking and what the task was asked.
     * @param changes What the push changes, from the cache.
     * @param files Each file as both sides have it, in the daemon's order.
     */
    record Read(Review review, Changes changes, List<Side> files) {}

    /**
     * One file as both sides have it.
     *
     * @param change The file and how it changed.
     * @param ranked How the daemon ranks it, or {@code null} when it did not.
     * @param base The file at the base, or {@code null} when the base has none.
     * @param pushed The file in the push, or {@code null} when the push removes it.
     */
    record Side(
            Changes.Change change,
            Review.@Nullable File ranked,
            byte @Nullable [] base,
            byte @Nullable [] pushed) {

        /**
         * Tells whether the file reaches far.
         *
         * @return {@code true} for a file the daemon ranks dangerous.
         */
        boolean farReaching() {
            final Review.File file = ranked;
            return file != null && file.farReaching();
        }
    }

    private static final java.util.Set<String> READING =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static String key(Waiting.Entry entry) {
        return String.join(
                "\u0000",
                entry.machine().name(),
                entry.project(),
                entry.repository(),
                entry.push().name(),
                entry.push().commit());
    }

    // The read runs on a thread of its own, so Cancel interrupts it, which ends its git and ssh.
    private static Read readCancellable(Waiting.Entry entry, ProgressIndicator indicator) {
        final java.util.concurrent.FutureTask<Read> reading =
                new java.util.concurrent.FutureTask<>(() -> read(entry));
        Thread.ofVirtual().start(reading);
        while (true) {
            try {
                indicator.checkCanceled();
                return reading.get(100, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (ProcessCanceledException ex) {
                reading.cancel(true);
                throw ex;
            } catch (java.util.concurrent.TimeoutException ex) {
                // Still reading; Cancel is looked at again.
            } catch (InterruptedException ex) {
                reading.cancel(true);
                Thread.currentThread().interrupt();
                throw new ProcessCanceledException(ex);
            } catch (java.util.concurrent.ExecutionException ex) {
                if (ex.getCause() instanceof RuntimeException failed) {
                    throw failed;
                }
                throw new IllegalStateException(ex.getCause());
            }
        }
    }

    private static Read read(Waiting.Entry entry) {
        final Review review;
        try (Gate gate = PoreloWatch.get().gate(entry.machine())) {
            review = gate.review(entry.project(), entry.repository(), entry.push().name());
        }
        final PoreloWatch watch = PoreloWatch.get();
        final Changes changes = watch.fetch(entry);
        final List<Ranked> ordered = ordered(review, changes);
        final List<String> paths = ordered.stream().map(one -> one.change().path()).toList();
        // One git per side, not two for every file.
        final Map<String, byte @Nullable []> base = watch.files(entry, changes.base(), paths);
        final Map<String, byte @Nullable []> pushed = watch.files(entry, changes.commit(), paths);
        final List<Side> files = new ArrayList<>();
        for (final Ranked one : ordered) {
            files.add(
                    new Side(
                            one.change(),
                            one.ranked(),
                            base.get(one.change().path()),
                            pushed.get(one.change().path())));
        }
        return new Read(review, changes, files);
    }

    /**
     * A file the push changes, and how the daemon ranks it.
     *
     * @param change The file and how it changed.
     * @param ranked How the daemon ranks it, or {@code null} when it did not.
     */
    record Ranked(Changes.Change change, Review.@Nullable File ranked) {}

    /**
     * Returns the files a review shows, in the daemon's order.
     *
     * @param review The daemon's ranking.
     * @param changes What the cache says the push changes.
     * @return Each file with its ranking, far-reaching first.
     */
    static List<Ranked> ordered(Review review, Changes changes) {
        final Map<String, Review.File> ranked =
                review.files().stream()
                        .collect(
                                Collectors.toMap(
                                        Review.File::path,
                                        Function.identity(),
                                        (one, other) -> one));
        // The daemon ranked what it will forward; a file it ranks that the diff does not show would
        // be approved unread.
        final java.util.Set<String> shown =
                changes.files().stream().map(Changes.Change::path).collect(Collectors.toSet());
        for (final Review.File file : review.files()) {
            if (!shown.contains(file.path())) {
                throw new org.fuin.porelo.intellij.gate.GateException(
                        "The daemon ranks "
                                + file.path()
                                + ", which the push does not change against "
                                + changes.base()
                                + "; read what waits again");
            }
        }
        final List<String> order = review.files().stream().map(Review.File::path).toList();
        return changes.files().stream()
                // The daemon's order: far-reaching first; anything it did not rank after that,
                // never left out.
                .sorted(
                        Comparator.comparingInt(
                                change -> {
                                    final int at = order.indexOf(change.path());
                                    return at < 0 ? Integer.MAX_VALUE : at;
                                }))
                .map(change -> new Ranked(change, ranked.get(change.path())))
                .toList();
    }
}
