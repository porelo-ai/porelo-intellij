package org.fuin.porelo.intellij.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fetches from a bare mirror built as a gate's is - its source's branches under {@code refs/heads}, its HEAD naming
 * the default branch, a waiting push under {@code refs/porelo/incoming} - with git and nothing of this machine's
 * configuration.
 */
class ReviewCacheTest {

    private static final Map<String, String> NO_CONFIG = Map.of("GIT_CONFIG_GLOBAL", "/dev/null",
            "GIT_CONFIG_NOSYSTEM", "1", "GIT_AUTHOR_NAME", "a", "GIT_AUTHOR_EMAIL", "a@x", "GIT_COMMITTER_NAME", "a",
            "GIT_COMMITTER_EMAIL", "a@x");

    @TempDir
    Path dir;

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
    @DisplayName("A push is read against where it and the mirror's default branch last met, so the branch's later work is not shown as removed")
    void readsAPushAgainstWhereItStarted() {
        final Changes changes = cache.fetch(cache.repository("m", "p", "r"), mirror.toString(), "app", pushed);

        assertThat(changes.branch()).as("the branch the mirror's HEAD names").isEqualTo("trunk");
        assertThat(changes.base()).as("where the push and the branch last met").isEqualTo(started)
                .isNotEqualTo(movedOn);
        assertThat(changes.files()).as("what the push changes, and nothing the branch did later").containsExactly(
                new Changes.Change("M", "notes.txt"), new Changes.Change("A", "src/A.java"));
    }

    @Test
    @DisplayName("A file is read from the cache as each side has it, and a file one side lacks is absent")
    void readsBothSidesOfAFile() {
        final Path repository = cache.repository("m", "p", "r");
        final Changes changes = cache.fetch(repository, mirror.toString(), "app", pushed);

        assertThat(text(cache.file(repository, changes.base(), "notes.txt"))).as("the base's notes")
                .isEqualTo("hello\n");
        assertThat(text(cache.file(repository, pushed, "notes.txt"))).as("the push's notes")
                .isEqualTo("hello again\n");
        assertThat(cache.file(repository, changes.base(), "src/A.java")).as("a file the base lacks").isNull();
    }

    @Test
    @DisplayName("The cache is bare: nothing is checked out")
    void nothingIsCheckedOut() throws IOException {
        final Path repository = cache.repository("m", "p", "r");
        cache.fetch(repository, mirror.toString(), "app", pushed);

        assertThat(git(dir, "--git-dir", repository.toString(), "rev-parse", "--is-bare-repository"))
                .as("whether the cache is bare").isEqualTo("true");
        try (var entries = Files.list(repository)) {
            assertThat(entries.map(path -> path.getFileName().toString()).toList()).as("what the cache holds")
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

        assertThatThrownBy(() -> cache.fetch(cache.repository("m", "p", "r"), mirror.toString(), "app", pushed))
                .as("a push that moved").isInstanceOf(CacheException.class)
                .hasMessageContaining("app now holds " + now + ", not " + pushed);
    }

    @Test
    @DisplayName("A name or a commit git could misread is refused before git runs")
    void refusesWhatGitCouldMisread() {
        final Path repository = cache.repository("m", "p", "r");

        assertThatThrownBy(() -> cache.fetch(repository, mirror.toString(), "--upload-pack=touch x", pushed))
                .as("an option as a name").isInstanceOf(CacheException.class).hasMessageContaining("not a name");
        assertThatThrownBy(() -> cache.fetch(repository, mirror.toString(), "app", "HEAD"))
                .as("a ref as a commit").isInstanceOf(CacheException.class).hasMessageContaining("not a full commit");
        assertThat(repository).as("the cache, untouched").doesNotExist();
    }

    @Test
    @DisplayName("Each gate has a repository of its own, its names made safe as a path")
    void eachGateHasItsOwnRepository() {
        assertThat(cache.repository("walk 9", "../p", "r")).as("the repository of a gate")
                .isEqualTo(dir.resolve("cache/walk-9/---p/r.git"));
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
        final ProcessBuilder builder = new ProcessBuilder(command).directory(in.toFile()).redirectErrorStream(true);
        builder.environment().putAll(NO_CONFIG);
        final Process git = builder.start();
        final String out = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
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
