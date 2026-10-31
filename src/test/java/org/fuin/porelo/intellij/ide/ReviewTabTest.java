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
import org.fuin.porelo.intellij.review.Lines;
import org.fuin.porelo.intellij.sarif.Note;
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
            "Only a trusted new-side suggestion that replaces exactly its placed lines gets a preview; an old-side, out-of-range, E0 or reply-elsewhere one gets none")
    void onlyAConfinedSuggestionIsPreviewed() {
        final Note.Fix onAnchor = new Note.Fix(new Note.Region(2, 3), "b2\nc2\n");
        final Note fixed = fixed(line("t1", "g1", "new", 2, 3), onAnchor, "I1");
        final Note outside =
                fixed(
                        line("t1", "g2", "new", 2, 3),
                        new Note.Fix(new Note.Region(2, 6), "x\n"),
                        "A");
        final Note fromOutside = fixed(line("t1", "g3", "new", 2, 3), onAnchor, "E0");
        final Note unknown = fixed(line("t1", "g4", "new", 2, 3), onAnchor, null);
        final Note projects = fixed(line("t1", "g7", "new", 2, 3), onAnchor, "E1");
        final Note gone = outdated(fixed(line("t1", "g8", "new", 2, 3), onAnchor, "A"));
        final Note elsewhere =
                fixed(line("t1", "g5", "new", 4, 5), new Note.Fix(new Note.Region(4, 5), ""), "A");
        final ReviewTab.Discussion onNew =
                ReviewTab.place(
                                List.of(file()),
                                List.of(
                                        fixed,
                                        outside,
                                        fromOutside,
                                        unknown,
                                        elsewhere,
                                        projects,
                                        gone))
                        .lines()
                        .get("A.java")
                        .get(0);
        final Note oldFixed =
                fixed(line("t2", "g6", "old", 1, 2), new Note.Fix(new Note.Region(1, 2), ""), "A");
        final ReviewTab.Discussion onOld =
                ReviewTab.place(List.of(file()), List.of(oldFixed)).lines().get("A.java").get(0);

        assertThat(onNew.previewable(fixed)).as("a trusted fix of its own lines").isTrue();
        assertThat(onNew.previewable(outside)).as("a fix beyond its lines").isFalse();
        assertThat(onNew.previewable(fromOutside)).as("a fix from E0").isFalse();
        assertThat(onNew.previewable(unknown)).as("a fix from no stamped level").isFalse();
        assertThat(onNew.previewable(elsewhere)).as("a reply's fix elsewhere").isFalse();
        assertThat(onNew.previewable(projects)).as("a fix from E1").isTrue();
        assertThat(onNew.previewable(gone)).as("an outdated reply's fix").isFalse();
        assertThat(onOld.previewable(oldFixed)).as("an old-side fix").isFalse();
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
            "Each run is read page by page; a run the daemon or the reader refuses is left out whole and said, never shown in part")
    @DisabledOnOs(
            value = OS.WINDOWS,
            disabledReason =
                    "its stand-in daemon, porelo-wire's VarlinkServer, makes its socket owner-only with POSIX permissions, which Windows has not")
    void aRefusedRunIsLeftOutWholeAndSaid() throws Exception {
        try (VarlinkServer daemon = new VarlinkServer(dir.resolve("d.sock"), Gate.INTERFACE)) {
            Thread.ofVirtual().start(daemon);
            daemon.method(
                    "Comments",
                    (parameters, replies) -> {
                        final String run = (String) parameters.get("run");
                        final boolean first = parameters.get("cursor") == null;
                        replies.last(
                                first
                                        ? Map.of("sarif", run + "-1", "cursor", run + "-c")
                                        : Map.of("sarif", run + "-2"));
                    });
            final ReviewSummary.Run good =
                    new ReviewSummary.Run("r1", "task", "task", "complete", 2, 2);
            final ReviewSummary.Run bad =
                    new ReviewSummary.Run("r2", "lint", "scanner", "complete", 1, 1);
            final ReviewTab.Comments comments;
            try (Gate gate = new Gate(daemon.socketPath())) {
                comments =
                        ReviewTab.comments(
                                gate,
                                entry(new ReviewSummary(1, 3, 2, 0, 0, List.of(good, bad))),
                                page -> {
                                    if (page.equals("r2-2")) {
                                        throw new SarifRefusedException(
                                                "unicode-format", "/runs/0", "a bidi control");
                                    }
                                    return List.of(line("t-" + page, page, "new", 1, 1));
                                });
            }

            assertThat(comments.notes())
                    .extracting(Note::guid)
                    .as("every page of the run read, and nothing of the refused one")
                    .containsExactly("r1-1", "r1-2");
            assertThat(comments.unread())
                    .as("the refused run, with why")
                    .containsExactly(new ReviewTab.Unread(bad, "a bidi control"));
        }
    }

    @Test
    @DisplayName("A push without checks reads no comments and calls nothing")
    @DisabledOnOs(
            value = OS.WINDOWS,
            disabledReason =
                    "its stand-in daemon, porelo-wire's VarlinkServer, makes its socket owner-only with POSIX permissions, which Windows has not")
    void aPushWithoutChecksReadsNothing() throws Exception {
        // The daemon answers no call, so any call would leave a run unread.
        try (VarlinkServer daemon = new VarlinkServer(dir.resolve("d.sock"), Gate.INTERFACE);
                Gate gate = startedGate(daemon)) {
            final ReviewTab.Comments comments =
                    ReviewTab.comments(
                            gate,
                            entry(null),
                            page -> {
                                throw new AssertionError("read " + page);
                            });

            assertThat(comments.notes()).as("notes").isEmpty();
            assertThat(comments.unread()).as("unread runs").isEmpty();
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

    private static Note fixed(Note note, Note.Fix fix, @Nullable String level) {
        return new Note(
                note.guid(),
                note.kind(),
                note.level(),
                note.text(),
                note.anchor(),
                new Note.Threading(note.thread().id(), "g0", "open"),
                new Note.Origin("task", level, "a task"),
                false,
                fix);
    }

    private static Note outdated(Note note) {
        return new Note(
                note.guid(),
                note.kind(),
                note.level(),
                note.text(),
                note.anchor(),
                note.thread(),
                note.origin(),
                true,
                note.fix());
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
