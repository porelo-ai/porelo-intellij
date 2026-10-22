package org.fuin.porelo.intellij.machine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.fuin.porelo.intellij.gate.Gate;
import org.fuin.porelo.intellij.gate.Project;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reads a stand-in daemon through the relay over Windows' own {@code ssh.exe} to an OpenSSH server
 * on the same Windows, whose {@code porelo} is {@link FakePorelo} - run alone, as {@code ./gradlew
 * windowsSshTest} in the Windows job, never in the build.
 *
 * <p>{@code PORELO_WINDOWS_SSH_HOST} is a host ssh reaches with the key in Windows' agent, {@code
 * PORELO_WINDOWS_SSH_STRANGER} one it reaches with no key it may use, and {@code
 * PORELO_WINDOWS_SSH} the ssh to run, Windows' own unless it is set.
 */
@Tag("windows-ssh")
class WindowsSshRelayTest {

    private static final String REMOTE_SOCKET = "/run/user/1000/porelo/porelod.sock";

    @TempDir Path dir;

    private static String env(String name) {
        final String value = System.getenv(name);
        assertThat(value).as("the environment variable " + name).isNotBlank();
        return value;
    }

    private static List<String> ssh() {
        final String set = System.getenv("PORELO_WINDOWS_SSH");
        return List.of(
                set != null && !set.isBlank()
                        ? set
                        : new Machines(
                                        System.getenv(),
                                        Path.of(System.getProperty("user.home")),
                                        true)
                                .ssh());
    }

    @Test
    @DisplayName(
            "A machine over ssh is read through Windows' own ssh.exe, the daemon's NUL bytes unchanged")
    void readsTheDaemonThroughWindowsOwnSsh() {
        final Machine machine =
                Machine.overSsh("over ssh", env("PORELO_WINDOWS_SSH_HOST"), REMOTE_SOCKET);

        try (Relay relay = Relay.overSsh(machine, dir, ssh());
                Gate gate = new Gate(relay.socket())) {
            assertThat(gate.projects())
                    .extracting(Project::name)
                    .as("the projects read over ssh")
                    .containsExactly(FakePorelo.PROJECT);
        }
    }

    @Test
    @DisplayName(
            "A machine whose key is not in the agent is not reached, in ssh's words, and nothing waits for an answer")
    void aMachineWithoutAKeyIsNotReached() {
        final Machine machine =
                Machine.overSsh("no key", env("PORELO_WINDOWS_SSH_STRANGER"), REMOTE_SOCKET);

        assertThatThrownBy(() -> Relay.overSsh(machine, dir, ssh()))
                .as("a machine ssh has no key for")
                .isInstanceOf(MachinesException.class)
                .hasMessageContaining("does not answer through ssh to")
                .hasMessageContaining("Permission denied");
    }
}
