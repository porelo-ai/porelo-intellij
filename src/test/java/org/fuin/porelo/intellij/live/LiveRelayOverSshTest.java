package org.fuin.porelo.intellij.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.fuin.porelo.intellij.gate.Gate;
import org.fuin.porelo.intellij.gate.Project;
import org.fuin.porelo.intellij.machine.Machine;
import org.fuin.porelo.intellij.machine.Relay;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reads a real daemon through the relay over ssh, as the plugin reaches a machine from Windows,
 * with the machine's own shell and {@code porelo daemon connect --socket} at the far end - run
 * alone, as {@code ./gradlew liveTest}, never in the build.
 *
 * <p>The machine is the one {@code LiveGateTest} names, with the same {@code PORELO_LIVE_HOST},
 * {@code PORELO_LIVE_SOCKET} and {@code PORELO_LIVE_SSH_CONFIG}; its daemon has a project {@code
 * PORELO_LIVE_PROJECT}.
 */
@Tag("live")
class LiveRelayOverSshTest {

    @TempDir Path runtime;

    private static String env(String name) {
        final String value = System.getenv(name);
        assertThat(value).as("the environment variable " + name).isNotBlank();
        return value;
    }

    @Test
    @DisplayName(
            "A real daemon is read through the relay over ssh, its socket named to porelo daemon connect through the machine's shell")
    void readsARealDaemonOverSsh() {
        final Machine machine =
                Machine.overSsh(
                        "live",
                        env("PORELO_LIVE_HOST"),
                        env("PORELO_LIVE_SOCKET"),
                        Path.of(env("PORELO_LIVE_SSH_CONFIG")));

        try (Relay relay = Relay.overSsh(machine, runtime, List.of("ssh"));
                Gate gate = new Gate(relay.socket())) {
            assertThat(gate.projects())
                    .extracting(Project::name)
                    .as("the projects read over ssh")
                    .contains(env("PORELO_LIVE_PROJECT"));
        }
    }
}
