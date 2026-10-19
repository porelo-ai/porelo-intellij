package org.fuin.porelo.intellij.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.fuin.porelo.intellij.gate.Gate;
import org.fuin.porelo.intellij.gate.Project;
import org.fuin.porelo.intellij.gate.Waiting;
import org.fuin.porelo.intellij.machine.Machine;
import org.fuin.porelo.intellij.machine.Tunnel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Times a real push to the moment the plugin knows it waits, through a real daemon's stream of
 * projects and a real ssh tunnel - run alone, as {@code ./gradlew liveTest}, never in the build.
 *
 * <p>The machine is the one {@code LiveGateTest} names, with the same {@code PORELO_LIVE_HOST},
 * {@code PORELO_LIVE_SOCKET} and {@code PORELO_LIVE_SSH_CONFIG}. {@code PORELO_LIVE_PUSH} is a
 * shell command, run here, that makes a task there push so that a repository's count of waiting
 * pushes moves: a task's first push, not one that only moves a task already waiting to another
 * commit, which the daemon's list of projects does not show.
 */
@Tag("live")
class LiveNoticeTest {

    /** What the acceptance allows from a push to its notice. */
    private static final Duration NOTICE = Duration.ofSeconds(5);

    @TempDir Path dir;

    private static String env(String name) {
        final String value = System.getenv(name);
        assertThat(value).as("the environment variable " + name).isNotBlank();
        return value;
    }

    @Test
    @DisplayName(
            "A real push is known to wait within 5 s through the daemon's stream, and with the stream cut a plain read still finds it")
    void aRealPushIsKnownWithinSeconds() throws Exception {
        final Machine machine =
                new Machine(
                        "live",
                        Path.of("/nowhere.sock"),
                        env("PORELO_LIVE_HOST"),
                        env("PORELO_LIVE_SOCKET"));
        try (Tunnel tunnel =
                Tunnel.raise(machine, dir, List.of("ssh", "-F", env("PORELO_LIVE_SSH_CONFIG")))) {
            final Waiting before = Waiting.read(List.of(machine), any -> new Gate(tunnel.socket()));
            final AtomicReference<List<Project>> first = new AtomicReference<>();
            final AtomicReference<Long> moved = new AtomicReference<>();
            final Gate watching = new Gate(tunnel.socket());
            final Thread stream =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try {
                                            watching.watch(
                                                    projects -> {
                                                        if (first.compareAndSet(null, projects)) {
                                                            return;
                                                        }
                                                        if (moved.get() == null
                                                                && Waiting.pendingMoved(
                                                                        first.get(), projects)) {
                                                            moved.set(System.nanoTime());
                                                        }
                                                    });
                                        } catch (RuntimeException ex) {
                                            // Closed below, which ends the stream.
                                        }
                                    });
            waitFor(() -> first.get() != null, Duration.ofSeconds(30), "the stream's first answer");

            push(env("PORELO_LIVE_PUSH"));
            final long pushed = System.nanoTime();
            waitFor(() -> moved.get() != null, Duration.ofSeconds(30), "the push in the stream");
            final Waiting after =
                    before.withMachine(
                            "live",
                            Waiting.read(
                                    List.of(machine), any -> new Gate(tunnel.socket()), before));
            final Duration known = Duration.ofNanos(System.nanoTime() - pushed);

            assertThat(after.newSince(before)).as("what is new once the stream said so").hasSize(1);
            assertThat(known)
                    .as(
                            "from the push to knowing what it is (stream %s ms)",
                            TimeUnit.NANOSECONDS.toMillis(moved.get() - pushed))
                    .isLessThanOrEqualTo(NOTICE);

            watching.close();
            stream.join(Duration.ofSeconds(10));
            assertThat(Waiting.read(List.of(machine), any -> new Gate(tunnel.socket())).pushes())
                    .as("what a plain read finds with the stream cut")
                    .containsAll(after.newSince(before));
        }
    }

    private static void push(String command) throws IOException, InterruptedException {
        final Process shell =
                new ProcessBuilder("bash", "-c", command).redirectErrorStream(true).start();
        final String said = new String(shell.getInputStream().readAllBytes());
        assertThat(shell.waitFor()).as("the push command, which said: " + said).isZero();
    }

    private static void waitFor(
            java.util.function.BooleanSupplier done, Duration limit, String what)
            throws InterruptedException {
        final long until = System.nanoTime() + limit.toNanos();
        while (!done.getAsBoolean()) {
            assertThat(System.nanoTime()).as("waiting for " + what).isLessThan(until);
            Thread.sleep(20);
        }
    }
}
