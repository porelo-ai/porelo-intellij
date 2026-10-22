package org.fuin.porelo.intellij.machine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.intellij.gate.Gate;
import org.fuin.porelo.intellij.gate.Project;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Opens relays over ssh with a stand-in for {@code ssh} in a process of its own, which reads the
 * remote command as a shell would and joins it to a stand-in daemon on a real unix socket, so what
 * is asked of ssh and what the plugin reads through it are both seen.
 */
@DisabledOnOs(
        value = OS.WINDOWS,
        disabledReason =
                "its stand-in daemon, porelo-wire's VarlinkServer, makes its socket owner-only with POSIX permissions, which Windows has not")
class RelayTest {

    @TempDir Path dir;

    private VarlinkServer daemon;

    private Path daemonSocket;

    @BeforeEach
    void startTheDaemon() throws IOException {
        daemonSocket = dir.resolve("porelod.sock");
        daemon = new VarlinkServer(daemonSocket, Gate.INTERFACE);
        daemon.method(
                "Projects",
                (parameters, replies) ->
                        replies.last(
                                Map.of(
                                        "projects",
                                        List.of(
                                                Map.of(
                                                        "name",
                                                        "default",
                                                        "securityClass",
                                                        "guarded",
                                                        "file",
                                                        "/home/a/.local/state/porelo/default/project.yml",
                                                        "mirror",
                                                        "",
                                                        "pending",
                                                        0,
                                                        "tasks",
                                                        0,
                                                        "running",
                                                        0,
                                                        "prepared",
                                                        true,
                                                        "preparedState",
                                                        "READY",
                                                        "repositories",
                                                        List.of())))));
        Thread.ofVirtual().start(daemon);
    }

    @AfterEach
    void stopTheDaemon() throws Exception {
        daemon.close();
    }

    private List<String> ssh(String does) {
        final String java = ProcessHandle.current().info().command().orElseThrow();
        // Its source file, which java runs as it is: the test's class path is longer than one
        // command line may be.
        return List.of(
                java,
                Path.of("src/test/java", FakeSsh.class.getName().replace('.', '/') + ".java")
                        .toString(),
                does,
                dir.resolve("args").toString());
    }

    @Test
    @DisplayName(
            "A machine over ssh is read through the relay, ssh asked for no prompt and the remote socket quoted as one word")
    void readsTheDaemonOverSsh() throws IOException {
        final Machine build1 = Machine.overSsh("build 1", "dev@build1", daemonSocket.toString());

        try (Relay relay = Relay.overSsh(build1, dir, ssh("connect"));
                Gate gate = new Gate(relay.socket())) {
            assertThat(relay.socket())
                    .as("the plugin's end")
                    .isEqualTo(dir.resolve("porelo-intellij-build-1.sock"));
            assertThat(gate.projects())
                    .extracting(Project::name)
                    .as("the projects read over ssh")
                    .containsExactly("default");
        }
        final List<String> once =
                List.of(
                        "-T",
                        "-o",
                        "BatchMode=yes",
                        "-o",
                        "ServerAliveInterval=15",
                        "-o",
                        "ControlMaster=no",
                        "-o",
                        "ControlPath=none",
                        "--",
                        "dev@build1",
                        "porelo daemon connect --socket '" + dir + "/porelod.sock'");
        assertThat(Files.readAllLines(dir.resolve("args"), StandardCharsets.UTF_8))
                .as("what ssh was given, in turn: the probe and the gate's connection")
                .containsExactlyElementsOf(
                        java.util.stream.Stream.concat(once.stream(), once.stream()).toList());
        assertThat(dir.resolve("porelo-intellij-build-1.sock"))
                .as("the socket once the relay is closed")
                .doesNotExist();
    }

    @Test
    @DisplayName(
            "A machine ssh cannot reach is refused in ssh's own words, and no end of the plugin's is left")
    void saysWhatSshSaidWhenItCannotReach() {
        final Machine build1 = Machine.overSsh("build 1", "dev@build1", daemonSocket.toString());

        assertThatThrownBy(() -> Relay.overSsh(build1, dir, ssh("refuse")))
                .as("a host whose key ssh does not know")
                .isInstanceOf(MachinesException.class)
                .hasMessage(
                        "Porelo's daemon in 'build 1' does not answer through ssh to dev@build1"
                                + " (exit code 255): Host key verification failed.");
        assertThat(dir.resolve("porelo-intellij-build-1.sock"))
                .as("the plugin's end")
                .doesNotExist();
    }

    @Test
    @DisplayName(
            "A remote socket that is not plain path characters is refused before ssh runs, as no remote shell may read it as a command")
    void refusesARemoteSocketAShellCouldReadAsACommand() {
        // Single quotes hold under a POSIX shell, but fish ends them at \', and Windows' own
        // quoting
        // may not keep a " in one argument.
        for (final String remote :
                List.of(
                        "/run/a\\' ; id #",
                        "/run/a\" && id #",
                        "/run/a;id",
                        "/run/a b",
                        "/run/$(id)")) {
            assertThatThrownBy(
                            () ->
                                    Relay.overSsh(
                                            Machine.overSsh("sly", "dev@build1", remote),
                                            dir,
                                            ssh("connect")))
                    .as("the remote socket " + remote)
                    .isInstanceOf(MachinesException.class)
                    .hasMessageContaining("which the remote shell could read as more than a path");
        }
        assertThat(dir.resolve("args")).as("whether ssh was started").doesNotExist();
    }

    @Test
    @DisplayName("A host that ssh would read as one of its options is refused before ssh runs")
    void refusesAHostThatReadsAsAnOption() {
        final Machine sly = Machine.overSsh("sly", "-oProxyCommand=touch", daemonSocket.toString());

        assertThatThrownBy(() -> Relay.overSsh(sly, dir, ssh("connect")))
                .as("a host that starts with '-'")
                .isInstanceOf(MachinesException.class)
                .hasMessageContaining("which ssh would read as an option");
        assertThat(dir.resolve("args")).as("whether ssh was started").doesNotExist();
    }
}
