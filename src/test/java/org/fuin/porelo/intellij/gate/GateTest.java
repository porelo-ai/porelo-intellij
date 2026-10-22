package org.fuin.porelo.intellij.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
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
            final Review review = gate.review("porelo", "core", "app");

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
            assertThat(gate.review("porelo", "core", "app").asked())
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
}
