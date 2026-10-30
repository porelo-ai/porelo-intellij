package org.fuin.porelo.intellij.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.intellij.cache.ReviewCache;
import org.fuin.porelo.intellij.gate.Gate;
import org.fuin.porelo.intellij.gate.Waiting;
import org.fuin.porelo.intellij.machine.Machine;
import org.fuin.porelo.intellij.machine.Tunnel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fetches what a real task pushed into a repository of the person's with uncommitted work, from the
 * real gate's mirror over ssh - run alone, as {@code ./gradlew liveTest}, never in the build.
 *
 * <p>The machine is the one {@code LiveGateTest} names, with the same environment: a task {@code
 * PORELO_LIVE_TASK} whose push {@code PORELO_LIVE_COMMIT} waits, the daemon's socket {@code
 * PORELO_LIVE_SOCKET} on ssh's target {@code PORELO_LIVE_HOST}, reached with the ssh configuration
 * {@code PORELO_LIVE_SSH_CONFIG}.
 */
@Tag("live")
class LiveIntoProjectTest {

    @TempDir Path dir;

    private static String env(String name) {
        final String value = System.getenv(name);
        assertThat(value).as("the environment variable " + name).isNotBlank();
        return value;
    }

    @Test
    @DisplayName(
            "A real push fetched into a repository with uncommitted work is reachable as porelo/<push>, the work as it was, and goes once decided")
    void aRealPushIsFetchedIntoAProject() throws IOException {
        final Machine machine =
                new Machine(
                        "live",
                        Path.of("/nowhere.sock"),
                        env("PORELO_LIVE_HOST"),
                        env("PORELO_LIVE_SOCKET"),
                        Path.of(env("PORELO_LIVE_SSH_CONFIG")));
        final String task = env("PORELO_LIVE_TASK");
        final String commit = env("PORELO_LIVE_COMMIT");
        final Waiting.Entry entry;
        try (Tunnel tunnel = Tunnel.raise(machine, dir, List.of("ssh"))) {
            final Waiting waiting =
                    Waiting.read(List.of(machine), any -> new Gate(tunnel.socket()));
            entry =
                    waiting.pushes().stream()
                            .filter(push -> push.push().name().equals(task))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new AssertionError(
                                                    "no push of " + task + " in " + waiting));
        }
        final Path person = dir.resolve("person");
        git(dir, "init", "-q", person.toString());
        Files.writeString(person.resolve("mine.txt"), "committed\n", StandardCharsets.UTF_8);
        git(person, "add", "mine.txt");
        git(person, "commit", "-q", "-m", "Mine");
        Files.writeString(person.resolve("mine.txt"), "changed, not committed\n");
        Files.writeString(person.resolve("new.txt"), "not added\n");
        final String head = git(person, "rev-parse", "HEAD");
        final String status = git(person, "status", "--porcelain=v1", "--untracked-files=all");
        final ReviewCache cache = new ReviewCache(dir.resolve("cache"), Map.of());

        cache.fetchInto(
                person, entry.source(path -> false), entry.machine().sshConfig(), task, commit);

        assertThat(git(person, "rev-parse", "refs/remotes/porelo/" + task))
                .as("the push's ref")
                .isEqualTo(commit);
        assertThat(git(person, "rev-parse", "HEAD")).as("HEAD").isEqualTo(head);
        assertThat(git(person, "status", "--porcelain=v1", "--untracked-files=all"))
                .as("the uncommitted work")
                .isEqualTo(status);

        cache.forget(person, task);

        assertThat(git(person, "for-each-ref", "refs/remotes/porelo/"))
                .as("refs under porelo/ once decided")
                .isEmpty();
    }

    private static String git(Path in, String... arguments) throws IOException {
        final List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        final ProcessBuilder builder =
                new ProcessBuilder(command).directory(in.toFile()).redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_AUTHOR_NAME", "a");
        builder.environment().put("GIT_AUTHOR_EMAIL", "a@x");
        builder.environment().put("GIT_COMMITTER_NAME", "a");
        builder.environment().put("GIT_COMMITTER_EMAIL", "a@x");
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
