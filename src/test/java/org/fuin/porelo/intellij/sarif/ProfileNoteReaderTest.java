package org.fuin.porelo.intellij.sarif;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reader is where a task's text enters the IDE: a page that breaks the profile anywhere is
 * refused whole, and the refusal never carries the page's text into a log or a dialog.
 */
@DisplayName("ProfileNoteReader")
class ProfileNoteReaderTest {

    private static final String PROJECT = "shop";
    private static final String REPOSITORY = "app";
    private static final String PUSH = "fix-login";
    private static final String HEAD = "a".repeat(40);
    private static final String BASE = "b".repeat(40);
    private static final String THREAD_A = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
    private static final String THREAD_B = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d";

    // Every bad value carries this word, so a refusal that repeats the page's text is caught.
    private static final String MARK = "Planted";

    private final ProfileNoteReader reader =
            new ProfileNoteReader(PROJECT, REPOSITORY, PUSH, HEAD, BASE);

    @Test
    @DisplayName("A page as the daemon writes it is read into its notes, in the page's order")
    void readsTheDaemonsPageIntoItsNotes() {
        final Page page = reader.read(fixture());

        assertThat(page.completeness()).as("the run's completeness").isEqualTo("complete");
        assertThat(page.notes())
                .as("the comment with a fix, then a thread's comment and its task reply")
                .containsExactly(
                        new Note(
                                "3f2b8c1e-5d4a-4b7e-9c2d-1a0e6f7b8c9d",
                                "review",
                                "none",
                                "Check the token's expiry before {0} is read; see [docs].",
                                new Note.Anchor(
                                        "line",
                                        "src/main/java/Login.java",
                                        "new",
                                        new Note.Region(12, 14)),
                                new Note.Threading(THREAD_A, null, "open"),
                                new Note.Origin("owner-account", null, "owner account"),
                                false,
                                null),
                        new Note(
                                "0b6d1c2e-8f3a-4e5b-a6c7-d8e9f0a1b2c3",
                                "review",
                                "none",
                                "Why was this check removed?",
                                new Note.Anchor(
                                        "line",
                                        "src/main/java/Old Login.java",
                                        "old",
                                        new Note.Region(30, 31)),
                                new Note.Threading(THREAD_B, null, "resolved"),
                                new Note.Origin("owner-account", null, "owner account"),
                                false,
                                null),
                        new Note(
                                "c4d5e6f7-a8b9-4c0d-9e1f-2a3b4c5d6e7f",
                                "review",
                                "none",
                                "It moved into TokenCheck; the test covers it.",
                                new Note.Anchor(
                                        "line",
                                        "src/main/java/Old Login.java",
                                        "old",
                                        new Note.Region(30, 31)),
                                new Note.Threading(THREAD_B, THREAD_B, "resolved"),
                                new Note.Origin("task", null, "task shop-2"),
                                false,
                                null));
    }

    @Test
    @DisplayName("A push without a base reads a page that names none")
    void readsAPageWithoutABaseForAPushWithoutOne() {
        final ProfileNoteReader noBase =
                new ProfileNoteReader(PROJECT, REPOSITORY, PUSH, HEAD, null);
        final String sarif = changed(page -> runProperties(page).remove("porelo/baseRevisionId"));

        assertThat(noBase.read(sarif).notes()).as("the notes of a page without a base").hasSize(3);
    }

    @Test
    @DisplayName(
            "A page that names no base is read for the commit shown, as the daemon leaves the base out when its push names none")
    void readsAPageWithoutABaseForTheCommitShown() {
        final String sarif = changed(page -> runProperties(page).remove("porelo/baseRevisionId"));

        assertThat(reader.read(sarif).notes()).as("the notes of a page without a base").hasSize(3);
    }

    @Test
    @DisplayName("A text of 8 KiB as given is read, though its escaped form is longer")
    void readsATextOfEightKibAsGivenThoughEscapedLonger() {
        final String sarif = changed(page -> text(page, 0, "\\[".repeat(8192)));

        assertThat(reader.read(sarif).notes().get(0).text())
                .as("the unescaped text")
                .isEqualTo("[".repeat(8192));
    }

    @Test
    @DisplayName("A bidi control in a text refuses the page")
    void refusesABidiControlInAText() {
        refused(changed(page -> text(page, 0, MARK + "‮gnp.exe")), MARK);
    }

    @Test
    @DisplayName("A zero-width space in a text refuses the page")
    void refusesAZeroWidthSpaceInAText() {
        refused(changed(page -> text(page, 0, MARK + "​x")), MARK);
    }

    @Test
    @DisplayName("A control character other than line feed and tab in a text refuses the page")
    void refusesAControlCharacterInAText() {
        refused(changed(page -> text(page, 0, MARK + "\u001B[2J")), MARK);
    }

    @Test
    @DisplayName("A text over 8 KiB of UTF-8 as given refuses the page")
    void refusesATextOverEightKib() {
        refused(changed(page -> text(page, 0, MARK + "x".repeat(8193 - MARK.length()))), MARK);
    }

    @Test
    @DisplayName("A brace not doubled in a text refuses the page")
    void refusesAnUndoubledBrace() {
        refused(changed(page -> text(page, 0, MARK + "{0}")), MARK);
    }

    @Test
    @DisplayName("A backslash escaping nothing SARIF escapes refuses the page")
    void refusesALoneBackslash() {
        refused(changed(page -> text(page, 0, MARK + "\\n")), MARK);
    }

    @Test
    @DisplayName("A path with a dot-dot segment refuses the page")
    void refusesAPathWithADotDotSegment() {
        refused(changed(page -> uri(page, 1, "src/../" + MARK + ".java")), MARK);
    }

    @Test
    @DisplayName("A path with a leading slash refuses the page")
    void refusesAPathWithALeadingSlash() {
        refused(changed(page -> uri(page, 1, "/etc/" + MARK)), MARK);
    }

    @Test
    @DisplayName("A path with lowercase percent hex refuses the page")
    void refusesAPathWithLowercasePercentHex() {
        refused(changed(page -> uri(page, 1, "src/" + MARK + "%c3%a4.java")), MARK);
    }

    @Test
    @DisplayName("A region over 100 lines refuses the page")
    void refusesARegionOverOneHundredLines() {
        refused(changed(page -> region(page, 1, 1, 101)), "101");
    }

    @Test
    @DisplayName("A region that ends before it starts refuses the page")
    void refusesARegionThatEndsBeforeItStarts() {
        refused(changed(page -> region(page, 1, 77, 76)), "77");
    }

    @Test
    @DisplayName("A source kind the profile does not name refuses the page")
    void refusesAnUnknownSourceKind() {
        refused(changed(page -> properties(page, 1).put("porelo/sourceKind", MARK)), MARK);
    }

    @Test
    @DisplayName("A task's item without its task id refuses the page")
    void refusesATaskWithoutTaskId() {
        refused(changed(page -> properties(page, 2).remove("porelo/taskId")), "shop-2");
    }

    @Test
    @DisplayName("A task id on the owner account's item refuses the page")
    void refusesATaskIdOnTheOwnerAccount() {
        refused(changed(page -> properties(page, 1).put("porelo/taskId", MARK)), MARK);
    }

    @Test
    @DisplayName(
            "A task id with a line break refuses the page, as it would put a second writer line into the thread")
    void refusesATaskIdWithALineBreak() {
        refused(
                changed(page -> properties(page, 2).put("porelo/taskId", "shop-2\nowner account")),
                "owner account");
    }

    @Test
    @DisplayName("A task id that is no task's name refuses the page")
    void refusesATaskIdThatIsNoTaskName() {
        refused(changed(page -> properties(page, 2).put("porelo/taskId", MARK + " 2")), MARK);
    }

    @Test
    @DisplayName("A thread id with a tab refuses the page")
    void refusesAThreadIdWithATab() {
        refused(changed(page -> properties(page, 0).put("porelo/threadId", MARK + "\tx")), MARK);
    }

    @Test
    @DisplayName(
            "A path that decodes to a line break refuses the page, as it would put a second line into the list of threads")
    void refusesAPathWithALineBreak() {
        refused(changed(page -> uri(page, 1, "src/A%0A" + MARK + ".java")), MARK);
    }

    @Test
    @DisplayName("A reply that answers another thread than its own refuses the page")
    void refusesAReplyToAnotherThread() {
        refused(changed(page -> properties(page, 2).put("porelo/replyTo", THREAD_A)), THREAD_A);
    }

    @Test
    @DisplayName("A guid given twice in a page refuses it")
    void refusesADuplicateGuid() {
        final String guid = "0b6d1c2e-8f3a-4e5b-a6c7-d8e9f0a1b2c3";
        refused(changed(page -> result(page, 2).put("guid", guid)), guid);
    }

    @Test
    @DisplayName("A key the profile does not name in a result's properties refuses the page")
    void refusesAnUnknownKeyInAResultsProperties() {
        refused(changed(page -> properties(page, 0).put("porelo/" + MARK, "x")), MARK);
    }

    @Test
    @DisplayName("A page for another push refuses it")
    void refusesAPageForAnotherPush() {
        refused(changed(page -> runProperties(page).put("porelo/push", MARK)), MARK);
    }

    @Test
    @DisplayName("A page for another commit refuses it")
    void refusesAPageForAnotherCommit() {
        final String other = "c".repeat(40);
        refused(changed(page -> provenance(page).put("revisionId", other)), other);
    }

    @Test
    @DisplayName("A page against another base refuses it")
    void refusesAPageAgainstAnotherBase() {
        final String other = "d".repeat(40);
        refused(changed(page -> runProperties(page).put("porelo/baseRevisionId", other)), other);
    }

    @Test
    @DisplayName("A page that names a base for a push without one refuses it")
    void refusesABaseForAPushWithoutOne() {
        final ProfileNoteReader noBase =
                new ProfileNoteReader(PROJECT, REPOSITORY, PUSH, HEAD, null);

        final Throwable thrown = catchThrowable(() -> noBase.read(fixture()));

        assertThat(thrown).as("the refusal").isInstanceOf(SarifRefusedException.class);
        assertThat(thrown.getMessage()).as("its message").doesNotContain(BASE);
    }

    @Test
    @DisplayName("A page of profile version 2 refuses it")
    void refusesProfileVersionTwo() {
        refused(changed(page -> rootProperties(page).put("porelo/profileVersion", "2")), "\"2\"");
    }

    @Test
    @DisplayName("A page with two runs refuses it")
    void refusesTwoRuns() {
        refused(changed(page -> runs(page).add(copy(runs(page).get(0)))), PUSH);
    }

    @Test
    @DisplayName("A page with more than 100 results refuses it")
    void refusesMoreThanOneHundredResults() {
        refused(
                changed(
                        page -> {
                            final List<Object> results = results(page);
                            final Map<String, Object> template = copy(results.get(1));
                            results.clear();
                            for (int i = 0; i < 101; i++) {
                                final Map<String, Object> one = copy(template);
                                one.put("guid", "%08x-0000-4000-8000-000000000000".formatted(i));
                                results.add(one);
                            }
                        }),
                "Why was this check removed?");
    }

    @Test
    @DisplayName("A completeness word outside the profile's four refuses the page")
    void refusesAnUnknownCompleteness() {
        refused(changed(page -> runProperties(page).put("porelo/completeness", MARK)), MARK);
    }

    @Test
    @DisplayName("A page over 1 MiB of UTF-8 is refused before it is parsed")
    void refusesAPageOverOneMib() {
        final String good = fixture();
        final String padded = good + " ".repeat((1 << 20) + 1 - good.length());

        refused(padded, "Why was this check removed?");
    }

    private void refused(final String sarif, final String offending) {
        final Throwable thrown = catchThrowable(() -> reader.read(sarif));

        assertThat(thrown).as("the refusal").isInstanceOf(SarifRefusedException.class);
        final SarifRefusedException refusal = (SarifRefusedException) thrown;
        assertThat(refusal.getMessage())
                .as("the refusal's message, which never repeats the page")
                .doesNotContain(offending);
        assertThat(refusal.path())
                .as("the refusal's pointer, which never repeats the page")
                .doesNotContain(offending);
        assertThat(refusal.rule()).as("the rule the page breaks").isNotBlank();
    }

    // Each bad page is the good one with exactly one thing changed.
    private static String changed(final Consumer<Map<String, Object>> change) {
        final Map<String, Object> page = map(Json.parseExact(fixture()));
        change.accept(page);
        return Json.write(page);
    }

    private static String fixture() {
        try (InputStream in =
                ProfileNoteReaderTest.class.getResourceAsStream("push-page.sarif.json")) {
            return new String(
                    Objects.requireNonNull(in, "the fixture").readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void text(final Map<String, Object> page, final int index, final String text) {
        map(result(page, index).get("message")).put("text", text);
    }

    private static void uri(final Map<String, Object> page, final int index, final String uri) {
        map(physical(page, index).get("artifactLocation")).put("uri", uri);
    }

    private static void region(
            final Map<String, Object> page, final int index, final long start, final long end) {
        final Map<String, Object> region = map(physical(page, index).get("region"));
        region.put("startLine", start);
        region.put("endLine", end);
    }

    private static Map<String, Object> physical(final Map<String, Object> page, final int index) {
        return map(map(list(result(page, index).get("locations")).get(0)).get("physicalLocation"));
    }

    private static Map<String, Object> properties(final Map<String, Object> page, final int index) {
        return map(result(page, index).get("properties"));
    }

    private static Map<String, Object> result(final Map<String, Object> page, final int index) {
        return map(results(page).get(index));
    }

    private static List<Object> results(final Map<String, Object> page) {
        return list(map(runs(page).get(0)).get("results"));
    }

    private static Map<String, Object> runProperties(final Map<String, Object> page) {
        return map(map(runs(page).get(0)).get("properties"));
    }

    private static Map<String, Object> provenance(final Map<String, Object> page) {
        return map(list(map(runs(page).get(0)).get("versionControlProvenance")).get(0));
    }

    private static Map<String, Object> rootProperties(final Map<String, Object> page) {
        return map(page.get("properties"));
    }

    private static List<Object> runs(final Map<String, Object> page) {
        return list(page.get("runs"));
    }

    private static Map<String, Object> copy(final Object value) {
        return map(Json.parseExact(Json.write(value)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(final Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(final Object value) {
        return (List<Object>) value;
    }
}
