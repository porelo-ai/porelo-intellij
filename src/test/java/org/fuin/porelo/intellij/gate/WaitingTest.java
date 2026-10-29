package org.fuin.porelo.intellij.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.intellij.machine.Machine;
import org.fuin.porelo.intellij.machine.Machines;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Gathers what waits from stand-in daemons on real unix sockets, one per machine. */
@DisabledOnOs(
        value = OS.WINDOWS,
        disabledReason =
                "its stand-in daemon, porelo-wire's VarlinkServer, makes its socket owner-only with POSIX permissions, which Windows has not")
class WaitingTest {

    private static final String COMMIT = "9119cf0a6a99d1e2b3c4d5e6f708192a3b4c5d6e";

    @TempDir Path dir;

    private final List<VarlinkServer> daemons = new ArrayList<>();

    private final List<String> pendingAsked = new ArrayList<>();

    @AfterEach
    void stopTheDaemons() throws Exception {
        for (final VarlinkServer daemon : daemons) {
            daemon.close();
        }
    }

    private Machine machine(String name, String at) {
        final VarlinkServer daemon = new VarlinkServer(dir.resolve(name + ".sock"), Gate.INTERFACE);
        Thread.ofVirtual().start(daemon);
        daemons.add(daemon);
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
                                                                        "porelo",
                                                                        "own",
                                                                        true,
                                                                        "upstream",
                                                                        "u",
                                                                        "pending",
                                                                        0),
                                                                Map.of(
                                                                        "name",
                                                                        "core",
                                                                        "own",
                                                                        false,
                                                                        "upstream",
                                                                        "git@example.org:porelo/core.git",
                                                                        "pending",
                                                                        1)))))));
        daemon.method(
                "Pending",
                (parameters, replies) -> {
                    synchronized (pendingAsked) {
                        pendingAsked.add(name + ":" + parameters.get("repository"));
                    }
                    replies.last(
                            Map.of(
                                    "mirror",
                                    "/m",
                                    "mode",
                                    "gatekeeping",
                                    "seededFrom",
                                    "",
                                    "pending",
                                    List.of(
                                            Map.of(
                                                    "name",
                                                    "task-" + name,
                                                    "commit",
                                                    COMMIT,
                                                    "subject",
                                                    "s",
                                                    "waiting",
                                                    "w",
                                                    "at",
                                                    at,
                                                    "fetch",
                                                    "f"))));
                });
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
                                        List.of(
                                                Map.of(
                                                        "path",
                                                        "pom.xml",
                                                        "status",
                                                        "M",
                                                        "added",
                                                        1,
                                                        "removed",
                                                        1,
                                                        "rank",
                                                        "DANGEROUS",
                                                        "reason",
                                                        "a build script"),
                                                Map.of(
                                                        "path",
                                                        "A.java",
                                                        "status",
                                                        "M",
                                                        "added",
                                                        1,
                                                        "removed",
                                                        1,
                                                        "rank",
                                                        "ORDINARY",
                                                        "reason",
                                                        "")))));
        return new Machine(name, daemon.socketPath(), null, null);
    }

    @Test
    @DisplayName(
            "What waits on every machine is listed newest first, with how many of its files reach far")
    void listsWhatWaitsEverywhereNewestFirst() {
        final List<Machine> machines =
                List.of(
                        machine("one", "2026-10-07T16:00:00Z"),
                        machine("two", "2026-10-07T17:00:00Z"));

        final Waiting waiting = Waiting.read(machines, machine -> new Gate(machine.socket()));

        assertThat(waiting.pushes())
                .extracting(
                        entry ->
                                entry.machine().name()
                                        + "/"
                                        + entry.repository()
                                        + "/"
                                        + entry.push().name()
                                        + "/"
                                        + entry.farReaching())
                .as("what waits, newest first")
                .containsExactly("two/core/task-two/1", "one/core/task-one/1");
        assertThat(waiting.pushes())
                .extracting(Waiting.Entry::upstream)
                .as("where each repository's approved work goes, as its gate says")
                .containsOnly("git@example.org:porelo/core.git");
        assertThat(waiting.silent()).as("machines that did not answer").isEmpty();
        assertThat(pendingAsked)
                .as("the repositories asked what waits: only those where something does")
                .containsExactlyInAnyOrder("one:core", "two:core");
    }

    @Test
    @DisplayName("A machine that does not answer is said, and the others are still listed")
    void aMachineThatDoesNotAnswerIsSaid() {
        final Machine gone = new Machine("gone", dir.resolve("gone.sock"), null, null);

        final Waiting waiting =
                Waiting.read(
                        List.of(gone, machine("one", "2026-10-07T16:00:00Z")),
                        machine -> new Gate(machine.socket()));

        assertThat(waiting.silent())
                .extracting(Waiting.Silent::machine)
                .as("who did not answer")
                .containsExactly("gone");
        assertThat(waiting.silent().getFirst().why())
                .as("why")
                .startsWith("does not answer: ")
                .contains("Nothing answers at");
        assertThat(waiting.pushes())
                .extracting(entry -> entry.machine().name())
                .as("who did")
                .containsExactly("one");
    }

    @Test
    @DisplayName(
            "An entry the plugin does not reach is said after the machines that did not answer, with its why")
    void anEntryItDoesNotReachIsSaid() {
        final Machine gone = new Machine("gone", dir.resolve("gone.sock"), null, null);
        final Machines.Listed listed =
                new Machines.Listed(
                        List.of(gone, machine("one", "2026-10-07T16:00:00Z")),
                        List.of(
                                new Machines.Unreachable(
                                        "Ubuntu on Windows",
                                        "is a WSL distribution ('Ubuntu'), which this"
                                                + " plugin does not reach yet")));

        final Waiting waiting = Waiting.read(listed, machine -> new Gate(machine.socket()));

        assertThat(waiting.silent())
                .extracting(Waiting.Silent::machine)
                .as("who is said, in that order")
                .containsExactly("gone", "Ubuntu on Windows");
        assertThat(waiting.silent().getLast().why())
                .as("why the WSL entry is not reached")
                .isEqualTo(
                        "is a WSL distribution ('Ubuntu'), which this plugin does not reach yet");
        assertThat(waiting.pushes())
                .extracting(entry -> entry.machine().name())
                .as("who answered")
                .containsExactly("one");
    }

    @Test
    @DisplayName(
            "A push is new when it was not seen, or when it moved to another commit, and not otherwise")
    void saysWhatIsNew() {
        final Machine machine = new Machine("m", dir.resolve("m.sock"), null, null);
        final Waiting.Entry kept = entry(machine, "kept", COMMIT);
        final Waiting.Entry moved = entry(machine, "moved", COMMIT);
        final Waiting before = new Waiting(List.of(kept, moved), List.of());
        final Waiting.Entry movedOn =
                entry(machine, "moved", "0123456789abcdef0123456789abcdef01234567");
        final Waiting.Entry arrived = entry(machine, "arrived", COMMIT);

        final Waiting now = new Waiting(List.of(arrived, movedOn, kept), List.of());

        assertThat(now.newSince(before)).as("what is new").containsExactly(arrived, movedOn);
        assertThat(now.newSince(now)).as("what is new against itself").isEmpty();
    }

    private static Waiting.Entry entry(Machine machine, String name, String commit) {
        return new Waiting.Entry(
                machine,
                "porelo",
                "core",
                "u",
                "/m",
                new PendingPush(name, commit, "s", "w", "at", "f"),
                0);
    }

    @Test
    @DisplayName(
            "An open review's push is said to have moved, with the commit it waits at now, or to be gone only from a machine that answered")
    void saysWhatAnOpenReviewsPushIsNow() {
        final Machine machine = new Machine("m", dir.resolve("m.sock"), null, null);
        final Waiting.Entry shown = entry(machine, "p", COMMIT);
        final Waiting.Entry moved = entry(machine, "p", "0123456789abcdef0123456789abcdef01234567");

        assertThat(new Waiting(List.of(shown), List.of()).now(shown))
                .as("waiting as shown")
                .isEqualTo(new Waiting.Now.Same());
        assertThat(new Waiting(List.of(moved), List.of()).now(shown))
                .as("waiting at another commit")
                .isEqualTo(new Waiting.Now.Moved(moved));
        assertThat(new Waiting(List.of(), List.of()).now(shown))
                .as("decided elsewhere")
                .isEqualTo(new Waiting.Now.Gone());
        assertThat(new Waiting(List.of(), List.of(new Waiting.Silent("m", "why"))).now(shown))
                .as("its machine silent")
                .isEqualTo(new Waiting.Now.Unknown());
        assertThat(new Waiting(List.of(moved), List.of(new Waiting.Silent("o", "why"))).now(shown))
                .as("waiting at another commit, another machine silent")
                .isEqualTo(new Waiting.Now.Moved(moved));
    }

    @Test
    @DisplayName(
            "Another push in the same repository, or one of the same name on another machine, in another project or in another repository, is not taken for the one an open review shows")
    void anotherPlacesPushIsNotTheOneShown() {
        final Machine machine = new Machine("m", dir.resolve("m.sock"), null, null);
        final Machine other = new Machine("o", dir.resolve("o.sock"), null, null);
        final Waiting.Entry shown = entry(machine, "p", COMMIT);
        final String elsewhere = "0123456789abcdef0123456789abcdef01234567";
        final List<Waiting.Entry> others =
                List.of(
                        entry(other, "p", elsewhere),
                        new Waiting.Entry(
                                machine,
                                "other",
                                "core",
                                "u",
                                "/m",
                                new PendingPush("p", elsewhere, "s", "w", "at", "f"),
                                0),
                        new Waiting.Entry(
                                machine,
                                "porelo",
                                "other",
                                "u",
                                "/m",
                                new PendingPush("p", elsewhere, "s", "w", "at", "f"),
                                0),
                        entry(machine, "another", elsewhere));

        assertThat(new Waiting(others, List.of()).now(shown))
                .as("only others of that name wait")
                .isEqualTo(new Waiting.Now.Gone());
        assertThat(new Waiting(others, List.of(new Waiting.Silent("m", "why"))).now(shown))
                .as("only others of that name wait, its machine silent")
                .isEqualTo(new Waiting.Now.Unknown());
    }

    @Test
    @DisplayName(
            "The projects' stream says something new waits only when a repository's count of waiting pushes moved, not when anything else did")
    void pendingMovedOnlyWhenACountDid() {
        final List<Project> before = List.of(project("p", repository("a", 1), repository("b", 0)));

        assertThat(
                        Waiting.pendingMoved(
                                before,
                                List.of(project("p", repository("a", 1), repository("b", 0)))))
                .as("the same counts, another answer")
                .isFalse();
        assertThat(
                        Waiting.pendingMoved(
                                before,
                                List.of(project("p", repository("a", 1), repository("b", 1)))))
                .as("one more push in b")
                .isTrue();
        assertThat(Waiting.pendingMoved(before, List.of(project("p", repository("a", 0)))))
                .as("a decided and b gone")
                .isTrue();
        assertThat(
                        Waiting.pendingMoved(
                                before,
                                List.of(
                                        project("p", repository("a", 1), repository("b", 0)),
                                        project("q", repository("c", 1)))))
                .as("a new project with one push")
                .isTrue();
        assertThat(
                        Waiting.pendingMoved(
                                before,
                                List.of(
                                        project("p", repository("a", 1), repository("b", 0)),
                                        project("q", repository("c", 0)))))
                .as("a new project with nothing waiting")
                .isFalse();
    }

    @Test
    @DisplayName(
            "What one machine says now replaces what it said before, and nothing of the other machines changes")
    void oneMachineReadAgainReplacesOnlyItsOwn() {
        final Machine one = new Machine("one", dir.resolve("1.sock"), null, null);
        final Machine two = new Machine("two", dir.resolve("2.sock"), null, null);
        final Waiting.Entry old = at(one, "old", "2026-10-07T15:00:00Z");
        final Waiting.Entry other = at(two, "other", "2026-10-07T16:00:00Z");
        final Waiting before =
                new Waiting(List.of(old, other), List.of(new Waiting.Silent("three", "why")));
        final Waiting.Entry arrived = at(one, "arrived", "2026-10-07T17:00:00Z");

        final Waiting after = before.withMachine("one", new Waiting(List.of(arrived), List.of()));
        final Waiting silent =
                before.withMachine(
                        "one", new Waiting(List.of(), List.of(new Waiting.Silent("one", "gone"))));

        assertThat(after.pushes())
                .as("what waits after one is read again, newest first")
                .containsExactly(arrived, other);
        assertThat(after.silent())
                .extracting(Waiting.Silent::machine)
                .as("who is silent")
                .containsExactly("three");
        assertThat(silent.pushes()).as("what waits once one is silent").containsExactly(other);
        assertThat(silent.silent())
                .extracting(Waiting.Silent::machine)
                .as("who is silent then")
                .containsExactly("three", "one");
    }

    private static Waiting.Entry at(Machine machine, String name, String at) {
        return new Waiting.Entry(
                machine,
                "porelo",
                "core",
                "u",
                "/m",
                new PendingPush(name, COMMIT, "s", "w", at, "f"),
                0);
    }

    private static Project project(String name, Project.Repository... repositories) {
        return new Project(
                name,
                List.of(repositories),
                List.of(repositories).stream().mapToInt(Project.Repository::pending).sum());
    }

    private static Project.Repository repository(String name, int pending) {
        return new Project.Repository(name, false, "u", pending);
    }

    @Test
    @DisplayName(
            "Pushes are grouped by project and repository, newest group first, naming a machine only where needed")
    void groupsByProjectAndRepository() {
        final Machine one = new Machine("one", dir.resolve("1.sock"), null, null);
        final Machine two = new Machine("two", dir.resolve("2.sock"), null, null);
        final Waiting.Entry newest = entry(one, "porelo", "core", "newest");
        final Waiting.Entry docs = entry(one, "site", "site", "docs");
        final Waiting.Entry older = entry(one, "porelo", "core", "older");
        final Waiting.Entry elsewhere = entry(two, "porelo", "core", "elsewhere");

        final List<Waiting.Group> groups =
                new Waiting(List.of(newest, docs, older, elsewhere), List.of()).groups();

        assertThat(groups)
                .extracting(Waiting.Group::label)
                .as("the groups")
                .containsExactly("porelo / core on one", "site / site", "porelo / core on two");
        assertThat(groups.getFirst().pushes())
                .as("the first group's pushes, newest first")
                .containsExactly(newest, older);
    }

    private static Waiting.Entry entry(
            Machine machine, String project, String repository, String name) {
        return new Waiting.Entry(
                machine,
                project,
                repository,
                "u",
                "/m",
                new PendingPush(name, COMMIT, "s", "w", "at", "f"),
                0);
    }

    @Test
    @DisplayName(
            "A push is fetched over ssh to the tunnel's host, from the mirror's path here, or from the gate's own address")
    void saysWhereAPushIsFetchedFrom() {
        final String mirror = "/home/i/.local/share/porelo/mirrors/default/app.git";
        final PendingPush push =
                new PendingPush(
                        "app",
                        COMMIT,
                        "s",
                        "w",
                        "at",
                        "git fetch ssh://i@test-vm"
                                + mirror
                                + " refs/porelo/incoming/app:refs/remotes/porelo/app");
        final Machine tunnelled =
                new Machine("t", dir.resolve("t.sock"), "i@192.0.2.10", "/run/user/1/s.sock");
        final Machine plain = new Machine("p", dir.resolve("p.sock"), null, null);

        assertThat(
                        new Waiting.Entry(tunnelled, "default", "app", "u", mirror, push, 0)
                                .source(path -> false))
                .as("through the tunnel's host")
                .isEqualTo("ssh://i@192.0.2.10" + mirror);
        assertThat(
                        new Waiting.Entry(plain, "default", "app", "u", mirror, push, 0)
                                .source(path -> true))
                .as("this computer's mirror")
                .isEqualTo(mirror);
        assertThat(
                        new Waiting.Entry(plain, "default", "app", "u", mirror, push, 0)
                                .source(path -> false))
                .as("a forward somebody else keeps")
                .isEqualTo("ssh://i@test-vm" + mirror);
        assertThat(
                        new Waiting.Entry(
                                        Machine.wsl("w", "Ubuntu-26.04"),
                                        "default",
                                        "app",
                                        "u",
                                        mirror,
                                        push,
                                        0)
                                .source(path -> false))
                .as("a WSL distribution's mirror, as Windows reaches it")
                .isEqualTo("//wsl.localhost/Ubuntu-26.04" + mirror);
        final PendingPush odd =
                new PendingPush(
                        "app", COMMIT, "s", "w", "at", "git fetch --upload-pack=x " + mirror);
        assertThatThrownBy(
                        () ->
                                new Waiting.Entry(plain, "default", "app", "u", mirror, odd, 0)
                                        .source(path -> false))
                .as("an address that is not ssh")
                .isInstanceOf(GateException.class)
                .hasMessageContaining("names no ssh address");
    }

    @Test
    @DisplayName(
            "Grouped by machine, each machine lists its projects and repositories without naming itself again")
    void groupsByMachine() {
        final Machine one = new Machine("one", dir.resolve("1.sock"), null, null);
        final Machine two = new Machine("two", dir.resolve("2.sock"), null, null);
        final Waiting.Entry newest = entry(two, "porelo", "core", "newest");
        final Waiting.Entry docs = entry(one, "site", "site", "docs");
        final Waiting.Entry older = entry(two, "porelo", "core", "older");
        final Waiting.Entry elsewhere = entry(one, "porelo", "core", "elsewhere");

        final List<Waiting.MachineGroup> machines =
                new Waiting(List.of(newest, docs, older, elsewhere), List.of()).byMachine();

        assertThat(machines)
                .extracting(Waiting.MachineGroup::machine)
                .as("the machines, newest push first")
                .containsExactly("two", "one");
        assertThat(machines.get(0).groups())
                .extracting(Waiting.Group::label)
                .as("two's groups")
                .containsExactly("porelo / core");
        assertThat(machines.get(0).groups().getFirst().pushes())
                .as("two's pushes")
                .containsExactly(newest, older);
        assertThat(machines.get(1).groups())
                .extracting(Waiting.Group::label)
                .as("one's groups")
                .containsExactly("site / site", "porelo / core");
    }

    @Test
    @DisplayName(
            "A mirror path from the gate that would lead the fetch to another host, port or distribution is refused")
    void aMirrorThatLeadsElsewhereIsRefused() {
        final Machine tunnelled =
                new Machine(
                        "build1",
                        dir.resolve("b.sock"),
                        "i@192.0.2.10",
                        "/run/user/1001/porelo/porelod.sock");
        final Machine wsl = Machine.wsl("Ubuntu on Windows", "Ubuntu");
        final PendingPush push = new PendingPush("app", COMMIT, "s", "w", "at", "f");

        for (final String mirror : List.of("@evil.example/x", ":2222/x", "", "/a/../b")) {
            assertThatThrownBy(
                            () ->
                                    new Waiting.Entry(tunnelled, "p", "r", "u", mirror, push, 0)
                                            .source(path -> false))
                    .as("the mirror '" + mirror + "' over ssh")
                    .isInstanceOf(GateException.class);
        }
        assertThatThrownBy(
                        () ->
                                new Waiting.Entry(wsl, "p", "r", "u", "/../Other/x", push, 0)
                                        .source(path -> false))
                .as("a mirror that climbs out of its distribution")
                .isInstanceOf(GateException.class);
    }
}
