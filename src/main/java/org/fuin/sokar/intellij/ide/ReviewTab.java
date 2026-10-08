package org.fuin.sokar.intellij.ide;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.fuin.sokar.intellij.cache.Changes;
import org.fuin.sokar.intellij.gate.Gate;
import org.fuin.sokar.intellij.gate.Review;
import org.fuin.sokar.intellij.gate.Waiting;
import org.jspecify.annotations.Nullable;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;

/**
 * Opens a waiting push as an editor tab: the tree of the files it changes on the left, IntelliJ's diff of the one
 * selected on the right, against where it and its branch last met, with Approve and Reject above.
 * <p>
 * The files are read from the plugin's own bare cache; nothing is checked out and nothing of the person's is
 * written to.
 */
final class ReviewTab {

    private ReviewTab() {
    }

    /**
     * Reads the push in the background and opens it in the project's editor.
     *
     * @param project The window to open it in.
     * @param entry The push.
     */
    static void open(Project project, Waiting.Entry entry) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Reading " + entry.push().name(), true) {
            @Override
            public void run(ProgressIndicator indicator) {
                final Read read;
                try {
                    read = read(entry);
                } catch (RuntimeException ex) {
                    NotificationGroupManager.getInstance().getNotificationGroup(SokarWatch.NOTIFICATIONS)
                            .createNotification("Cannot read " + entry.push().name() + ": " + ex.getMessage(),
                                    NotificationType.ERROR).notify(project);
                    return;
                }
                ApplicationManager.getApplication().invokeLater(() -> FileEditorManager.getInstance(project)
                        .openFile(new ReviewFile(entry, read), true), project.getDisposed());
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
    record Read(Review review, Changes changes, List<Side> files) {
    }

    /**
     * One file as both sides have it.
     *
     * @param change The file and how it changed.
     * @param ranked How the daemon ranks it, or {@code null} when it did not.
     * @param base The file at the base, or {@code null} when the base has none.
     * @param pushed The file in the push, or {@code null} when the push removes it.
     */
    record Side(Changes.Change change, Review.@Nullable File ranked, byte @Nullable [] base,
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

    private static Read read(Waiting.Entry entry) {
        final Review review;
        try (Gate gate = SokarWatch.get().gate(entry.machine())) {
            review = gate.review(entry.project(), entry.repository(), entry.push().name());
        }
        final SokarWatch watch = SokarWatch.get();
        final Changes changes = watch.fetch(entry);
        final Map<String, Review.File> ranked = review.files().stream()
                .collect(Collectors.toMap(Review.File::path, Function.identity(), (one, other) -> one));
        final List<String> order = review.files().stream().map(Review.File::path).toList();
        final List<Side> files = new ArrayList<>();
        changes.files().stream()
                // The daemon's order: far-reaching first; anything it did not rank after that, never left out.
                .sorted(Comparator.comparingInt(change -> {
                    final int at = order.indexOf(change.path());
                    return at < 0 ? Integer.MAX_VALUE : at;
                }))
                .forEach(change -> files.add(new Side(change, ranked.get(change.path()),
                        watch.file(entry, changes.base(), change.path()),
                        watch.file(entry, changes.commit(), change.path()))));
        return new Read(review, changes, files);
    }

}
