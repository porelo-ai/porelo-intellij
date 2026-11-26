package org.fuin.porelo.intellij.ide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.intellij.diff.util.LineRange;
import com.intellij.diff.util.Side;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.intellij.cache.Changes;
import org.fuin.porelo.intellij.gate.Gate;
import org.fuin.porelo.intellij.gate.GateException;
import org.fuin.porelo.intellij.gate.PendingPush;
import org.fuin.porelo.intellij.gate.Review;
import org.fuin.porelo.intellij.gate.ReviewSummary;
import org.fuin.porelo.intellij.gate.Waiting;
import org.fuin.porelo.intellij.machine.Machine;
import org.fuin.porelo.intellij.review.CommentThread;
import org.fuin.porelo.intellij.review.Lines;
import org.fuin.porelo.intellij.review.ReviewContext;
import org.fuin.porelo.intellij.sarif.Note;
import org.fuin.porelo.intellij.sarif.NoteReader;
import org.fuin.porelo.intellij.sarif.Page;
import org.fuin.porelo.intellij.sarif.SarifRefusedException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Puts what the cache says a push changes in the order the daemon ranks it, and each comment thread
 * on the lines the cache pinned for its side, or in the discussion panel.
 */
class ReviewTabTest {

    private static final String BASE = "0123456789abcdef0123456789abcdef01234567";

    private static final String COMMIT = "1123456789abcdef0123456789abcdef01234567";

    @TempDir Path dir;

    @Test
    @DisplayName(
            "A file the daemon ranks far-reaching but the cache's diff lacks is refused, so a review never shows less than what is approved")
    void aRankedFileTheDiffLacksIsRefused() {
        final Review review =
                new Review(
                        "",
                        "",
                        List.of(
                                new Review.File(
                                        ".github/workflows/x.yml",
                                        "M",
                                        1,
                                        1,
                                        "DANGEROUS",
                                        "a workflow")),
                        "");
        final Changes changes =
                new Changes(
                        "main",
                        "0123456789abcdef0123456789abcdef01234567",
                        "1123456789abcdef0123456789abcdef01234567",
                        List.of(new Changes.Change("M", "A.java", "100644", "100644")));

        assertThatThrownBy(() -> ReviewTab.ordered(review, changes))
                .as("a ranked file the diff does not show")
                .isInstanceOf(GateException.class)
                .hasMessageContaining(".github/workflows/x.yml");
    }

    @Test
    @DisplayName(
            "A line thread is placed on its side's lines of the pinned file, its first note first, wherever the daemon sent it")
    void aLineThreadIsPlacedOnItsSide() {
        final Note reply = reply(line("t1", "g1", "new", 2, 3), "g2");
        final ReviewTab.Placed placed =
                ReviewTab.place(List.of(file()), List.of(reply, line("t1", "g1", "new", 2, 3)));

        assertThat(placed.panel()).as("threads for the panel").isEmpty();
        final ReviewTab.Discussion thread = placed.lines().get("A.java").get(0);
        assertThat(thread.at())
                .as("where the thread is")
                .isEqualTo(new Lines.Located(Side.RIGHT, new LineRange(1, 3)));
        assertThat(thread.notes())
                .extracting(Note::guid)
                .as("the first note, then its reply")
                .containsExactly("g1", "g2");
    }

    @Test
    @DisplayName(
            "An old-side thread is placed against the base's file, so lines only the push has send it to the panel")
    void anOldSideThreadIsPlacedAgainstTheBase() {
        final ReviewTab.Placed placed =
                ReviewTab.place(
                        List.of(file()),
                        List.of(line("t1", "g1", "old", 1, 2), line("t2", "g2", "old", 4, 4)));

        assertThat(placed.lines().get("A.java"))
                .extracting(ReviewTab.Discussion::id)
                .as("the thread on the base's lines")
                .containsExactly("t1");
        assertThat(placed.lines().get("A.java").get(0).at())
                .as("where it is")
                .isEqualTo(new Lines.Located(Side.LEFT, new LineRange(0, 2)));
        assertThat(placed.panel())
                .extracting(ReviewTab.Discussion::id)
                .as("the thread on lines the base lacks")
                .containsExactly("t2");
    }

    @Test
    @DisplayName(
            "A thread on lines beyond the file, on a path that is no regular file at its side, or on a path the push does not change goes to the panel as unplaced, never onto the diff")
    void aThreadWithoutAPlaceGoesToThePanel() {
        final ReviewTab.Side link =
                new ReviewTab.Side(
                        new Changes.Change("A", "link", "000000", "120000"),
                        null,
                        null,
                        bytes("A.java"));
        final ReviewTab.Side removed =
                new ReviewTab.Side(
                        new Changes.Change("D", "gone.txt", "100644", "000000"),
                        null,
                        bytes("x\n"),
                        null);
        final ReviewTab.Side wasLink =
                new ReviewTab.Side(
                        new Changes.Change("T", "was-link", "120000", "100644"),
                        null,
                        bytes("A.java"),
                        bytes("x\n"));
        final ReviewTab.Placed placed =
                ReviewTab.place(
                        List.of(file(), link, removed, wasLink),
                        List.of(
                                line("beyond", "g1", "new", 5, 6),
                                line("link", "g2", "new", 1, 1, "link"),
                                line("gone", "g3", "new", 1, 1, "gone.txt"),
                                line("unchanged", "g4", "new", 1, 1, "B.java"),
                                line("was-link", "g5", "old", 1, 1, "was-link")));

        assertThat(placed.lines()).as("threads on the diff").isEmpty();
        assertThat(placed.panel())
                .extracting(ReviewTab.Discussion::id, ReviewTab.Discussion::unplaced)
                .as("each thread, with why it has no place")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "beyond", "Lines 5-6 are not on the new side, which has 5"),
                        org.assertj.core.groups.Tuple.tuple(
                                "link", "On link, which is no regular file on the new side"),
                        org.assertj.core.groups.Tuple.tuple(
                                "gone", "On gone.txt, which is no regular file on the new side"),
                        org.assertj.core.groups.Tuple.tuple(
                                "unchanged", "On B.java, which the push does not change"),
                        org.assertj.core.groups.Tuple.tuple(
                                "was-link",
                                "On was-link, which is no regular file on the old side"));
        assertThat(placed.panel())
                .extracting(ReviewTab.Discussion::at)
                .as("no place for any of them")
                .containsOnlyNulls();
    }

    @Test
    @DisplayName(
            "File, general and outdated threads, and a kind of place the plugin does not know, go to the panel")
    void fileGeneralAndOutdatedThreadsGoToThePanel() {
        final Note onFile = note("t1", "g1", new Note.Anchor("file", "A.java", null, null), false);
        final Note general = note("t2", "g2", new Note.Anchor("general", null, null, null), false);
        final Note outdated =
                note(
                        "t3",
                        "g3",
                        new Note.Anchor("line", "A.java", "new", new Note.Region(1, 1)),
                        true);
        final Note newer = note("t4", "g4", new Note.Anchor("symbol", "A.java", null, null), false);

        final ReviewTab.Placed placed =
                ReviewTab.place(List.of(file()), List.of(onFile, general, outdated, newer));

        assertThat(placed.lines()).as("threads on the diff").isEmpty();
        assertThat(placed.panel())
                .extracting(ReviewTab.Discussion::unplaced)
                .as("why each is in the panel")
                .containsExactly(
                        "On the whole of a file",
                        "On the review",
                        "Outdated: its place is gone since it was written",
                        "On a place of the kind 'symbol'");
    }

    @Test
    @DisplayName(
            "A review the daemon diffed against another base than the cache is refused, naming both; one that names none is read")
    void aReviewOfAnotherBaseIsRefused() {
        final Changes changes = new Changes("main", BASE, COMMIT, List.of());

        assertThatThrownBy(
                        () -> ReviewTab.based(new Review("", "", List.of(), "", COMMIT), changes))
                .as("another base")
                .isInstanceOf(GateException.class)
                .hasMessageContaining(BASE)
                .hasMessageContaining(COMMIT);
        assertThatCode(() -> ReviewTab.based(new Review("", "", List.of(), "", BASE), changes))
                .as("the same base")
                .doesNotThrowAnyException();
        assertThatCode(() -> ReviewTab.based(new Review("", "", List.of(), ""), changes))
                .as("no base named")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A file's lines are counted as SARIF counts them")
    void countsLinesAsSarifDoes() {
        assertThat(ReviewTab.lineCount(bytes(""))).as("an empty file").isZero();
        assertThat(ReviewTab.lineCount(bytes("a\n"))).as("one ended line").isEqualTo(1);
        assertThat(ReviewTab.lineCount(bytes("a\nb"))).as("a last line unended").isEqualTo(2);
        assertThat(ReviewTab.lineCount(bytes("\n\n"))).as("two empty lines").isEqualTo(2);
    }

    @Test
    @DisplayName(
            "A page the reader refuses leaves every thread of the push out and says why, never a part shown")
    @DisabledOnOs(
            value = OS.WINDOWS,
            disabledReason =
                    "its stand-in daemon, porelo-wire's VarlinkServer, makes its socket owner-only with POSIX permissions, which Windows has not")
    void aRefusedPageLeavesEveryThreadOut() throws Exception {
        final ReviewTab.Comments comments =
                readPages(
                        List.of("p1", "p2"),
                        page -> {
                            if (page.equals("p2")) {
                                throw new SarifRefusedException(
                                        "unicode-format", "/runs/0", "a bidi control");
                            }
                            return new Page(
                                    List.of(line("t-" + page, page, "new", 1, 1)), "partial");
                        });

        assertThat(comments.notes()).as("the notes of the page read before").isEmpty();
        assertThat(comments.unread()).as("why").isEqualTo("a bidi control");
    }

    @Test
    @DisplayName(
            "Threads whose last page does not say complete, or whose earlier page does, are left out and said: a cut read would pass for all there is")
    @DisabledOnOs(
            value = OS.WINDOWS,
            disabledReason =
                    "its stand-in daemon, porelo-wire's VarlinkServer, makes its socket owner-only with POSIX permissions, which Windows has not")
    void anIncompleteReadIsLeftOutAndSaid() throws Exception {
        final ReviewTab.Comments cut =
                readPages(
                        List.of("p1", "p2"),
                        page -> new Page(List.of(line("t-" + page, page, "new", 1, 1)), "partial"));
        final ReviewTab.Comments early =
                readPages(
                        List.of("p1", "p2"),
                        page ->
                                new Page(
                                        List.of(line("t-" + page, page, "new", 1, 1)), "complete"));
        final ReviewTab.Comments failed =
                readPages(List.of("p1"), page -> new Page(List.of(), "failed"));

        assertThat(cut.notes()).as("notes of a read whose last page is partial").isEmpty();
        assertThat(cut.unread())
                .as("why")
                .isEqualTo("Page 2 of 2 says the threads are partial, not complete");
        assertThat(early.notes()).as("notes of a read whose first page is complete").isEmpty();
        assertThat(early.unread())
                .as("why")
                .isEqualTo("Page 1 of 2 says the threads are complete, not partial");
        assertThat(failed.unread())
                .as("why")
                .isEqualTo("Page 1 of 1 says the threads are failed, not complete");
    }

    @Test
    @DisplayName("An item the daemon sends twice leaves every thread out, as it could stand twice")
    @DisabledOnOs(
            value = OS.WINDOWS,
            disabledReason =
                    "its stand-in daemon, porelo-wire's VarlinkServer, makes its socket owner-only with POSIX permissions, which Windows has not")
    void anItemSentTwiceIsRefused() throws Exception {
        final ReviewTab.Comments comments =
                readPages(
                        List.of("p1", "p2"),
                        page ->
                                new Page(
                                        List.of(line("t1", "same", "new", 1, 1)),
                                        page.equals("p1") ? "partial" : "complete"));

        assertThat(comments.notes()).as("notes").isEmpty();
        assertThat(comments.unread()).as("why").isEqualTo("The daemon sent the item same twice");
    }

    @Test
    @DisplayName(
            "A thread no note opens, one two notes open, and one whose reply stands elsewhere go to the list with why, never onto the diff")
    void aThreadThatIsNoneGoesToTheList() {
        final Note root = line("two", "g1", "new", 1, 1);
        final Note otherRoot = line("two", "g2", "new", 2, 2);
        final Note orphan = reply(line("none", "g3", "new", 1, 1), "g4");
        final Note opener = line("away", "g5", "new", 1, 1);
        final Note away = reply(line("away", "g6", "new", 3, 3), "g7");

        final ReviewTab.Placed placed =
                ReviewTab.place(List.of(file()), List.of(root, otherRoot, orphan, opener, away));

        assertThat(placed.lines()).as("threads on the diff").isEmpty();
        assertThat(placed.panel())
                .extracting(ReviewTab.Discussion::id, ReviewTab.Discussion::unplaced)
                .as("each thread, with why")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("two", "2 notes open this thread"),
                        org.assertj.core.groups.Tuple.tuple("none", "No note opens this thread"),
                        org.assertj.core.groups.Tuple.tuple(
                                "away", "A reply stands away from its thread's place"));
    }

    @Test
    @DisplayName(
            "A push without review runs still shows its threads: the owner's comments are read from the push itself")
    @DisabledOnOs(
            value = OS.WINDOWS,
            disabledReason =
                    "its stand-in daemon, porelo-wire's VarlinkServer, makes its socket owner-only with POSIX permissions, which Windows has not")
    void aPushWithoutRunsReadsItsThreads() throws Exception {
        final java.util.List<Map<String, Object>> calls =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        try (VarlinkServer daemon = new VarlinkServer(dir.resolve("d.sock"), Gate.INTERFACE)) {
            daemon.method(
                    "Comments",
                    (parameters, replies) -> {
                        calls.add(parameters);
                        replies.last(Map.of("sarif", "owner"));
                    });
            final ReviewTab.Comments comments;
            try (Gate gate = startedGate(daemon)) {
                comments =
                        ReviewTab.comments(
                                gate,
                                entry(null),
                                page ->
                                        new Page(
                                                List.of(line("t-" + page, page, "new", 3, 3)),
                                                "complete"));
            }

            assertThat(comments.notes())
                    .extracting(Note::guid)
                    .as("the owner's thread on a push nothing reviewed")
                    .containsExactly("owner");
            assertThat(calls).as("reads of the push").hasSize(1);
            assertThat(calls.getFirst())
                    .as("the push asked, by the fields the daemon takes")
                    .containsEntry("name", "app")
                    .doesNotContainKeys("run", "round");
        }
    }

    @Test
    @DisplayName(
            "A push with two review runs shows each thread once, since the daemon answers the push's threads whatever run is named")
    @DisabledOnOs(
            value = OS.WINDOWS,
            disabledReason =
                    "its stand-in daemon, porelo-wire's VarlinkServer, makes its socket owner-only with POSIX permissions, which Windows has not")
    void aPushWithTwoRunsReadsItsThreadsOnce() throws Exception {
        try (VarlinkServer daemon = new VarlinkServer(dir.resolve("d.sock"), Gate.INTERFACE)) {
            // As the daemon does: the push's threads, whatever else the call names.
            daemon.method("Comments", (parameters, replies) -> replies.last(Map.of("sarif", "t1")));
            final ReviewSummary.Run one =
                    new ReviewSummary.Run("r1", "task", "task", "complete", 1, 1);
            final ReviewSummary.Run two =
                    new ReviewSummary.Run("r2", "lint", "scanner", "complete", 1, 1);
            final ReviewTab.Comments comments;
            try (Gate gate = startedGate(daemon)) {
                comments =
                        ReviewTab.comments(
                                gate,
                                entry(new ReviewSummary(1, 2, 2, 0, 0, List.of(one, two))),
                                page ->
                                        new Page(
                                                List.of(line("t-" + page, page, "new", 3, 3)),
                                                "complete"));
            }

            assertThat(comments.notes())
                    .extracting(Note::guid)
                    .as("the thread, once")
                    .containsExactly("t1");
        }
    }

    @Test
    @DisplayName(
            "Each file's diff gets its own threads at their lines, each note under its writer in the plugin's words, and no control to start one")
    void eachFilesDiffGetsItsOwnThreads() {
        final Note opened = line("t1", "g1", "new", 2, 3);
        final Note answered = reply(opened, "g2");
        final ReviewThreads threads =
                new ReviewThreads(ReviewTab.place(List.of(file()), List.of(opened, answered)));
        final List<List<CommentThread>> told = new java.util.ArrayList<>();

        threads.follow(new ReviewContext("p", "r", BASE, COMMIT, "A.java"), told::add);
        threads.follow(new ReviewContext("p", "r", BASE, COMMIT, "B.java"), told::add);

        assertThat(told).as("what each follower was told").hasSize(2);
        assertThat(told.get(0))
                .extracting(CommentThread::id, CommentThread::range, CommentThread::notes)
                .as("A.java's thread, at its lines, its notes in order")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "t1",
                                new LineRange(1, 3),
                                List.of(
                                        new CommentThread.Said("a task", "text of g1"),
                                        new CommentThread.Said("a task", "reply"))));
        assertThat(told.get(1)).as("the threads of a file without any").isEmpty();
        assertThat(threads.canStart()).as("whether a new thread is offered").isFalse();
    }

    /** Reads a push whose daemon answers these pages, one per cursor, with a reader of them. */
    private ReviewTab.Comments readPages(List<String> pages, NoteReader reader) throws Exception {
        try (VarlinkServer daemon =
                new VarlinkServer(dir.resolve("p" + System.nanoTime() + ".sock"), Gate.INTERFACE)) {
            daemon.method(
                    "Comments",
                    (parameters, replies) -> {
                        final Object cursor = parameters.get("cursor");
                        final int index = cursor == null ? 0 : Integer.parseInt((String) cursor);
                        replies.last(
                                index == pages.size() - 1
                                        ? Map.of("sarif", pages.get(index))
                                        : Map.of(
                                                "sarif",
                                                pages.get(index),
                                                "cursor",
                                                String.valueOf(index + 1)));
                    });
            try (Gate gate = startedGate(daemon)) {
                return ReviewTab.comments(gate, entry(null), reader);
            }
        }
    }

    private static Gate startedGate(VarlinkServer daemon) {
        Thread.ofVirtual().start(daemon);
        return new Gate(daemon.socketPath());
    }

    private Waiting.Entry entry(@Nullable ReviewSummary review) {
        return new Waiting.Entry(
                new Machine("m", dir.resolve("d.sock")),
                "p",
                "r",
                "u",
                "/m",
                new PendingPush(
                        "app", COMMIT, "s", "w", "at", "f", "I1", "", "C1", 1, null, review),
                0);
    }

    /** A.java: three lines at the base, five in the push. */
    private static ReviewTab.Side file() {
        return new ReviewTab.Side(
                new Changes.Change("M", "A.java", "100644", "100644"),
                null,
                bytes("a\nb\nc\n"),
                bytes("a\nb2\nc2\nd\ne\n"));
    }

    private static Note line(String thread, String guid, String side, int start, int end) {
        return line(thread, guid, side, start, end, "A.java");
    }

    private static Note line(
            String thread, String guid, String side, int start, int end, String path) {
        return note(
                thread,
                guid,
                new Note.Anchor("line", path, side, new Note.Region(start, end)),
                false);
    }

    private static Note note(String thread, String guid, Note.Anchor anchor, boolean outdated) {
        return new Note(
                guid,
                "review",
                "none",
                "text of " + guid,
                anchor,
                new Note.Threading(thread, null, "open"),
                new Note.Origin("task", "A", "a task"),
                outdated,
                null);
    }

    private static Note reply(Note to, String guid) {
        return new Note(
                guid,
                to.kind(),
                to.level(),
                "reply",
                to.anchor(),
                new Note.Threading(to.thread().id(), to.guid(), "open"),
                to.origin(),
                false,
                null);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
