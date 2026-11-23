package org.fuin.porelo.intellij.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.fuin.porelo.wire.LimitFactors;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Speaks the gate's calls with a stand-in daemon on a real unix socket, whose answers carry the
 * fields the daemon's interface definition gives them.
 */
@DisabledOnOs(
        value = OS.WINDOWS,
        disabledReason =
                "its stand-in daemon, porelo-wire's VarlinkServer, makes its socket owner-only with POSIX permissions, which Windows has not")
class GateTest {

    private static final String COMMIT = "9119cf0a6a99d1e2b3c4d5e6f708192a3b4c5d6e";

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
            "Projects are read with their repositories, the project's own first, and what waits in each")
    void readsProjects() {
        daemon.method(
                "Projects",
                (parameters, replies) ->
                        replies.last(
                                Map.of(
                                        "projects",
                                        List.of(
                                                Map.of(
                                                        "name",
                                                        "porelo",
                                                        "securityClass",
                                                        "guarded",
                                                        "file",
                                                        "/home/a/porelo/project.yml",
                                                        "mirror",
                                                        "",
                                                        "pending",
                                                        2,
                                                        "tasks",
                                                        1,
                                                        "running",
                                                        1,
                                                        "prepared",
                                                        true,
                                                        "preparedState",
                                                        "READY",
                                                        "repositories",
                                                        List.of(
                                                                Map.of(
                                                                        "name",
                                                                        "porelo",
                                                                        "own",
                                                                        true,
                                                                        "upstream",
                                                                        "git@github.com:a/p.git",
                                                                        "mirror",
                                                                        "/m/p",
                                                                        "pending",
                                                                        0),
                                                                Map.of(
                                                                        "name",
                                                                        "core",
                                                                        "own",
                                                                        false,
                                                                        "upstream",
                                                                        "git@github.com:a/c.git",
                                                                        "mirror",
                                                                        "/m/c",
                                                                        "pending",
                                                                        2)))))));

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThat(gate.projects())
                    .as("the projects")
                    .containsExactly(
                            new Project(
                                    "porelo",
                                    List.of(
                                            new Project.Repository(
                                                    "porelo", true, "git@github.com:a/p.git", 0),
                                            new Project.Repository(
                                                    "core", false, "git@github.com:a/c.git", 2)),
                                    2));
        }
    }

    @Test
    @DisplayName(
            "Watching the projects hands over every answer of the daemon's stream, in its order, until the stream ends")
    void watchesTheProjects() {
        daemon.method(
                "WatchProjects",
                (parameters, replies) -> {
                    assertThat(replies.streaming()).as("whether the stream was asked for").isTrue();
                    replies.more(Map.of("projects", List.of(project("one", 0))));
                    replies.more(Map.of("projects", List.of(project("one", 1))));
                    replies.last(Map.of("projects", List.of(project("one", 2))));
                });
        final List<Integer> seen = new java.util.ArrayList<>();

        try (Gate gate = new Gate(daemon.socketPath())) {
            gate.watch(
                    projects -> seen.add(projects.getFirst().repositories().getFirst().pending()));
        }

        assertThat(seen).as("how much waited, answer by answer").containsExactly(0, 1, 2);
    }

    @Test
    @DisplayName("A stream of projects that the daemon cuts off is a failure, never an end")
    void aCutStreamIsAFailure() {
        daemon.method(
                "WatchProjects",
                (parameters, replies) -> {
                    replies.more(Map.of("projects", List.of(project("one", 0))));
                    throw new IllegalStateException("gone");
                });

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThatThrownBy(() -> gate.watch(projects -> {}))
                    .as("a stream cut off")
                    .isInstanceOf(GateException.class)
                    .hasMessageContaining("WatchProjects");
        }
    }

    private static Map<String, Object> project(String name, int pending) {
        return Map.of(
                "name",
                name,
                "pending",
                pending,
                "repositories",
                List.of(
                        Map.of(
                                "name",
                                name,
                                "own",
                                true,
                                "upstream",
                                "git@example.org:" + name + ".git",
                                "pending",
                                pending)));
    }

    @Test
    @DisplayName(
            "What waits at a repository's gate is asked of that repository, and listed newest first")
    void readsWhatWaitsNewestFirst() {
        daemon.method(
                "Pending",
                (parameters, replies) -> {
                    asked.set(parameters);
                    replies.last(
                            Map.of(
                                    "mirror",
                                    "/m/c",
                                    "mode",
                                    "gatekeeping",
                                    "seededFrom",
                                    "",
                                    "pending",
                                    List.of(
                                            push("older", "2026-10-07T16:00:00Z"),
                                            push("newer", "2026-10-07T17:00:00Z"))));
                });

        try (Gate gate = new Gate(daemon.socketPath())) {
            final Gate.Pending pending = gate.pending("porelo", "core");

            assertThat(pending.pushes())
                    .extracting(PendingPush::name)
                    .as("the pushes, newest first")
                    .containsExactly("newer", "older");
            assertThat(pending.mirror()).as("the gate's mirror").isEqualTo("/m/c");
        }
        assertThat(asked.get())
                .as("what the daemon was asked")
                .isEqualTo(Map.of("project", "porelo", "repository", "core"));
    }

    @Test
    @DisplayName("A review keeps the daemon's order, and a dangerous file is far-reaching")
    void readsAReview() {
        daemon.method(
                "Review",
                (parameters, replies) ->
                        replies.last(
                                Map.of(
                                        "diff",
                                        "diff --git",
                                        "log",
                                        "abc x",
                                        "asked",
                                        "Fix the build",
                                        "files",
                                        List.of(
                                                Map.of(
                                                        "path",
                                                        ".github/workflows/build.yml",
                                                        "status",
                                                        "M",
                                                        "added",
                                                        3,
                                                        "removed",
                                                        1,
                                                        "rank",
                                                        "DANGEROUS",
                                                        "reason",
                                                        "a CI definition"),
                                                Map.of(
                                                        "path",
                                                        "src/A.java",
                                                        "status",
                                                        "A",
                                                        "added",
                                                        10,
                                                        "removed",
                                                        0,
                                                        "rank",
                                                        "ORDINARY",
                                                        "reason",
                                                        "")))));

        try (Gate gate = new Gate(daemon.socketPath())) {
            final Review review = gate.review("porelo", "core", "app", COMMIT);

            assertThat(review.files())
                    .extracting(Review.File::path)
                    .as("the files in the daemon's order")
                    .containsExactly(".github/workflows/build.yml", "src/A.java");
            assertThat(review.files())
                    .extracting(Review.File::farReaching)
                    .as("which reach far")
                    .containsExactly(true, false);
            assertThat(review.asked()).as("what the task was asked").isEqualTo("Fix the build");
        }
    }

    @Test
    @DisplayName(
            "A review of a task that is gone has no 'asked', which is not the same as an empty one")
    void aReviewOfAGoneTaskAskedNothingKnown() {
        daemon.method(
                "Review",
                (parameters, replies) ->
                        replies.last(Map.of("diff", "", "log", "", "files", List.of())));

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThat(gate.review("porelo", "core", "app", COMMIT).asked())
                    .as("what a gone task was asked")
                    .isNull();
        }
    }

    @Test
    @DisplayName("Approve passes the branch and the full commit that was shown")
    void approvesTheCommitThatWasShown() {
        // A real daemon answers 'forwarded' with the push's name, measured against
        // 0.4.2~snapshot.268.
        daemon.method(
                "Approve",
                (parameters, replies) -> {
                    asked.set(parameters);
                    replies.last(Map.of("forwarded", "app", "branch", "main"));
                });

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThat(gate.approve("porelo", "core", "app", "main", COMMIT))
                    .as("what was forwarded")
                    .isEqualTo("app onto main");
        }
        assertThat(asked.get())
                .as("what the daemon was asked")
                .isEqualTo(
                        Map.of(
                                "project",
                                "porelo",
                                "repository",
                                "core",
                                "name",
                                "app",
                                "branch",
                                "main",
                                "commit",
                                COMMIT));
    }

    @Test
    @DisplayName(
            "A push that moved after it was shown is refused as MovedSinceReview, naming both commits")
    void aPushThatMovedIsRefused() {
        daemon.method(
                "Approve",
                (parameters, replies) -> {
                    throw new VarlinkException(
                            Gate.INTERFACE + ".MovedSinceReview",
                            Map.of(
                                    "name",
                                    "app",
                                    "reviewed",
                                    COMMIT,
                                    "now",
                                    "0123456789abcdef0123456789abcdef01234567"));
                });

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThatThrownBy(() -> gate.approve("porelo", "core", "app", "main", COMMIT))
                    .as("approving a push that moved")
                    .isInstanceOfSatisfying(
                            MovedSinceReviewException.class,
                            moved ->
                                    assertThat(moved.now())
                                            .as("what it holds now")
                                            .isEqualTo("0123456789abcdef0123456789abcdef01234567"));
        }
    }

    @Test
    @DisplayName(
            "A branch that holds earlier work is refused as BranchExists, said in words with the next name")
    void aTakenBranchIsRefusedInWords() {
        daemon.method(
                "Approve",
                (parameters, replies) -> {
                    throw new VarlinkException(
                            Gate.INTERFACE + ".BranchExists",
                            Map.of("branch", "porelo/app", "at", "4d2e9a1"));
                });

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThatThrownBy(() -> gate.approve("porelo", "core", "app", "porelo/app", COMMIT))
                    .as("approving onto a taken branch")
                    .isInstanceOfSatisfying(
                            BranchExistsException.class,
                            taken -> {
                                assertThat(taken.getMessage())
                                        .as("what the person reads")
                                        .isEqualTo(
                                                "porelo/app already holds 4d2e9a1, from earlier work. Nothing was forwarded.");
                                assertThat(taken.next())
                                        .as("the name offered instead")
                                        .isEqualTo("porelo/app-2");
                            });
        }
    }

    @Test
    @DisplayName(
            "A BranchExists without its fields is still said in the daemon's words, never as a missing field")
    void aTakenBranchWithoutItsFieldsIsSaidAsIs() {
        daemon.method(
                "Approve",
                (parameters, replies) -> {
                    throw new VarlinkException(
                            Gate.INTERFACE + ".BranchExists", Map.of("branch", "porelo/app"));
                });

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThatThrownBy(() -> gate.approve("porelo", "core", "app", "porelo/app", COMMIT))
                    .as("a refusal with a field missing")
                    .isExactlyInstanceOf(GateException.class)
                    .hasMessage("Approve was refused: BranchExists {branch=porelo/app}");
        }
    }

    @Test
    @DisplayName(
            "The name offered after a numbered branch is the next number, never the same one again")
    void theNameOfferedCountsOn() {
        assertThat(new BranchExistsException("porelo/app-2", "a").next())
                .as("after -2")
                .isEqualTo("porelo/app-3");
        assertThat(new BranchExistsException("porelo/app-9", "a").next())
                .as("after -9")
                .isEqualTo("porelo/app-10");
        assertThat(new BranchExistsException("fix-", "a").next())
                .as("a dash with no number")
                .isEqualTo("fix--2");
    }

    @Test
    @DisplayName("Reject passes the reason, and says whether the task was told")
    void rejectsWithAReason() {
        daemon.method(
                "Reject",
                (parameters, replies) -> {
                    asked.set(parameters);
                    replies.last(Map.of("rejected", "app", "told", false));
                });

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThat(gate.reject("porelo", "core", "app", "Tests are missing"))
                    .as("whether the task was told")
                    .isFalse();
        }
        assertThat(asked.get())
                .as("what the daemon was asked")
                .containsEntry("reason", "Tests are missing");
    }

    @Test
    @DisplayName("Any other refusal names the daemon's error")
    void anotherRefusalNamesTheError() {
        daemon.method(
                "Pending",
                (parameters, replies) -> {
                    throw new VarlinkException(
                            Gate.INTERFACE + ".NoSuchProject", Map.of("project", "gone"));
                });

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThatThrownBy(() -> gate.pending("gone", "gone"))
                    .as("a refused call")
                    .isInstanceOf(GateException.class)
                    .hasMessageContaining("Pending was refused: NoSuchProject");
        }
    }

    @Test
    @DisplayName("An answer without a field the plugin reads is refused, never read as empty")
    void anAnswerWithoutAFieldIsRefused() {
        daemon.method("Pending", (parameters, replies) -> replies.last(Map.of("mirror", "/m/c")));

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThatThrownBy(() -> gate.pending("porelo", "core"))
                    .as("an answer without 'pending'")
                    .isInstanceOf(GateException.class)
                    .hasMessageContaining("without 'pending'");
        }
    }

    @Test
    @DisplayName("A socket nothing answers at is named")
    void aSocketNothingAnswersAtIsNamed() {
        final Path nowhere = dir.resolve("nowhere.sock");

        assertThatThrownBy(() -> new Gate(nowhere))
                .as("connecting to nothing")
                .isInstanceOf(GateException.class)
                .hasMessageContaining("Nothing answers at " + nowhere);
    }

    @Test
    @DisplayName(
            "A daemon that takes the call and never answers is left within the read's deadline, so it holds up no other machine and no closing project")
    void aDaemonThatNeverAnswersIsLeft() throws InterruptedException {
        final java.util.concurrent.CountDownLatch released =
                new java.util.concurrent.CountDownLatch(1);
        daemon.method(
                "Projects",
                (parameters, replies) -> {
                    try {
                        released.await();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                });
        try (Gate gate = new Gate(daemon.socketPath(), java.time.Duration.ofSeconds(2))) {
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    java.time.Duration.ofSeconds(20),
                    () ->
                            assertThatThrownBy(gate::projects)
                                    .as("a call that is never answered")
                                    .isInstanceOf(GateException.class)
                                    .hasMessageContaining("did not answer within 2 s"));
        } finally {
            released.countDown();
        }
    }

    @Test
    @DisplayName(
            "A repository without 'own', or with a count no int holds, is refused, never read as not the project's or as nothing waiting")
    void aRepositoryReadWrongIsRefused() {
        daemon.method(
                "Projects",
                (parameters, replies) ->
                        replies.last(
                                Map.of(
                                        "projects",
                                        List.of(
                                                Map.of(
                                                        "name",
                                                        "porelo",
                                                        "pending",
                                                        1,
                                                        "repositories",
                                                        List.of(
                                                                Map.of(
                                                                        "name",
                                                                        "core",
                                                                        "upstream",
                                                                        "u",
                                                                        "pending",
                                                                        1)))))));
        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThatThrownBy(gate::projects)
                    .as("a repository without 'own'")
                    .isInstanceOf(GateException.class)
                    .hasMessageContaining("'own'");
        }
        daemon.method(
                "Projects",
                (parameters, replies) ->
                        replies.last(
                                Map.of(
                                        "projects",
                                        List.of(
                                                Map.of(
                                                        "name",
                                                        "porelo",
                                                        "pending",
                                                        1,
                                                        "repositories",
                                                        List.of(
                                                                Map.of(
                                                                        "name",
                                                                        "core",
                                                                        "own",
                                                                        true,
                                                                        "upstream",
                                                                        "u",
                                                                        "pending",
                                                                        4_294_967_296L)))))));
        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThatThrownBy(gate::projects)
                    .as("a count of 2^32, which an int would read as 0")
                    .isInstanceOf(GateException.class)
                    .hasMessageContaining("'pending'");
        }
    }

    @Test
    @DisplayName(
            "The name offered for a taken branch counts on past what a long holds, so approving never crashes on it")
    void theNameOfferedCountsOnPastALong() {
        assertThat(new BranchExistsException("b-99999999999999999999", "a").next())
                .as("the name offered after a long number")
                .isEqualTo("b-100000000000000000000");
    }

    private static Map<String, Object> push(String name, String at) {
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
                at,
                "fetch",
                "git fetch ssh://a@m/m/c refs/porelo/incoming/"
                        + name
                        + ":refs/remotes/porelo/"
                        + name);
    }

    @Test
    @DisplayName("The factors are read from the daemon's limits line, not from its numbers")
    void readsTheFactorsFromTheLimitsLine() {
        daemon.method(
                "Limits",
                (parameters, replies) ->
                        replies.last(
                                Map.of(
                                        "line",
                                        "services=2.0,machines=1.5,polling=3.0,retries=0.5",
                                        "services",
                                        9.0,
                                        "machines",
                                        9.0,
                                        "polling",
                                        9.0,
                                        "retries",
                                        9.0)));

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThat(gate.limits())
                    .as("the factors the daemon answered")
                    .isEqualTo(
                            LimitFactors.parse(
                                    "services=2.0,machines=1.5,polling=3.0,retries=0.5"));
        }
    }

    @Test
    @DisplayName("After the factors are read, an answer is waited on by the services factor")
    void waitsOnLaterAnswersByTheServicesFactor() {
        daemon.method(
                "Limits",
                (parameters, replies) ->
                        replies.last(
                                Map.of(
                                        "line",
                                        "services=0.1,machines=1.0,polling=1.0,retries=1.0",
                                        "services",
                                        0.1,
                                        "machines",
                                        1.0,
                                        "polling",
                                        1.0,
                                        "retries",
                                        1.0)));
        daemon.method("Projects", (parameters, replies) -> {});

        try (Gate gate = new Gate(daemon.socketPath())) {
            gate.limits();
            final long started = System.nanoTime();
            assertThatThrownBy(gate::projects)
                    .as("a daemon that does not answer")
                    .isInstanceOf(GateException.class)
                    .hasMessage("Projects did not answer within 3 s");
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .as("how long it was waited on")
                    .isLessThan(Duration.ofSeconds(10));
        }
    }

    @Test
    @DisplayName(
            "A connection opened with a machine's last factors waits on its first answer by them")
    void waitsOnTheFirstAnswerByTheFactorsGiven() {
        daemon.method("Projects", (parameters, replies) -> {});

        try (Gate gate = new Gate(daemon.socketPath(), LimitFactors.parse("services=0.1"))) {
            assertThatThrownBy(gate::projects)
                    .as("a daemon that does not answer")
                    .isInstanceOf(GateException.class)
                    .hasMessage("Projects did not answer within 3 s");
        }
    }

    @Test
    @DisplayName("A limits line that breaks the grammar is refused, never read as all four at 1.0")
    void refusesABrokenLimitsLine() {
        daemon.method(
                "Limits",
                (parameters, replies) ->
                        replies.last(
                                Map.of(
                                        "line",
                                        "services=0",
                                        "services",
                                        0.0,
                                        "machines",
                                        1.0,
                                        "polling",
                                        1.0,
                                        "retries",
                                        1.0)));

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThatThrownBy(gate::limits)
                    .as("a factor of 0")
                    .isInstanceOf(GateException.class)
                    .hasMessageContaining("limits line 'services=0' is refused");
        }
    }

    @Test
    @DisplayName("A daemon that answers no limits line is refused")
    void refusesAnAnswerWithoutTheLine() {
        daemon.method("Limits", (parameters, replies) -> replies.last(Map.of("services", 1.0)));

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThatThrownBy(gate::limits)
                    .as("an answer without the line")
                    .isInstanceOf(GateException.class)
                    .hasMessageContaining("The daemon answered without 'line'");
        }
    }

    @Test
    @DisplayName(
            "A limits line that leaves a factor out is refused: porelod writes all four, and one left out would be read as 1.0")
    void refusesALimitsLineWithoutAllFour() {
        for (final String line :
                List.of("", "services=2.0", "services=1.0,machines=1.0,polling=1.0")) {
            daemon.method("Limits", (parameters, replies) -> replies.last(Map.of("line", line)));

            try (Gate gate = new Gate(daemon.socketPath())) {
                assertThatThrownBy(gate::limits)
                        .as("the line '" + line + "'")
                        .isInstanceOf(GateException.class)
                        .hasMessageContaining("does not name all four");
            }
        }
    }

    private static Map<String, Object> file(String path) {
        return Map.of(
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
                "");
    }

    private static Map<String, Object> pagedReview(int whole, int next) {
        return Map.of(
                "diff",
                "diff --git",
                "log",
                "abc x",
                "files",
                List.of(file("a")),
                "paged",
                Map.of(
                        "files",
                        whole,
                        "commits",
                        0,
                        "logBytes",
                        5,
                        "diffBytes",
                        10,
                        "filesNext",
                        next));
    }

    @Test
    @DisplayName(
            "A review whose files come in pages is read to its last file, each page asked for the commit listed")
    void readsEveryPageOfAReviewsFiles() {
        final List<Map<String, Object>> pages = new java.util.ArrayList<>();
        daemon.method("Review", (parameters, replies) -> replies.last(pagedReview(3, 1)));
        daemon.method(
                "ReviewPage",
                (parameters, replies) -> {
                    pages.add(parameters);
                    final int from = ((Number) parameters.get("from")).intValue();
                    replies.last(
                            from == 1
                                    ? Map.of("files", List.of(file("b")), "next", 2)
                                    : Map.of("files", List.of(file("c"))));
                });

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThat(gate.review("porelo", "core", "app", COMMIT).files())
                    .extracting(Review.File::path)
                    .as("every file, in the review's order")
                    .containsExactly("a", "b", "c");
        }
        assertThat(pages)
                .as("the pages asked for")
                .hasSize(2)
                .allSatisfy(
                        page ->
                                assertThat(page)
                                        .as("a page's call")
                                        .containsEntry("commit", COMMIT)
                                        .containsEntry("part", "files")
                                        .containsEntry("name", "app"));
    }

    @Test
    @DisplayName("Pages that leave a file out are refused, never shown as the whole review")
    void refusesPagesThatLeaveAFileOut() {
        daemon.method("Review", (parameters, replies) -> replies.last(pagedReview(3, 1)));
        daemon.method(
                "ReviewPage",
                (parameters, replies) -> replies.last(Map.of("files", List.of(file("b")))));

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThatThrownBy(() -> gate.review("porelo", "core", "app", COMMIT))
                    .as("two files of three")
                    .isInstanceOf(GateException.class)
                    .hasMessage("The daemon's review of app holds 2 files, but says it has 3");
        }
    }

    @Test
    @DisplayName("A page that brings no file is refused, so pages that never end are not asked for")
    void refusesAPageWithoutAFile() {
        final java.util.concurrent.atomic.AtomicInteger pages =
                new java.util.concurrent.atomic.AtomicInteger();
        daemon.method("Review", (parameters, replies) -> replies.last(pagedReview(3, 1)));
        daemon.method(
                "ReviewPage",
                (parameters, replies) ->
                        // After a hundred empty pages it ends, so a loop shows as a wrong answer.
                        replies.last(
                                pages.incrementAndGet() < 100
                                        ? Map.of("files", List.of(), "next", 1)
                                        : Map.of("files", List.of())));

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThatThrownBy(() -> gate.review("porelo", "core", "app", COMMIT))
                    .as("an empty page that asks for the same place again")
                    .isInstanceOf(GateException.class)
                    .hasMessage("The daemon's review of app sent a page of files without a file");
        }
        assertThat(pages).as("pages asked for").hasValue(1);
    }

    @Test
    @DisplayName(
            "A review that says it has more files than it sent, and no page for them, is refused")
    void refusesAReviewThatSaysItHasMoreFilesThanItSent() {
        daemon.method(
                "Review",
                (parameters, replies) ->
                        replies.last(
                                Map.of(
                                        "diff",
                                        "",
                                        "log",
                                        "",
                                        "files",
                                        List.of(file("a")),
                                        "paged",
                                        Map.of(
                                                "files",
                                                2,
                                                "commits",
                                                0,
                                                "logBytes",
                                                0,
                                                "diffBytes",
                                                10,
                                                "diffNext",
                                                0))));

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThatThrownBy(() -> gate.review("porelo", "core", "app", COMMIT))
                    .as("one file of two, with no page for the other")
                    .isInstanceOf(GateException.class)
                    .hasMessage("The daemon's review of app holds 1 files, but says it has 2");
        }
    }

    @Test
    @DisplayName("A page that starts elsewhere than where the files read so far end is refused")
    void refusesAPageThatDoesNotFollowOn() {
        daemon.method("Review", (parameters, replies) -> replies.last(pagedReview(3, 1)));
        daemon.method(
                "ReviewPage",
                (parameters, replies) ->
                        replies.last(Map.of("files", List.of(file("b")), "next", 1)));

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThatThrownBy(() -> gate.review("porelo", "core", "app", COMMIT))
                    .as("a page asked for again")
                    .isInstanceOf(GateException.class)
                    .hasMessage("The daemon's review of app asks for files from 1 after 2 of 3");
        }
    }

    @Test
    @DisplayName(
            "A push that moved while its pages were read is refused as moved, naming the commit now")
    void refusesAReviewWhosePushMovedBetweenPages() {
        daemon.method("Review", (parameters, replies) -> replies.last(pagedReview(2, 1)));
        daemon.method(
                "ReviewPage",
                (parameters, replies) -> {
                    throw new VarlinkException(
                            Gate.INTERFACE + ".MovedSinceReview",
                            Map.of(
                                    "name",
                                    "app",
                                    "reviewed",
                                    COMMIT,
                                    "now",
                                    "0123456789abcdef0123456789abcdef01234567"));
                });

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThatThrownBy(() -> gate.review("porelo", "core", "app", COMMIT))
                    .as("a push that moved between pages")
                    .isInstanceOfSatisfying(
                            MovedSinceReviewException.class,
                            moved ->
                                    assertThat(moved.now())
                                            .as("what it holds now")
                                            .isEqualTo("0123456789abcdef0123456789abcdef01234567"));
        }
    }

    @Test
    @DisplayName("A review that fits one message is read without asking for a page")
    void readsAWholeReviewWithoutAPage() {
        final java.util.concurrent.atomic.AtomicInteger pages =
                new java.util.concurrent.atomic.AtomicInteger();
        daemon.method(
                "Review",
                (parameters, replies) ->
                        replies.last(Map.of("diff", "", "log", "", "files", List.of(file("a")))));
        daemon.method(
                "ReviewPage",
                (parameters, replies) -> {
                    pages.incrementAndGet();
                    replies.last(Map.of("files", List.of()));
                });

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThat(gate.review("porelo", "core", "app", COMMIT).files())
                    .as("its one file")
                    .hasSize(1);
        }
        assertThat(pages).as("pages asked for").hasValue(0);
    }
}
