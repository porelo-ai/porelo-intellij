package org.fuin.porelo.intellij.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fetches from a bare mirror built as a gate's is - its source's branches under {@code refs/heads},
 * its HEAD naming the default branch, a waiting push under {@code refs/porelo/incoming} - with git
 * and nothing of this machine's configuration.
 */
class ReviewCacheTest {

    private static final Map<String, String> NO_CONFIG =
            Map.of(
                    "GIT_CONFIG_GLOBAL",
                    "/dev/null",
                    "GIT_CONFIG_NOSYSTEM",
                    "1",
                    "GIT_AUTHOR_NAME",
                    "a",
                    "GIT_AUTHOR_EMAIL",
                    "a@x",
                    "GIT_COMMITTER_NAME",
                    "a",
                    "GIT_COMMITTER_EMAIL",
                    "a@x");

    /** An ssh that never answers: git runs it with the host and its command appended. */
    private static final String SILENT_SSH = "sleep 600; :";

    @TempDir Path dir;

    private Path work;

    private Path mirror;

    private String started;

    private String pushed;

    private String movedOn;

    private ReviewCache cache;

    @BeforeEach
    void buildAMirror() throws IOException {
        work = dir.resolve("work");
        mirror = dir.resolve("mirror.git");
        git(dir, "init", "-q", "-b", "trunk", work.toString());
        write("notes.txt", "hello\n");
        write("build.gradle", "plugins {}\n");
        git(work, "add", "-A");
        git(work, "commit", "-q", "-m", "Start");
        started = git(work, "rev-parse", "HEAD");
        git(dir, "clone", "-q", "--bare", work.toString(), mirror.toString());
        // The task's work, which starts at 'started'.
        git(work, "checkout", "-q", "-b", "task");
        write("notes.txt", "hello again\n");
        write("src/A.java", "class A {}\n");
        git(work, "add", "-A");
        git(work, "commit", "-q", "-m", "Task");
        pushed = git(work, "rev-parse", "HEAD");
        git(work, "push", "-q", mirror.toString(), "HEAD:refs/porelo/incoming/app");
        // The branch moves on after the task started; its change is not the task's.
        git(work, "checkout", "-q", "trunk");
        write("build.gradle", "plugins { java }\n");
        git(work, "commit", "-q", "-am", "Later");
        movedOn = git(work, "rev-parse", "HEAD");
        git(work, "push", "-q", mirror.toString(), "trunk:refs/heads/trunk");
        cache = new ReviewCache(dir.resolve("cache"), NO_CONFIG);
    }

    @Test
    @DisplayName(
            "A push is read against where it and the mirror's default branch last met, so the branch's later work is not shown as removed")
    void readsAPushAgainstWhereItStarted() {
        final Changes changes =
                cache.fetch(
                        cache.repository("m", "p", "r"), mirror.toString(), null, "app", pushed);

        assertThat(changes.branch()).as("the branch the mirror's HEAD names").isEqualTo("trunk");
        assertThat(changes.base())
                .as("where the push and the branch last met")
                .isEqualTo(started)
                .isNotEqualTo(movedOn);
        assertThat(changes.files())
                .as("what the push changes, and nothing the branch did later")
                .containsExactly(
                        new Changes.Change("M", "notes.txt"),
                        new Changes.Change("A", "src/A.java"));
    }

    @Test
    @DisplayName(
            "A file is read from the cache as each side has it, and a file one side lacks is absent")
    void readsBothSidesOfAFile() {
        final Path repository = cache.repository("m", "p", "r");
        final Changes changes = cache.fetch(repository, mirror.toString(), null, "app", pushed);

        assertThat(text(cache.file(repository, changes.base(), "notes.txt")))
                .as("the base's notes")
                .isEqualTo("hello\n");
        assertThat(text(cache.file(repository, pushed, "notes.txt")))
                .as("the push's notes")
                .isEqualTo("hello again\n");
        assertThat(cache.file(repository, changes.base(), "src/A.java"))
                .as("a file the base lacks")
                .isNull();
    }

    @Test
    @DisplayName("The cache is bare: nothing is checked out")
    void nothingIsCheckedOut() throws IOException {
        final Path repository = cache.repository("m", "p", "r");
        cache.fetch(repository, mirror.toString(), null, "app", pushed);

        assertThat(
                        git(
                                dir,
                                "--git-dir",
                                repository.toString(),
                                "rev-parse",
                                "--is-bare-repository"))
                .as("whether the cache is bare")
                .isEqualTo("true");
        try (var entries = Files.list(repository)) {
            assertThat(entries.map(path -> path.getFileName().toString()).toList())
                    .as("what the cache holds")
                    .doesNotContain("notes.txt", "src", "build.gradle");
        }
    }

    @Test
    @DisplayName("A push that no longer holds the commit shown is refused, naming both")
    void refusesAPushThatMoved() throws IOException {
        git(work, "checkout", "-q", "task");
        write("notes.txt", "and again\n");
        git(work, "commit", "-q", "-am", "More");
        final String now = git(work, "rev-parse", "HEAD");
        git(work, "push", "-q", "-f", mirror.toString(), "HEAD:refs/porelo/incoming/app");

        assertThatThrownBy(
                        () ->
                                cache.fetch(
                                        cache.repository("m", "p", "r"),
                                        mirror.toString(),
                                        null,
                                        "app",
                                        pushed))
                .as("a push that moved")
                .isInstanceOf(CacheException.class)
                .hasMessageContaining("app now holds " + now + ", not " + pushed);
    }

    @Test
    @DisplayName("A name or a commit git could misread is refused before git runs")
    void refusesWhatGitCouldMisread() {
        final Path repository = cache.repository("m", "p", "r");

        assertThatThrownBy(
                        () ->
                                cache.fetch(
                                        repository,
                                        mirror.toString(),
                                        null,
                                        "--upload-pack=touch x",
                                        pushed))
                .as("an option as a name")
                .isInstanceOf(CacheException.class)
                .hasMessageContaining("not a name");
        assertThatThrownBy(() -> cache.fetch(repository, mirror.toString(), null, "app", "HEAD"))
                .as("a ref as a commit")
                .isInstanceOf(CacheException.class)
                .hasMessageContaining("not a full commit");
        assertThat(repository).as("the cache, untouched").doesNotExist();
    }

    @Test
    @DisplayName("Each gate has a repository of its own, its names made safe as a path")
    void eachGateHasItsOwnRepository() {
        assertThat(cache.repository("walk 9", "../p", "r"))
                .as("the repository of a gate")
                .isEqualTo(dir.resolve("cache/walk-9/---p/r.git"));
    }

    @Test
    @DisplayName(
            "A push fetched into a person's repository is reachable as porelo/<push>, and HEAD, the index, the working tree and every other ref are as they were, uncommitted changes included")
    void aPushFetchedIntoAProjectTouchesNothingElse() throws IOException {
        final Path person = personsRepository();
        final String head = git(person, "rev-parse", "HEAD");
        final String status = git(person, "status", "--porcelain=v1", "--untracked-files=all");
        final String staged = git(person, "diff", "--cached");
        final String unstaged = git(person, "diff");
        final String refs = git(person, "for-each-ref", "--format=%(refname) %(objectname)");

        cache.fetchInto(person, mirror.toString(), null, "app", pushed);

        assertThat(git(person, "rev-parse", "refs/remotes/porelo/app"))
                .as("the push's ref in the person's repository")
                .isEqualTo(pushed);
        assertThat(git(person, "rev-parse", "HEAD")).as("HEAD").isEqualTo(head);
        assertThat(git(person, "status", "--porcelain=v1", "--untracked-files=all"))
                .as("the working tree's state")
                .isEqualTo(status);
        assertThat(git(person, "diff", "--cached")).as("the index").isEqualTo(staged);
        assertThat(git(person, "diff")).as("the uncommitted changes").isEqualTo(unstaged);
        assertThat(git(person, "for-each-ref", "--format=%(refname) %(objectname)"))
                .as("every ref but the push's")
                .isEqualTo(refs + "\nrefs/remotes/porelo/app " + pushed);
    }

    @Test
    @DisplayName(
            "A push that no longer holds the commit shown is refused when fetched into a person's repository, and leaves no ref there")
    void aMovedPushLeavesNoRefInAProject() throws IOException {
        final Path person = personsRepository();
        git(work, "checkout", "-q", "task");
        write("notes.txt", "and again\n");
        git(work, "commit", "-q", "-am", "More");
        final String now = git(work, "rev-parse", "HEAD");
        git(work, "push", "-q", "-f", mirror.toString(), "HEAD:refs/porelo/incoming/app");

        assertThatThrownBy(() -> cache.fetchInto(person, mirror.toString(), null, "app", pushed))
                .as("a push that moved")
                .isInstanceOf(CacheException.class)
                .hasMessageContaining("app now holds " + now + ", not " + pushed);
        assertThat(git(person, "for-each-ref", "refs/remotes/porelo/"))
                .as("refs under porelo/")
                .isEmpty();
    }

    @Test
    @DisplayName(
            "A decided push's ref is deleted from a person's repository, and nothing else under porelo/ is")
    void aDecidedPushsRefGoes() throws IOException {
        final Path person = personsRepository();
        git(person, "update-ref", "refs/remotes/porelo/app", pushed);
        git(person, "update-ref", "refs/remotes/porelo/other", pushed);

        cache.forget(person, "app");
        cache.forget(person, "app");

        assertThat(git(person, "for-each-ref", "--format=%(refname)", "refs/remotes/porelo/"))
                .as("refs under porelo/ after forgetting app twice")
                .isEqualTo("refs/remotes/porelo/other");
    }

    @Test
    @DisplayName(
            "A person's repository's remotes are read as git config prints them, and a repository with none has none")
    void readsTheRemotesOfAPersonsRepository() throws IOException {
        final Path person = personsRepository();
        git(person, "remote", "add", "far", "git@example.org:x.git");
        final Path bare = dir.resolve("lonely");
        git(dir, "init", "-q", bare.toString());

        assertThat(cache.remoteUrls(person))
                .as("the remotes of a clone with one added")
                .isEqualTo(
                        "remote.origin.url " + mirror + "\nremote.far.url git@example.org:x.git\n");
        assertThat(cache.remoteUrls(bare)).as("the remotes of a repository with none").isEmpty();
    }

    /** A clone of the mirror's source with work of the person's: staged, unstaged and new. */
    private Path personsRepository() throws IOException {
        final Path person = dir.resolve("person");
        git(dir, "clone", "-q", mirror.toString(), person.toString());
        Files.writeString(person.resolve("notes.txt"), "mine\n", StandardCharsets.UTF_8);
        Files.writeString(person.resolve("build.gradle"), "plugins { id 'x' }\n");
        git(person, "add", "build.gradle");
        Files.writeString(person.resolve("scratch.txt"), "not added\n");
        return person;
    }

    @AfterEach
    void endWhatAFailedTestLeft() {
        silentSsh().forEach(ProcessHandle::destroyForcibly);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @DisplayName(
            "A fetch from a machine that never answers ends within its limit, and leaves no git or ssh running, so a review cannot hang for good")
    void aFetchThatHangsEndsWithinItsLimit() throws InterruptedException {
        final ReviewCache slow =
                new ReviewCache(dir.resolve("slow"), silent(), Duration.ofSeconds(2));

        assertTimeoutPreemptively(
                Duration.ofSeconds(30),
                () ->
                        assertThatThrownBy(
                                        () ->
                                                slow.fetch(
                                                        slow.repository("m", "p", "r"),
                                                        "ssh://example.invalid/x",
                                                        null,
                                                        "app",
                                                        pushed))
                                .as("a fetch that never gets an answer")
                                .isInstanceOf(CacheException.class)
                                .hasMessageContaining("did not end within 2 s"));
        assertThat(ended()).as("the git and ssh it started, ended").isTrue();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @DisplayName(
            "An interrupted fetch ends at once and leaves no git or ssh running, so closing a project ends its review's work")
    void anInterruptedFetchLeavesNothingRunning() throws InterruptedException {
        final ReviewCache slow =
                new ReviewCache(dir.resolve("slow"), silent(), Duration.ofSeconds(120));
        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        final Thread review =
                Thread.ofPlatform()
                        .start(
                                () -> {
                                    try {
                                        slow.fetch(
                                                slow.repository("m", "p", "r"),
                                                "ssh://example.invalid/x",
                                                null,
                                                "app",
                                                pushed);
                                    } catch (RuntimeException ex) {
                                        thrown.set(ex);
                                    }
                                });
        while (silentSsh().isEmpty() && review.isAlive()) {
            Thread.sleep(50);
        }

        review.interrupt();
        review.join(10_000);

        assertThat(review.isAlive()).as("whether the fetch still runs").isFalse();
        assertThat(thrown.get())
                .as("what the fetch threw")
                .isInstanceOf(CacheException.class)
                .hasMessageContaining("Interrupted");
        assertThat(ended()).as("the git and ssh it started, ended").isTrue();
    }

    /**
     * An ssh that serves the mirror as a host would, running git's command here, but only when it
     * is given the host's own configuration; any other ssh stands for a machine that is not the
     * host.
     */
    private Path hostsSsh(Path config) throws IOException {
        final Path ssh = Files.createDirectories(dir.resolve("host")).resolve("ssh");
        Files.writeString(
                ssh,
                "#!/bin/sh\n"
                        + "seen=\n"
                        + "while [ $# -gt 1 ]; do\n"
                        + "  [ \"$1\" = -F ] && [ \"$2\" = '"
                        + config
                        + "' ] && seen=1\n"
                        + "  shift\n"
                        + "done\n"
                        + "[ -n \"$seen\" ] || exit 255\n"
                        + "exec sh -c \"$1\"\n");
        Files.setPosixFilePermissions(ssh, PosixFilePermissions.fromString("rwx------"));
        return ssh;
    }

    /** The person's own ssh, which leads somewhere else, and says when it is run. */
    private Path personsSsh(Path used) throws IOException {
        final Path ssh = Files.createDirectories(dir.resolve("persons-ssh")).resolve("ssh");
        Files.writeString(ssh, "#!/bin/sh\necho \"$@\" >> '" + used + "'\nexit 255\n");
        Files.setPosixFilePermissions(ssh, PosixFilePermissions.fromString("rwx------"));
        return ssh;
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @DisplayName(
            "A host reached through its ssh configuration is fetched from through that alone, whichever way the person named an ssh of their own")
    void aHostIsReachedThroughItsSshConfigurationAlone() throws IOException {
        final Path config = dir.resolve("config/porelo/ssh/build1.conf");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "Host build1\n");
        final Path used = dir.resolve("used");
        final Path persons = personsSsh(used);
        final Path global = dir.resolve("gitconfig");
        Files.writeString(global, "[core]\n\tsshCommand = '" + persons + "'\n");
        final String url = "ssh://build1" + mirror;
        final Path person = personsRepository();
        final Map<String, Map<String, String>> ways =
                Map.of(
                        "GIT_SSH_COMMAND", Map.of("GIT_SSH_COMMAND", "'" + persons + "'"),
                        "GIT_SSH", Map.of("GIT_SSH", persons.toString()),
                        "core.sshCommand", Map.of("GIT_CONFIG_GLOBAL", global.toString()));

        for (final var way : ways.entrySet()) {
            final Map<String, String> environment = new HashMap<>(NO_CONFIG);
            environment.putAll(way.getValue());
            final ReviewCache viaHost =
                    new ReviewCache(
                            dir.resolve("cache-" + way.getKey()),
                            environment,
                            hostsSsh(config).toString());

            final Changes changes =
                    viaHost.fetch(viaHost.repository("m", "p", "r"), url, config, "app", pushed);
            viaHost.fetchInto(person, url, config, "app", pushed);

            assertThat(changes.commit())
                    .as("the push fetched from the host, the person's ssh set by " + way.getKey())
                    .isEqualTo(pushed);
            assertThat(git(person, "rev-parse", "refs/remotes/porelo/app"))
                    .as("the push fetched into the person's repository, by " + way.getKey())
                    .isEqualTo(pushed);
            assertThat(used).as("the person's own ssh, set by " + way.getKey()).doesNotExist();
            git(person, "update-ref", "-d", "refs/remotes/porelo/app");
        }
    }

    @Test
    @DisplayName(
            "A relative ssh configuration is refused before git runs, so it never names a file in the person's repository")
    void aRelativeSshConfigurationIsRefused() throws IOException {
        final Path person = personsRepository();
        final Path relative = Path.of("build1.conf");
        final String url = "ssh://build1" + mirror;

        assertThatThrownBy(
                        () ->
                                cache.fetch(
                                        cache.repository("m", "p", "r"),
                                        url,
                                        relative,
                                        "app",
                                        pushed))
                .as("a fetch with a relative ssh configuration")
                .isInstanceOf(CacheException.class)
                .hasMessageContaining("no absolute path");
        assertThatThrownBy(() -> cache.fetchInto(person, url, relative, "app", pushed))
                .as("a fetch into the person's repository with a relative ssh configuration")
                .isInstanceOf(CacheException.class)
                .hasMessageContaining("no absolute path");
        assertThat(cache.repository("m", "p", "r"))
                .as("the cache's repository, which git would have made")
                .doesNotExist();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @DisplayName(
            "ssh is told never to ask, so a fetch whose host or key would need an answer fails instead of waiting at a terminal")
    void sshIsNeverLeftToAsk() throws IOException {
        final Path bin = Files.createDirectories(dir.resolve("bin"));
        final Path asked = dir.resolve("asked");
        final Path ssh = bin.resolve("ssh");
        Files.writeString(ssh, "#!/bin/sh\necho \"$@\" > '" + asked + "'\nexit 255\n");
        Files.setPosixFilePermissions(ssh, PosixFilePermissions.fromString("rwx------"));
        final Map<String, String> environment = new HashMap<>(NO_CONFIG);
        environment.put("PATH", bin + ":" + System.getenv("PATH"));
        final ReviewCache viaSsh = new ReviewCache(dir.resolve("ssh"), environment);

        assertThatThrownBy(
                        () ->
                                viaSsh.fetch(
                                        viaSsh.repository("m", "p", "r"),
                                        "ssh://example.invalid/x",
                                        null,
                                        "app",
                                        pushed))
                .as("a fetch over an ssh that refuses")
                .isInstanceOf(CacheException.class);
        assertThat(Files.readString(asked)).as("what ssh was given").contains("BatchMode=yes");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @DisplayName(
            "The ssh the cache is given is the one git runs, from a path with a space and a quote, never one the path finds")
    void fetchesOverTheSshItIsGiven() throws IOException {
        final Path bin = Files.createDirectories(dir.resolve("bin"));
        final Path asked = dir.resolve("asked");
        final Path found = bin.resolve("ssh");
        Files.writeString(found, "#!/bin/sh\necho found > '" + asked + "'\nexit 255\n");
        Files.setPosixFilePermissions(found, PosixFilePermissions.fromString("rwx------"));
        final Path given = Files.createDirectories(dir.resolve("Open SSH's")).resolve("ssh.exe");
        Files.writeString(given, "#!/bin/sh\necho given \"$@\" > '" + asked + "'\nexit 255\n");
        Files.setPosixFilePermissions(given, PosixFilePermissions.fromString("rwx------"));
        final Map<String, String> environment = new HashMap<>(NO_CONFIG);
        environment.put("PATH", bin + ":" + System.getenv("PATH"));
        final ReviewCache viaSsh =
                new ReviewCache(dir.resolve("ssh"), environment, given.toString());

        assertThatThrownBy(
                        () ->
                                viaSsh.fetch(
                                        viaSsh.repository("m", "p", "r"),
                                        "ssh://example.invalid/x",
                                        null,
                                        "app",
                                        pushed))
                .as("a fetch over an ssh that refuses")
                .isInstanceOf(CacheException.class);
        assertThat(Files.readString(asked))
                .as("which ssh git ran, and what it was given")
                .startsWith("given ")
                .contains("BatchMode=yes");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @DisplayName(
            "ssh is told never to ask when a remote of the person's is fetched by its name, which says nothing of ssh")
    void sshIsNeverLeftToAskForARemoteByName() throws IOException {
        final Path person = personsRepository();
        git(person, "remote", "add", "far", "git@example.invalid:x.git");
        final Path bin = Files.createDirectories(dir.resolve("bin"));
        final Path asked = dir.resolve("asked");
        final Path ssh = bin.resolve("ssh");
        Files.writeString(ssh, "#!/bin/sh\necho \"$@\" > '" + asked + "'\nexit 255\n");
        Files.setPosixFilePermissions(ssh, PosixFilePermissions.fromString("rwx------"));
        final Map<String, String> environment = new HashMap<>(NO_CONFIG);
        environment.put("PATH", bin + ":" + System.getenv("PATH"));
        final ReviewCache viaSsh = new ReviewCache(dir.resolve("ssh"), environment);

        assertThatThrownBy(() -> viaSsh.fetchRemote(person, "far"))
                .as("a remote fetched over an ssh that refuses")
                .isInstanceOf(CacheException.class)
                .hasMessageContaining("git fetch");
        assertThat(Files.readString(asked)).as("what ssh was given").contains("BatchMode=yes");
    }

    @Test
    @DisplayName(
            "A file of a cache git cannot read is refused, not read as absent, so a review never shows a changed file as added or deleted")
    void aFileOfABrokenCacheIsRefused() {
        assertThatThrownBy(() -> cache.file(dir.resolve("nope.git"), pushed, "notes.txt"))
                .as("a file read from a repository that is not there")
                .isInstanceOf(CacheException.class);
    }

    @Test
    @DisplayName(
            "Two reviews of one push opened at once both read it, as their fetches into one cache never run together")
    void twoReviewsAtOnceBothRead() throws InterruptedException {
        for (int round = 0; round < 8; round++) {
            final Path repository = cache.repository("m", "p", "r" + round);
            final CountDownLatch start = new CountDownLatch(1);
            final List<Throwable> failed = new java.util.concurrent.CopyOnWriteArrayList<>();
            final List<Thread> reviews = new ArrayList<>();
            for (int each = 0; each < 2; each++) {
                reviews.add(
                        Thread.ofPlatform()
                                .start(
                                        () -> {
                                            try {
                                                start.await();
                                                cache.fetch(
                                                        repository,
                                                        mirror.toString(),
                                                        null,
                                                        "app",
                                                        pushed);
                                            } catch (InterruptedException | RuntimeException ex) {
                                                failed.add(ex);
                                            }
                                        }));
            }
            start.countDown();
            for (final Thread review : reviews) {
                review.join(60_000);
            }

            assertThat(failed).as("the reviews that failed in round " + round).isEmpty();
        }
    }

    private static Map<String, String> silent() {
        final Map<String, String> environment = new HashMap<>(NO_CONFIG);
        environment.put("GIT_SSH_COMMAND", SILENT_SSH);
        return environment;
    }

    private static List<ProcessHandle> silentSsh() {
        return ProcessHandle.current()
                .descendants()
                .filter(process -> process.info().commandLine().orElse("").contains("sleep 600"))
                .toList();
    }

    private static boolean ended() throws InterruptedException {
        for (int tries = 0; tries < 100 && !silentSsh().isEmpty(); tries++) {
            Thread.sleep(100);
        }
        return silentSsh().isEmpty();
    }

    private void write(String path, String text) throws IOException {
        final Path file = work.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String git(Path in, String... arguments) throws IOException {
        final List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        final ProcessBuilder builder =
                new ProcessBuilder(command).directory(in.toFile()).redirectErrorStream(true);
        builder.environment().putAll(NO_CONFIG);
        final Process git = builder.start();
        final String out =
                new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        try {
            if (git.waitFor() != 0) {
                throw new IllegalStateException("git " + command + " failed: " + out);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
        return out;
    }
}
