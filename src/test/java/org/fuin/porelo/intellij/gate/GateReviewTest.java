package org.fuin.porelo.intellij.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Speaks the review's calls - a push's level, class and checks, and its comments - with a stand-in
 * daemon on a real unix socket, whose answers carry the fields the daemon's interface gives them.
 */
@DisabledOnOs(
        value = OS.WINDOWS,
        disabledReason =
                "its stand-in daemon, porelo-wire's VarlinkServer, makes its socket owner-only with POSIX permissions, which Windows has not")
class GateReviewTest {

    private static final String COMMIT = "9119cf0a6a99d1e2b3c4d5e6f708192a3b4c5d6e";

    private static final String BASE = "0123456789abcdef0123456789abcdef01234567";

    private static final String GUID = "3f2b8c1e-0d4a-4b6e-9a7f-1c2d3e4f5a6b";

    private static final String THREAD = "7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";

    @TempDir Path dir;

    private VarlinkServer daemon;

    private final AtomicReference<Map<String, Object>> asked = new AtomicReference<>();

    @BeforeEach
    void startTheDaemon() {
        daemon = new VarlinkServer(dir.resolve("porelod.sock"), Gate.INTERFACE);
        Thread.ofVirtual().start(daemon);
    }

    @AfterEach
    void stopTheDaemon() throws Exception {
        daemon.close();
    }

    @Test
    @DisplayName(
            "A push's level, class, approvals needed and checks are read as the daemon answers them")
    void readsAPushsLevelClassAndChecks() {
        final Map<String, Object> push = new HashMap<>(push("app"));
        push.put("sourceLevel", "E0");
        push.put("sourceWords", "from a forge account outside the project");
        push.put("riskClass", "C3");
        push.put("approvalsNeeded", 2);
        push.put("task", "porelo-default-app");
        push.put(
                "review",
                Map.of(
                        "round", 2,
                        "openFindings", 3,
                        "openThreads", 1,
                        "heldInbound", 4,
                        "waitingOutbound", 0,
                        "runs",
                                List.of(
                                        Map.of(
                                                "run", "r1",
                                                "name", "clippy",
                                                "kind", "scanner",
                                                "state", "partial",
                                                "findings", 5,
                                                "openFindings", 3))));
        daemon.method("Pending", (parameters, replies) -> replies.last(pending(push)));

        try (Gate gate = new Gate(daemon.socketPath())) {
            final PendingPush read = gate.pending("porelo", "core").pushes().getFirst();

            assertThat(read.sourceLevel()).as("the source level").isEqualTo("E0");
            assertThat(read.sourceWords())
                    .as("who it is from")
                    .isEqualTo("from a forge account outside the project");
            assertThat(read.riskClass()).as("the risk class").isEqualTo("C3");
            assertThat(read.approvalsNeeded()).as("how many must approve").isEqualTo(2);
            assertThat(read.task()).as("the task").isEqualTo("porelo-default-app");
            assertThat(read.review())
                    .as("the checks")
                    .isEqualTo(
                            new ReviewSummary(
                                    2,
                                    3,
                                    1,
                                    4,
                                    0,
                                    List.of(
                                            new ReviewSummary.Run(
                                                    "r1", "clippy", "scanner", "partial", 5, 3))));
        }
    }

    @Test
    @DisplayName(
            "A push from a daemon that names no level, class or checks has none, never a guessed one")
    void anOlderDaemonsPushHasNoLevel() {
        daemon.method("Pending", (parameters, replies) -> replies.last(pending(push("app"))));

        try (Gate gate = new Gate(daemon.socketPath())) {
            final PendingPush read = gate.pending("porelo", "core").pushes().getFirst();

            assertThat(read.sourceLevel()).as("the source level").isNull();
            assertThat(read.riskClass()).as("the risk class").isNull();
            assertThat(read.approvalsNeeded()).as("how many must approve").isNull();
            assertThat(read.review()).as("the checks").isNull();
        }
    }

    @Test
    @DisplayName(
            "A level that is not text, or a count of approvals that is no count, is refused rather than dropped")
    void aLevelOrApprovalCountReadWrongIsRefused() {
        final Map<String, Object> level = new HashMap<>(push("app"));
        level.put("sourceLevel", 1);
        final Map<String, Object> approvals = new HashMap<>(push("app"));
        approvals.put("approvalsNeeded", 0);
        final AtomicInteger call = new AtomicInteger();
        daemon.method(
                "Pending",
                (parameters, replies) ->
                        replies.last(pending(call.getAndIncrement() == 0 ? level : approvals)));

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThatThrownBy(() -> gate.pending("porelo", "core"))
                    .as("a level of 1")
                    .isInstanceOf(GateException.class)
                    .hasMessageContaining("'sourceLevel'");
        }
        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThatThrownBy(() -> gate.pending("porelo", "core"))
                    .as("approvals needed of 0")
                    .isInstanceOf(GateException.class)
                    .hasMessageContaining("'approvalsNeeded'");
        }
    }

    @Test
    @DisplayName("A review reads the base it diffed against and each file's risk class")
    void readsAReviewsBaseAndClasses() {
        daemon.method(
                "Review",
                (parameters, replies) ->
                        replies.last(
                                Map.of(
                                        "diff", "",
                                        "log", "",
                                        "asked", "",
                                        "base", BASE,
                                        "files",
                                                List.of(
                                                        file("a", Map.of("riskClass", "C2")),
                                                        file("b", Map.of())))));

        try (Gate gate = new Gate(daemon.socketPath())) {
            final Review review = gate.review("porelo", "core", "app");

            assertThat(review.base()).as("the base").isEqualTo(BASE);
            assertThat(review.files())
                    .extracting(Review.File::riskClass)
                    .as("each file's class, absent where the daemon names none")
                    .containsExactly("C2", null);
        }
    }

    @Test
    @DisplayName(
            "A comment is sent with its document, its parent and its id, never to the forge, and comes back as the daemon stored it")
    void sendsAComment() {
        daemon.method(
                "Comment",
                (parameters, replies) -> {
                    asked.set(parameters);
                    replies.last(Map.of("guid", GUID, "thread", THREAD, "sarif", "{\"stored\":1}"));
                });

        try (Gate gate = new Gate(daemon.socketPath())) {
            final Gate.Stored stored =
                    gate.comment("porelo", "core", "app", "{\"sent\":1}", GUID, "ij-1.a_b");

            assertThat(stored)
                    .as("what was stored")
                    .isEqualTo(new Gate.Stored(GUID, THREAD, "{\"stored\":1}"));
        }
        assertThat(asked.get())
                .as("what the daemon was asked")
                .isEqualTo(
                        Map.of(
                                "project", "porelo",
                                "repository", "core",
                                "name", "app",
                                "sarif", "{\"sent\":1}",
                                "replyTo", GUID,
                                "id", "ij-1.a_b"));
    }

    @Test
    @DisplayName("A new thread is sent without a parent")
    void sendsANewThreadWithoutAParent() {
        daemon.method(
                "Comment",
                (parameters, replies) -> {
                    asked.set(parameters);
                    replies.last(Map.of("guid", GUID, "thread", THREAD, "sarif", "{}"));
                });

        try (Gate gate = new Gate(daemon.socketPath())) {
            gate.comment("porelo", "core", "app", "{}", null, "ij-1");
        }
        assertThat(asked.get()).as("what the daemon was asked").doesNotContainKey("replyTo");
    }

    @Test
    @DisplayName(
            "A comment's id outside the daemon's grammar is refused before anything is sent, so a retry never makes a second comment")
    void aCommentIdOutsideTheGrammarIsRefused() {
        final AtomicInteger calls = new AtomicInteger();
        daemon.method(
                "Comment",
                (parameters, replies) -> {
                    calls.incrementAndGet();
                    replies.last(Map.of("guid", GUID, "thread", THREAD, "sarif", "{}"));
                });

        try (Gate gate = new Gate(daemon.socketPath())) {
            for (final String id : List.of("", "a b", "x".repeat(65), "ä")) {
                assertThatThrownBy(() -> gate.comment("porelo", "core", "app", "{}", null, id))
                        .as("the id '%s'", id)
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
        assertThat(calls).as("comments sent").hasValue(0);
    }

    @Test
    @DisplayName("A comment that moved on the daemon says that nothing was stored")
    void aCommentOnAPushThatMovedStoresNothing() {
        daemon.method(
                "Comment",
                (parameters, replies) -> {
                    throw new VarlinkException(
                            Gate.INTERFACE + ".MovedSinceReview",
                            Map.of("name", "app", "reviewed", COMMIT, "now", BASE));
                });

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThatThrownBy(() -> gate.comment("porelo", "core", "app", "{}", null, "ij-1"))
                    .as("a comment on a push that moved")
                    .isInstanceOf(MovedSinceReviewException.class)
                    .hasMessageContaining("nothing was stored")
                    .hasMessageNotContaining("forwarded");
        }
    }

    @Test
    @DisplayName(
            "A run's comments are read page by page, its run and round named, until the daemon gives no cursor")
    void readsEveryPageOfARun() {
        final List<Map<String, Object>> calls = new ArrayList<>();
        daemon.method(
                "Comments",
                (parameters, replies) -> {
                    calls.add(parameters);
                    final Object cursor = parameters.get("cursor");
                    if (cursor == null) {
                        replies.last(Map.of("sarif", "page 1", "cursor", "c1"));
                    } else if (cursor.equals("c1")) {
                        replies.last(Map.of("sarif", "page 2", "cursor", "c2"));
                    } else {
                        replies.last(Map.of("sarif", "page 3"));
                    }
                });

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThat(gate.comments("porelo", "core", "app", "r1", 2))
                    .as("the pages, in order")
                    .containsExactly("page 1", "page 2", "page 3");
        }
        assertThat(calls)
                .extracting(call -> call.get("cursor"))
                .as("the cursor each page was asked with")
                .containsExactly(null, "c1", "c2");
        assertThat(calls.getFirst())
                .as("the first page's call")
                .containsEntry("run", "r1")
                .containsEntry("name", "app");
        // The wire reads every JSON number as a double.
        assertThat(calls.getFirst().get("round"))
                .as("the round asked")
                .isInstanceOfSatisfying(
                        Number.class,
                        round -> assertThat(round.doubleValue()).as("its value").isEqualTo(2));
    }

    @Test
    @DisplayName("The current round is read without naming one")
    void readsTheCurrentRoundWithoutNamingOne() {
        daemon.method(
                "Comments",
                (parameters, replies) -> {
                    asked.set(parameters);
                    replies.last(Map.of("sarif", "page"));
                });

        try (Gate gate = new Gate(daemon.socketPath())) {
            gate.comments("porelo", "core", "app", "r1", null);
        }
        assertThat(asked.get()).as("what the daemon was asked").doesNotContainKey("round");
    }

    @Test
    @DisplayName("A daemon that answers the same cursor again is left, never read forever")
    void aCursorAnsweredAgainIsRefused() {
        final AtomicInteger calls = new AtomicInteger();
        daemon.method(
                "Comments",
                (parameters, replies) -> {
                    calls.incrementAndGet();
                    replies.last(Map.of("sarif", "page", "cursor", "same"));
                });

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThatThrownBy(() -> gate.comments("porelo", "core", "app", "r1", null))
                    .as("a cursor that does not move")
                    .isInstanceOf(GateException.class)
                    .hasMessageContaining("same");
        }
        assertThat(calls).as("pages asked").hasValue(2);
    }

    @Test
    @DisplayName("A daemon that never ends a run is left after a bounded number of pages")
    void aRunWithoutEndIsLeft() {
        final AtomicInteger calls = new AtomicInteger();
        daemon.method(
                "Comments",
                (parameters, replies) ->
                        replies.last(
                                Map.of("sarif", "page", "cursor", "c" + calls.incrementAndGet())));

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThatThrownBy(() -> gate.comments("porelo", "core", "app", "r1", null))
                    .as("a run that never ends")
                    .isInstanceOf(GateException.class)
                    .hasMessageContaining(Gate.PAGES + " pages");
        }
        assertThat(calls).as("pages asked").hasValue(Gate.PAGES);
    }

    @Test
    @DisplayName("Resolving and reopening a thread name it, and read back the state it is in now")
    void resolvesAndReopensAThread() {
        final List<Map<String, Object>> calls = new ArrayList<>();
        daemon.method(
                "ResolveThread",
                (parameters, replies) -> {
                    calls.add(parameters);
                    replies.last(
                            Map.of(
                                    "thread",
                                    THREAD,
                                    "state",
                                    Boolean.TRUE.equals(parameters.get("reopen"))
                                            ? "open"
                                            : "resolved"));
                });

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThat(gate.resolveThread("porelo", "core", "app", THREAD, false))
                    .as("resolved")
                    .isTrue();
            assertThat(gate.resolveThread("porelo", "core", "app", THREAD, true))
                    .as("resolved after reopening")
                    .isFalse();
        }
        assertThat(calls.getFirst())
                .as("resolving")
                .containsEntry("thread", THREAD)
                .doesNotContainKey("reopen");
        assertThat(calls.getLast()).as("reopening").containsEntry("reopen", true);
    }

    @Test
    @DisplayName(
            "A resolve answered for another thread, or with a state the plugin does not know, is refused rather than shown")
    void aResolveAnsweredWrongIsRefused() {
        final AtomicInteger call = new AtomicInteger();
        daemon.method(
                "ResolveThread",
                (parameters, replies) ->
                        replies.last(
                                call.getAndIncrement() == 0
                                        ? Map.of("thread", GUID, "state", "resolved")
                                        : Map.of("thread", THREAD, "state", "closed")));

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThatThrownBy(() -> gate.resolveThread("porelo", "core", "app", THREAD, false))
                    .as("an answer for another thread")
                    .isInstanceOf(GateException.class)
                    .hasMessageContaining(GUID);
        }
        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThatThrownBy(() -> gate.resolveThread("porelo", "core", "app", THREAD, false))
                    .as("a state of 'closed'")
                    .isInstanceOf(GateException.class)
                    .hasMessageContaining("closed");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusals")
    @DisplayName("Each refusal of a comment call is said in words, with its reason named")
    void eachRefusalIsSaidInWords(String error, Map<String, Object> fields, String words) {
        daemon.method(
                "Comment",
                (parameters, replies) -> {
                    throw new VarlinkException(Gate.INTERFACE + "." + error, fields);
                });

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThatThrownBy(() -> gate.comment("porelo", "core", "app", "{}", null, "ij-1"))
                    .as("the refusal %s", error)
                    .isInstanceOfSatisfying(
                            ReviewRefusedException.class,
                            refused ->
                                    assertThat(refused.reason()).as("the reason").isEqualTo(error))
                    .hasMessageContaining(words)
                    .hasMessageContaining("nothing was stored or changed");
        }
    }

    static Stream<Arguments> refusals() {
        return Stream.of(
                Arguments.of("NoSuchPush", Map.of("name", "app"), "app"),
                Arguments.of(
                        "ProfileViolation",
                        Map.of("detail", "d", "rule", "anchor", "path", "/runs/0"),
                        "/runs/0"),
                Arguments.of(
                        "ForbiddenField",
                        Map.of("detail", "d", "path", "/runs/0/results/0/guid"),
                        "/runs/0/results/0/guid"),
                Arguments.of(
                        "TooLarge", Map.of("detail", "d", "limit", "8 KiB", "path", "/m"), "8 KiB"),
                Arguments.of("UnsafeText", Map.of("detail", "d", "path", "/m/text"), "/m/text"),
                Arguments.of(
                        "AnchorOutsideBlob",
                        Map.of("path", "src/A.java", "startLine", 7, "endLine", 9),
                        "src/A.java"),
                Arguments.of(
                        "FixOutsideAnchor",
                        Map.of("detail", "d", "path", "src/A.java"),
                        "suggestion"),
                Arguments.of("OldSideFix", Map.of("detail", "d", "path", "src/A.java"), "new side"),
                Arguments.of("NoSuchResult", Map.of("guid", GUID), GUID),
                Arguments.of("NoSuchThread", Map.of("thread", THREAD), THREAD),
                Arguments.of("CursorRefused", Map.of("cursor", "c1"), "again"),
                Arguments.of("IdReused", Map.of("id", "ij-1"), "ij-1"));
    }

    @Test
    @DisplayName("A refusal without its fields is said in the daemon's own words, its reason named")
    void aRefusalWithoutItsFieldsIsSaidAsIs() {
        daemon.method(
                "Comment",
                (parameters, replies) -> {
                    throw new VarlinkException(Gate.INTERFACE + ".AnchorOutsideBlob", Map.of());
                });

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThatThrownBy(() -> gate.comment("porelo", "core", "app", "{}", null, "ij-1"))
                    .as("a refusal without fields")
                    .isInstanceOfSatisfying(
                            ReviewRefusedException.class,
                            refused ->
                                    assertThat(refused.reason())
                                            .as("the reason")
                                            .isEqualTo("AnchorOutsideBlob"))
                    .hasMessageContaining("Comment was refused: AnchorOutsideBlob");
        }
    }

    private static Map<String, Object> pending(Map<String, Object> push) {
        return Map.of("mirror", "/m/c", "pending", List.of(push));
    }

    private static Map<String, Object> push(String name) {
        return Map.of(
                "name",
                name,
                "commit",
                COMMIT,
                "subject",
                "Fix it",
                "waiting",
                "4 minutes",
                "at",
                "2026-10-07T16:00:00Z",
                "fetch",
                "git fetch ssh://a@m/m/c refs/porelo/incoming/" + name);
    }

    private static Map<String, Object> file(String path, Map<String, Object> more) {
        final Map<String, Object> file = new HashMap<>(more);
        file.putAll(
                Map.of(
                        "path",
                        path,
                        "status",
                        "M",
                        "added",
                        1,
                        "removed",
                        0,
                        "rank",
                        "ORDINARY",
                        "reason",
                        ""));
        return file;
    }
}
