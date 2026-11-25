package org.fuin.porelo.intellij.machine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.porelo.wire.LimitFactors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Holds the tunnels and bridges to the machines, and lets go of them with the IDE. */
class ConnectionsTest {

    private static final Machine BUILD1 =
            new Machine(
                    "build 1",
                    Path.of("/run/user/1000/porelo-tunnel-build-1.sock").toAbsolutePath(),
                    "build1",
                    "/run/user/1001/porelo/porelod.sock",
                    Path.of("/home/dev/.config/porelo/ssh/build1.conf").toAbsolutePath());

    @TempDir Path dir;

    @Test
    @DisplayName(
            "Once closed, no tunnel is raised any more, so a read or a decision that ends after the IDE closed leaves no ssh behind")
    void raisesNothingOnceClosed() {
        final String java = ProcessHandle.current().info().command().orElseThrow();
        final List<String> ssh =
                List.of(
                        java,
                        Path.of(
                                        "src/test/java",
                                        FakeSsh.class.getName().replace('.', '/') + ".java")
                                .toString(),
                        "forward",
                        dir.resolve("args").toString());
        final Connections connections = new Connections(() -> dir, ssh, List.of("wsl.exe"), false);

        connections.close();

        assertThatThrownBy(() -> connections.socketOf(BUILD1, LimitFactors.DEFAULT))
                .as("a tunnel asked for after closing")
                .isInstanceOf(MachinesException.class);
        assertThat(dir.resolve("args")).as("whether ssh was started").doesNotExist();
    }

    @Test
    @DisabledOnOs(
            value = OS.WINDOWS,
            disabledReason =
                    "a tunnel forwards to a unix socket with ssh -L, which the plugin raises on Linux only")
    @DisplayName(
            "Off Windows a server with no socket of the interface's is reached through the plugin's own tunnel, never a relay that needs porelo on the far shell's path")
    void tunnelsToAServerOffWindows() throws Exception {
        final String java = ProcessHandle.current().info().command().orElseThrow();
        final List<String> ssh =
                List.of(
                        java,
                        Path.of(
                                        "src/test/java",
                                        FakeSsh.class.getName().replace('.', '/') + ".java")
                                .toString(),
                        "forward",
                        dir.resolve("args").toString());
        final Machine server =
                Machine.overSsh(
                        "porelo-walk",
                        "porelo-walk",
                        "/run/user/1017/porelo/porelod.sock",
                        Path.of("/home/dev/.config/porelo/ssh/porelo-walk.conf").toAbsolutePath());

        try (Connections connections = new Connections(() -> dir, ssh, List.of("wsl.exe"), false)) {
            final Path socket = connections.socketOf(server, LimitFactors.DEFAULT);

            assertThat(socket)
                    .as("the plugin's own end")
                    .isEqualTo(dir.resolve("porelo-intellij-porelo-walk.sock"));
            assertThat(Files.readAllLines(dir.resolve("args"), StandardCharsets.UTF_8))
                    .as("what ssh was given: a forward to the daemon's socket, no command")
                    .contains("-N", "-L", socket + ":/run/user/1017/porelo/porelod.sock")
                    .noneMatch(arg -> arg.contains("porelo daemon connect"));
        }
    }

    @Test
    @DisplayName(
            "On Windows a server with no socket of the interface's is relayed to through porelo daemon connect, never tunnelled")
    void relaysToAServerOnWindows() {
        final String java = ProcessHandle.current().info().command().orElseThrow();
        final List<String> ssh =
                List.of(
                        java,
                        Path.of(
                                        "src/test/java",
                                        FakeSsh.class.getName().replace('.', '/') + ".java")
                                .toString(),
                        "refuse",
                        dir.resolve("args").toString());
        final Machine server =
                Machine.overSsh(
                        "porelo-walk",
                        "porelo-walk",
                        "/run/user/1017/porelo/porelod.sock",
                        Path.of("/home/dev/.config/porelo/ssh/porelo-walk.conf").toAbsolutePath());

        try (Connections connections = new Connections(() -> dir, ssh, List.of("wsl.exe"), true)) {
            assertThatThrownBy(() -> connections.socketOf(server, LimitFactors.DEFAULT))
                    .as("a server whose ssh refuses")
                    .isInstanceOf(MachinesException.class);
        }
        assertThat(dir.resolve("args"))
                .as("what ssh was given: the relay's command, no forward")
                .content(StandardCharsets.UTF_8)
                .contains("porelo daemon connect --socket")
                .doesNotContain("-L");
    }
}
