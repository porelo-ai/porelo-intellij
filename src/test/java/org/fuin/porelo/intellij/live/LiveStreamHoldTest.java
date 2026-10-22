package org.fuin.porelo.intellij.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.fuin.porelo.intellij.gate.Gate;
import org.fuin.porelo.intellij.machine.Connections;
import org.fuin.porelo.intellij.machine.Machine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds a real daemon's stream of projects through the plugin's own ssh tunnel for {@code
 * PORELO_LIVE_HOLD_MINUTES} minutes, cuts the tunnel once halfway through, and measures whether the
 * stream stays open and how fast it is open again after the cut - run by hand, never in the build.
 *
 * <p>{@code PORELO_LIVE_HOST} is ssh's target, {@code PORELO_LIVE_SOCKET} the daemon's socket
 * there, {@code PORELO_LIVE_SSH_CONFIG} the ssh configuration that reaches it, {@code
 * PORELO_LIVE_HOLD_MINUTES} the number of minutes to hold the stream, and {@code
 * PORELO_LIVE_HOLD_LOG} the file path the timestamped log is appended to.
 */
@Tag("live")
class LiveStreamHoldTest {

    @TempDir Path runtime;

    private record Reply(int stream, long nanos) {}

    private static final class State {
        final BufferedWriter writer;
        final List<Reply> replies = new CopyOnWriteArrayList<>();
        final List<String> unexpectedEnds = new CopyOnWriteArrayList<>();
        final AtomicReference<Long> cutAtNanos = new AtomicReference<>();
        final AtomicInteger cutStream = new AtomicInteger();
        final AtomicBoolean cutEnded = new AtomicBoolean();
        final AtomicBoolean stopped = new AtomicBoolean();
        final AtomicReference<Gate> currentGate = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        State(BufferedWriter writer) {
            this.writer = writer;
        }
    }

    private static String env(String name) {
        final String value = System.getenv(name);
        assertThat(value).as("the environment variable " + name).isNotBlank();
        return value;
    }

    @Test
    @DisplayName(
            "The stream of projects stays open over the ssh tunnel and reopens within a minute when the tunnel is cut")
    void holdsTheStreamAndReopensAfterACut() throws Exception {
        final int minutes = Integer.parseInt(env("PORELO_LIVE_HOLD_MINUTES"));
        assertThat(minutes)
                .as(
                        "PORELO_LIVE_HOLD_MINUTES (must be at least 3 so more than the 60 s allowed for a reply remain after the cut at half time)")
                .isGreaterThanOrEqualTo(3);
        final Machine machine =
                new Machine(
                        "live",
                        runtime.resolve("unused.sock"),
                        env("PORELO_LIVE_HOST"),
                        env("PORELO_LIVE_SOCKET"));
        final List<String> ssh = List.of("ssh", "-F", env("PORELO_LIVE_SSH_CONFIG"));
        final List<String> wsl = List.of("wsl.exe");
        final Duration hold = Duration.ofMinutes(minutes);
        final Path logFile = Path.of(env("PORELO_LIVE_HOLD_LOG"));
        if (logFile.getParent() != null) {
            Files.createDirectories(logFile.getParent());
        }

        final long startNanos = System.nanoTime();
        final long halfNanos = startNanos + hold.dividedBy(2).toNanos();
        final long deadlineNanos = startNanos + hold.toNanos();
        final State state;

        try (BufferedWriter writer =
                Files.newBufferedWriter(
                        logFile,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND)) {
            state = new State(writer);
            try (Connections connections = new Connections(() -> runtime, ssh, wsl)) {
                final Thread watcher =
                        Thread.ofVirtual()
                                .start(
                                        () ->
                                                holdStream(
                                                        connections,
                                                        machine,
                                                        deadlineNanos,
                                                        state));
                final boolean joined;
                try {
                    sleepUntil(halfNanos);
                    assertThat(state.failure.get())
                            .as("unexpected failure in the stream watcher: %s", state.failure.get())
                            .isNull();
                    assertThat(state.replies)
                            .as("replies received before cutting the tunnel at half time")
                            .isNotEmpty();
                    final List<ProcessHandle> tunnels =
                            ProcessHandle.current()
                                    .descendants()
                                    .filter(
                                            process ->
                                                    process.info()
                                                            .commandLine()
                                                            .map(
                                                                    line ->
                                                                            line.contains("ssh")
                                                                                    && line
                                                                                            .contains(
                                                                                                    "-L"))
                                                            .orElse(false))
                                    .toList();
                    assertThat(tunnels)
                            .as("ssh tunnel processes under this JVM to cut")
                            .isNotEmpty();
                    final List<Long> pids = tunnels.stream().map(ProcessHandle::pid).toList();
                    log(writer, "cut " + pids);
                    state.cutAtNanos.set(System.nanoTime());
                    tunnels.forEach(ProcessHandle::destroyForcibly);

                    sleepUntil(deadlineNanos);
                } finally {
                    state.stopped.set(true);
                    final Gate open = state.currentGate.getAndSet(null);
                    if (open != null) {
                        closeQuietly(open);
                    }
                    watcher.interrupt();
                    joined = watcher.join(Duration.ofSeconds(10));
                }
                assertThat(joined)
                        .as("stream watcher thread finished within 10 s of stopping")
                        .isTrue();
            }
            log(writer, "done");
        }

        assertThat(state.failure.get())
                .as("unexpected failure in the stream watcher: %s", state.failure.get())
                .isNull();
        assertThat(state.replies).as("replies received from the stream").isNotEmpty();
        assertThat(Duration.ofNanos(state.replies.getFirst().nanos() - startNanos))
                .as("time from the start to the first reply")
                .isLessThanOrEqualTo(Duration.ofSeconds(30));

        assertThat(state.cutEnded.get()).as("the cut ended a stream").isTrue();
        final long cutNanos = state.cutAtNanos.get();
        final Reply replyAfterCut = replyAfterCut(state);
        assertThat(replyAfterCut)
                .as(
                        "a reply received on a stream opened after the cut's end was recorded (unexpected ends: %s)",
                        state.unexpectedEnds)
                .isNotNull();
        assertThat(Duration.ofNanos(replyAfterCut.nanos() - cutNanos))
                .as(
                        "time from the cut to the next reply (the minute's read is the plugin's fallback, so a reopen must be faster)")
                .isLessThanOrEqualTo(Duration.ofSeconds(60));

        assertThat(state.unexpectedEnds)
                .as(
                        "every end not caused by the cut (the stream must stay open for the whole hold otherwise): %s",
                        state.unexpectedEnds)
                .isEmpty();
    }

    private static void holdStream(
            Connections connections, Machine machine, long deadlineNanos, State state) {
        try {
            int stream = 0;
            while (!state.stopped.get() && System.nanoTime() - deadlineNanos < 0) {
                Gate gate = null;
                final int currentStream = ++stream;
                try {
                    gate = new Gate(connections.socketOf(machine));
                    state.currentGate.set(gate);
                    if (state.stopped.get()) {
                        break;
                    }
                    log(state.writer, "open");
                    gate.watch(
                            projects -> {
                                log(state.writer, "reply " + projects.size() + " projects");
                                state.replies.add(new Reply(currentStream, System.nanoTime()));
                            });
                    if (state.stopped.get()) {
                        break;
                    }
                    recordEnd(state, currentStream, true, "end return: stream ended");
                } catch (RuntimeException ex) {
                    if (state.stopped.get()) {
                        break;
                    }
                    recordEnd(
                            state,
                            currentStream,
                            gate != null,
                            "end " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                } finally {
                    state.currentGate.set(null);
                    if (gate != null) {
                        closeQuietly(gate);
                    }
                }
                try {
                    // Wait as long as PoreloWatch.REOPEN_SECONDS before reopening.
                    Thread.sleep(Duration.ofSeconds(10));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (Throwable t) {
            state.failure.set(t);
        }
    }

    private static void recordEnd(State state, int stream, boolean opened, String event) {
        final long endNanos = System.nanoTime();
        final String line = log(state.writer, event);
        final Long cutNanos = state.cutAtNanos.get();
        final boolean afterCut = cutNanos != null && endNanos >= cutNanos;
        if (afterCut && opened && state.cutEnded.compareAndSet(false, true)) {
            state.cutStream.set(stream);
        } else if (!afterCut || opened || replyAfterCut(state) != null) {
            state.unexpectedEnds.add(line);
        }
    }

    private static Reply replyAfterCut(State state) {
        if (!state.cutEnded.get()) {
            return null;
        }
        final int cutStream = state.cutStream.get();
        return state.replies.stream()
                .filter(reply -> reply.stream() > cutStream)
                .findFirst()
                .orElse(null);
    }

    private static String log(BufferedWriter writer, String event) {
        synchronized (writer) {
            final String line = Instant.now() + " " + event;
            try {
                writer.write(line);
                writer.newLine();
                writer.flush();
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
            return line;
        }
    }

    private static void sleepUntil(long targetNanos) throws InterruptedException {
        final Duration remaining = Duration.ofNanos(targetNanos - System.nanoTime());
        if (remaining.isPositive()) {
            Thread.sleep(remaining);
        }
    }

    // Closing a broken connection when ending or reopening the stream is expected to fail at times.
    private static void closeQuietly(Gate gate) {
        try {
            gate.close();
        } catch (RuntimeException ex) {
            // See above.
        }
    }
}
