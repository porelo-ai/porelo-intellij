package org.fuin.porelo.intellij.machine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Holds the tunnels and bridges to the machines, and lets go of them with the IDE. */
class ConnectionsTest {

    private static final Machine BUILD1 =
            new Machine(
                    "build 1",
                    Path.of("/run/user/1000/porelo-tunnel-build-1.sock"),
                    "dev@build1",
                    "/run/user/1001/porelo/porelod.sock");

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
        final Connections connections = new Connections(() -> dir, ssh, List.of("wsl.exe"));

        connections.close();

        assertThatThrownBy(() -> connections.socketOf(BUILD1))
                .as("a tunnel asked for after closing")
                .isInstanceOf(MachinesException.class);
        assertThat(dir.resolve("args")).as("whether ssh was started").doesNotExist();
    }
}
