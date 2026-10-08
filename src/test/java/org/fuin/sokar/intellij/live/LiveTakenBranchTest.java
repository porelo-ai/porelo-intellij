package org.fuin.sokar.intellij.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.fuin.sokar.intellij.gate.BranchExistsException;
import org.fuin.sokar.intellij.gate.Gate;
import org.fuin.sokar.intellij.gate.Waiting;
import org.fuin.sokar.intellij.machine.Machine;
import org.fuin.sokar.intellij.machine.Tunnel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Approves a push onto branches that already hold other work, against a real daemon on a machine somebody keeps,
 * through a real ssh tunnel - run alone, as {@code ./gradlew liveTest}, never in the build. It forwards the push.
 * <p>
 * The machine has a task {@code SOKAR_LIVE_TASK} whose push {@code SOKAR_LIVE_COMMIT} waits, and its upstream holds
 * {@code sokar/<task>} with work the push did not grow from, possibly {@code -2} and on too; {@code
 * SOKAR_LIVE_FREE_BRANCH} names the first free one, which the push ends on. {@code SOKAR_LIVE_HOST}, {@code
 * SOKAR_LIVE_SOCKET} and {@code SOKAR_LIVE_SSH_CONFIG} reach the machine, as for {@link LiveGateTest}.
 */
@Tag("live")
class LiveTakenBranchTest {

    @TempDir
    Path runtime;

    private static String env(String name) {
        final String value = System.getenv(name);
        assertThat(value).as("the environment variable " + name).isNotBlank();
        return value;
    }

    @Test
    @DisplayName("A taken branch is refused in words with the next name, and the push goes onto the first free one")
    void aTakenBranchIsSaidAndTheNextNameTaken() {
        final Machine machine = new Machine("live", Path.of("/nowhere.sock"), env("SOKAR_LIVE_HOST"),
                env("SOKAR_LIVE_SOCKET"));
        final String task = env("SOKAR_LIVE_TASK");
        final String commit = env("SOKAR_LIVE_COMMIT");
        final String free = env("SOKAR_LIVE_FREE_BRANCH");

        try (Tunnel tunnel = Tunnel.raise(machine, runtime, List.of("ssh", "-F", env("SOKAR_LIVE_SSH_CONFIG")));
                Gate gate = new Gate(tunnel.socket())) {
            final Waiting.Entry entry = Waiting.read(List.of(machine), any -> new Gate(tunnel.socket())).pushes()
                    .stream().filter(push -> push.push().name().equals(task)).findFirst()
                    .orElseThrow(() -> new AssertionError("no push of " + task));
            final List<String> refused = new ArrayList<>();
            String branch = "sokar/" + task;
            String forwarded = null;
            for (int tries = 0; tries < 5 && forwarded == null; tries++) {
                try {
                    forwarded = gate.approve(entry.project(), entry.repository(), task, branch, commit);
                } catch (BranchExistsException taken) {
                    refused.add(taken.getMessage());
                    branch = taken.next();
                }
            }

            assertThat(refused).as("the refusals, in words").isNotEmpty().allSatisfy(said -> assertThat(said)
                    .matches("sokar/" + task + "(-[0-9]+)? already holds [0-9a-f]+, from earlier work\\. Nothing was"
                            + " forwarded\\."));
            assertThat(forwarded).as("what was forwarded, and where").isEqualTo(task + " onto " + free);
        }
    }

}
