package org.fuin.porelo.intellij.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.fuin.porelo.intellij.gate.Gate;
import org.fuin.porelo.intellij.gate.Project;
import org.fuin.porelo.intellij.machine.Machine;
import org.fuin.porelo.intellij.machine.WslBridge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reads a real daemon through the plugin's WSL bridge, with {@code porelo daemon connect} itself at the far end - run
 * alone, as {@code ./gradlew liveTest}, never in the build.
 * <p>
 * {@code PORELO_LIVE_WSL} is the command that stands in for {@code wsl.exe}, its words split at spaces: it answers
 * {@code -l -v} with {@code PORELO_LIVE_WSL_DISTRIBUTION} running as WSL 2, and runs what follows {@code --} on the
 * machine whose daemon has a project {@code PORELO_LIVE_PROJECT}. On Windows it is {@code wsl.exe} itself.
 */
@Tag("live")
class LiveWslBridgeTest {

    @TempDir
    Path runtime;

    private static String env(String name) {
        final String value = System.getenv(name);
        assertThat(value).as("the environment variable " + name).isNotBlank();
        return value;
    }

    @Test
    @DisplayName("A real daemon is read through the bridge and porelo daemon connect, byte for byte")
    void readsARealDaemonThroughTheBridge() {
        final Machine machine = Machine.wsl("live", env("PORELO_LIVE_WSL_DISTRIBUTION"));

        try (WslBridge bridge = WslBridge.open(machine, runtime, List.of(env("PORELO_LIVE_WSL").split(" ")));
                Gate gate = new Gate(bridge.socket())) {
            assertThat(gate.projects()).extracting(Project::name).as("the projects read through the bridge")
                    .contains(env("PORELO_LIVE_PROJECT"));
        }
    }

}
