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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.fuin.porelo.intellij.cache.Changes;
import org.fuin.porelo.intellij.gate.Gate;
import org.fuin.porelo.intellij.gate.GateException;
import org.fuin.porelo.intellij.gate.MovedSinceReviewException;
import org.fuin.porelo.intellij.gate.Review;
import org.fuin.porelo.intellij.gate.Waiting;
import org.fuin.porelo.intellij.review.Lines;
import org.fuin.porelo.intellij.sarif.Note;
import org.fuin.porelo.intellij.sarif.NoteReader;
import org.fuin.porelo.intellij.sarif.Page;
import org.fuin.porelo.intellij.sarif.ProfileNoteReader;
import org.fuin.porelo.intellij.sarif.SarifRefusedException;
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
     * @param into The open projects that hold the push's repository, where it can be opened.
     * @param threads The push's comment threads, at their lines or for the discussion panel.
     * @param unread Why the push's threads could not be read; absent when they were.
     */
    record Read(
            Review review,
            Changes changes,
            List<Side> files,
            List<IntoProjects.Target> into,
            Placed threads,
            @Nullable String unread) {}

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
            review =
                    gate.review(
                            entry.project(),
                            entry.repository(),
                            entry.push().name(),
                            entry.push().commit());
        } catch (MovedSinceReviewException moved) {
            // What is shown, fetched and approved is one commit, so the moved push is listed anew.
            PoreloWatch.get().readNow();
            throw new GateException(
                    entry.push().name()
                            + " moved to "
                            + moved.now()
                            + " while its review was read; what waits is read again, open it from"
                            + " there",
                    moved);
        }
        final PoreloWatch watch = PoreloWatch.get();
        final Changes changes = watch.fetch(entry);
        based(review, changes);
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
        final Comments comments;
        try (Gate gate = watch.gate(entry.machine())) {
            comments =
                    comments(
                            gate,
                            entry,
                            new ProfileNoteReader(
                                    entry.project(),
                                    entry.repository(),
                                    entry.push().name(),
                                    changes.commit(),
                                    changes.base()));
        }
        return new Read(
                review,
                changes,
                files,
                IntoProjects.holding(entry),
                place(files, comments.notes()),
                comments.unread());
    }

    /**
     * Refuses a review the daemon diffed against another base than the cache: its comments' old
     * side would be placed on lines of a file it never saw.
     *
     * @param review What the daemon answered.
     * @param changes What the cache read.
     * @throws GateException If the daemon names another base.
     */
    static void based(Review review, Changes changes) {
        final String base = review.base();
        if (base != null && !base.equals(changes.base())) {
            throw new GateException(
                    "The daemon reviewed against "
                            + base
                            + ", the cache against "
                            + changes.base()
                            + "; read what waits again");
        }
    }

    /**
     * The threads of a push, read whole or not at all.
     *
     * @param notes Every note of every page, in the daemon's order; none when they were not read.
     * @param unread Why they were not read; absent when they were.
     */
    record Comments(List<Note> notes, @Nullable String unread) {

        /**
         * Copies the list.
         *
         * @param notes The notes.
         * @param unread Why not.
         */
        Comments {
            notes = List.copyOf(notes);
        }
    }

    /**
     * Reads every page of the push's threads, whatever review runs it has. A page the daemon or the
     * reader refuses, a page that is not the last but says the threads are complete, a last one
     * that does not, or an item sent twice leaves every thread out and says why: a part shown would
     * read as all there is.
     *
     * @param gate The push's daemon.
     * @param entry The push.
     * @param reader Reads each page.
     * @return What was read, or why nothing was.
     */
    static Comments comments(Gate gate, Waiting.Entry entry, NoteReader reader) {
        final List<Note> notes = new ArrayList<>();
        try {
            final List<String> pages =
                    gate.comments(entry.project(), entry.repository(), entry.push().name());
            final java.util.Set<String> guids = new java.util.HashSet<>();
            for (int index = 0; index < pages.size(); index++) {
                final Page page = reader.read(pages.get(index));
                final String expected = index == pages.size() - 1 ? "complete" : "partial";
                if (!page.completeness().equals(expected)) {
                    return new Comments(
                            List.of(),
                            "Page "
                                    + (index + 1)
                                    + " of "
                                    + pages.size()
                                    + " says the threads are "
                                    + page.completeness()
                                    + ", not "
                                    + expected);
                }
                for (final Note note : page.notes()) {
                    if (!guids.add(note.guid())) {
                        return new Comments(
                                List.of(), "The daemon sent the item " + note.guid() + " twice");
                    }
                    notes.add(note);
                }
            }
        } catch (GateException | SarifRefusedException ex) {
            if (Thread.currentThread().isInterrupted()) {
                // Cancelled: the read ends, nothing is said unread.
                throw ex;
            }
            return new Comments(List.of(), String.valueOf(ex.getMessage()));
        }
        return new Comments(notes, null);
    }

    /**
     * A thread of a review: at its lines in the diff of its file, or in the discussion panel.
     *
     * @param id The thread's id.
     * @param notes Its notes, its first one first, then in the daemon's order.
     * @param path The file whose diff shows it; absent for one the panel shows.
     * @param at Where it is in that diff; absent for one the panel shows.
     * @param unplaced Why the panel shows it, in words; absent for one at its lines.
     */
    record Discussion(
            String id,
            List<Note> notes,
            @Nullable String path,
            Lines.@Nullable Located at,
            @Nullable String unplaced) {

        /**
         * Copies the list.
         *
         * @param id The id.
         * @param notes The notes.
         * @param path The file.
         * @param at Where.
         * @param unplaced Why not.
         */
        Discussion {
            notes = List.copyOf(notes);
        }

        /**
         * Returns the note that opened the thread, whose place is the thread's.
         *
         * @return The first note.
         */
        Note first() {
            return notes.get(0);
        }
    }

    /**
     * A review's threads, placed.
     *
     * @param lines The threads at their lines, by the file whose diff shows them.
     * @param panel The threads the discussion panel shows: on a file, on the review, outdated, or
     *     on no place the diff has.
     */
    record Placed(Map<String, List<Discussion>> lines, List<Discussion> panel) {

        /**
         * Copies the map and the lists.
         *
         * @param lines The placed threads.
         * @param panel The others.
         */
        Placed {
            final Map<String, List<Discussion>> copied = new LinkedHashMap<>();
            lines.forEach((path, threads) -> copied.put(path, List.copyOf(threads)));
            lines = java.util.Collections.unmodifiableMap(copied);
            panel = List.copyOf(panel);
        }
    }

    /**
     * Places each thread on the lines its first note names, against the bytes the cache pinned for
     * that side, never against a file anywhere else: a path the push does not change, a side that
     * holds no regular file, or lines the file does not have send the thread to the panel.
     *
     * @param files Each file as both sides have it.
     * @param notes Every note read.
     * @return The threads, placed.
     */
    static Placed place(List<Side> files, List<Note> notes) {
        final Map<String, Side> byPath = new java.util.HashMap<>();
        for (final Side file : files) {
            byPath.put(file.change().path(), file);
        }
        final Map<String, List<Note>> threads = new LinkedHashMap<>();
        for (final Note note : notes) {
            threads.computeIfAbsent(note.thread().id(), id -> new ArrayList<>()).add(note);
        }
        final Map<String, List<Discussion>> lines = new LinkedHashMap<>();
        final List<Discussion> panel = new ArrayList<>();
        threads.forEach(
                (id, thread) -> {
                    // The first note is the one that answers nothing, wherever the daemon put it.
                    final List<Note> ordered = new ArrayList<>(thread);
                    final List<Note> roots =
                            ordered.stream()
                                    .filter(note -> note.thread().replyTo() == null)
                                    .toList();
                    if (roots.size() == 1) {
                        ordered.remove(roots.getFirst());
                        ordered.add(0, roots.getFirst());
                    }
                    final Note first = ordered.get(0);
                    try {
                        opened(roots.size(), ordered);
                        final Lines.Located at = located(first, byPath);
                        final String path = java.util.Objects.requireNonNull(first.anchor().path());
                        lines.computeIfAbsent(path, ignored -> new ArrayList<>())
                                .add(new Discussion(id, ordered, path, at, null));
                    } catch (IllegalArgumentException ex) {
                        panel.add(new Discussion(id, ordered, null, null, ex.getMessage()));
                    }
                });
        return new Placed(lines, panel);
    }

    /**
     * Refuses a thread that is not one: opened by no note or by several, or with a reply away from
     * the place of the note it answers, which would show the reply where it was not written.
     */
    private static void opened(int roots, List<Note> ordered) {
        if (roots == 0) {
            throw new IllegalArgumentException("No note opens this thread");
        }
        if (roots > 1) {
            throw new IllegalArgumentException(roots + " notes open this thread");
        }
        final Note.Anchor place = ordered.get(0).anchor();
        for (final Note note : ordered) {
            if (!note.anchor().equals(place)) {
                throw new IllegalArgumentException("A reply stands away from its thread's place");
            }
        }
    }

    /** Where a note's lines are in its file's diff, or why it has no such place. */
    private static Lines.Located located(Note note, Map<String, Side> byPath) {
        final Note.Anchor anchor = note.anchor();
        if (note.outdated()) {
            throw new IllegalArgumentException("Outdated: its place is gone since it was written");
        }
        switch (anchor.kind()) {
            case "line" -> {
                // Placed below.
            }
            case "file" -> throw new IllegalArgumentException("On the whole of a file");
            case "general" -> throw new IllegalArgumentException("On the review");
            default ->
                    throw new IllegalArgumentException(
                            "On a place of the kind '" + anchor.kind() + "'");
        }
        final String path = anchor.path();
        final Note.Region region = anchor.region();
        final String side = anchor.side();
        if (path == null || region == null || side == null) {
            throw new IllegalArgumentException("On lines of no file");
        }
        final Side file = byPath.get(path);
        if (file == null) {
            throw new IllegalArgumentException("On " + path + ", which the push does not change");
        }
        final byte @Nullable [] bytes;
        final boolean regular;
        switch (side) {
            case "new" -> {
                bytes = file.pushed();
                regular = file.change().regular(true);
            }
            case "old" -> {
                bytes = file.base();
                regular = file.change().regular(false);
            }
            default ->
                    throw new IllegalArgumentException(
                            "On " + path + ", on the side '" + side + "'");
        }
        if (bytes == null || !regular) {
            throw new IllegalArgumentException(
                    "On " + path + ", which is no regular file on the " + side + " side");
        }
        return Lines.toViewer(side, region.startLine(), region.endLine(), lineCount(bytes));
    }

    /**
     * Counts a file's lines as SARIF does: a last line without its line feed is one.
     *
     * @param bytes The file.
     * @return Its lines; none for an empty file.
     */
    static int lineCount(byte[] bytes) {
        int lines = 0;
        for (final byte at : bytes) {
            if (at == '\n') {
                lines++;
            }
        }
        return bytes.length > 0 && bytes[bytes.length - 1] != '\n' ? lines + 1 : lines;
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
