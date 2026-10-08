package org.fuin.sokar.intellij.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import org.fuin.sokar.intellij.gate.Gate;
import org.fuin.sokar.intellij.gate.MovedSinceReviewException;
import org.fuin.sokar.intellij.gate.Review;
import org.fuin.sokar.intellij.gate.Waiting;
import org.fuin.sokar.intellij.machine.Machine;
import org.fuin.sokar.intellij.machine.Tunnel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the plugin's client against a real daemon on a machine somebody keeps, through a real ssh tunnel - run
 * alone, as {@code ./gradlew liveTest}, never in the build.
 * <p>
 * The machine has a task {@code SOKAR_LIVE_TASK} that pushed one commit, {@code SOKAR_LIVE_COMMIT}, changing at
 * least one file the daemon ranks as far-reaching - a workflow, a build file or IDE settings - and one that it does not. {@code SOKAR_LIVE_HOST} is ssh's target, {@code SOKAR_LIVE_SOCKET} the daemon's socket there, and
 * {@code SOKAR_LIVE_SSH_CONFIG} the ssh configuration that reaches it.
 */
@Tag("live")
class LiveGateTest {

    @TempDir
    Path runtime;

    private static String env(String name) {
        final String value = System.getenv(name);
        assertThat(value).as("the environment variable " + name).isNotBlank();
        return value;
    }

    @Test
    @DisplayName("What a real task pushed is seen through the tunnel, ranked, and a stale commit is not approved")
    void readsARealGate() {
        final Machine machine = new Machine("live", Path.of("/nowhere.sock"), env("SOKAR_LIVE_HOST"),
                env("SOKAR_LIVE_SOCKET"));
        final String task = env("SOKAR_LIVE_TASK");
        final String commit = env("SOKAR_LIVE_COMMIT");

        try (Tunnel tunnel = Tunnel.raise(machine, runtime, List.of("ssh", "-F", env("SOKAR_LIVE_SSH_CONFIG")))) {
            final Waiting waiting = Waiting.read(List.of(machine), any -> new Gate(tunnel.socket()));

            assertThat(waiting.silent()).as("machines that did not answer").isEmpty();
            final Waiting.Entry entry = waiting.pushes().stream().filter(push -> push.push().name().equals(task))
                    .findFirst().orElseThrow(() -> new AssertionError("no push of " + task + " in " + waiting));
            assertThat(entry.push().commit()).as("the commit that waits").isEqualTo(commit);
            assertThat(entry.farReaching()).as("far-reaching files").isPositive();

            try (Gate gate = new Gate(tunnel.socket())) {
                final Review review = gate.review(entry.project(), entry.repository(), task);
                assertThat(review.files()).as("what it changes").hasSizeGreaterThan(entry.farReaching());
                assertThat(review.files().getFirst().farReaching()).as("whether a far-reaching file comes first")
                        .isTrue();

                assertThatThrownBy(() -> gate.approve(entry.project(), entry.repository(), task, "main",
                        "0000000000000000000000000000000000000000")).as("approving a commit that was not shown")
                        .isInstanceOfSatisfying(MovedSinceReviewException.class,
                                moved -> assertThat(moved.now()).as("what waits now").isEqualTo(commit));
            }
        }
    }

}
