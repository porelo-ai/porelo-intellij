package org.fuin.porelo.intellij.machine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Raises tunnels with a stand-in for ssh in a process of its own, so what is asked of ssh and what
 * the plugin does with its answer are both seen.
 */
class TunnelTest {

    private static final Machine BUILD1 =
            new Machine(
                    "build 1",
                    Path.of("/run/user/1000/porelo-tunnel-build-1.sock"),
                    "dev@build1",
                    "/run/user/1001/porelo/porelod.sock");

    @TempDir Path dir;

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
            "The tunnel forwards the remote socket to the plugin's own, owner-only, and asks ssh for no prompt")
    void forwardsToThePluginsOwnSocket() throws IOException {
        try (Tunnel tunnel = Tunnel.raise(BUILD1, dir, ssh("forward"))) {
            assertThat(tunnel.socket())
                    .as("the plugin's end")
                    .isEqualTo(dir.resolve("porelo-intellij-build-1.sock"));
            assertThat(tunnel.up()).as("whether ssh runs").isTrue();
            assertThat(
                            PosixFilePermissions.toString(
                                    Files.getPosixFilePermissions(tunnel.socket())))
                    .as("who may use the socket")
                    .isEqualTo("rw-------");
            assertThat(Files.readAllLines(dir.resolve("args"), StandardCharsets.UTF_8))
                    .as("what ssh was given")
                    .containsExactly(
                            "-N",
                            "-o",
                            "BatchMode=yes",
                            "-o",
                            "ExitOnForwardFailure=yes",
                            "-o",
                            "ServerAliveInterval=15",
                            "-o",
                            "ControlMaster=no",
                            "-o",
                            "ControlPath=none",
                            "-L",
                            dir.resolve("porelo-intellij-build-1.sock")
                                    + ":/run/user/1001/porelo/porelod.sock",
                            "--",
                            "dev@build1");
        }
        assertThat(dir.resolve("porelo-intellij-build-1.sock"))
                .as("the socket once the tunnel is closed")
                .doesNotExist();
    }

    @Test
    @DisplayName("When ssh refuses, its own words are said")
    void saysWhatSshSaid() {
        assertThatThrownBy(() -> Tunnel.raise(BUILD1, dir, ssh("refuse")))
                .as("a refused host key")
                .isInstanceOf(MachinesException.class)
                .hasMessageContaining("exit code 255")
                .hasMessageContaining("Host key verification failed.");
    }

    @Test
    @DisplayName("ssh that never forwards is ended after the wait, and the socket is not left")
    void endsSshThatNeverForwards() {
        assertThatThrownBy(() -> Tunnel.raise(BUILD1, dir, ssh("hang"), Duration.ofMillis(1500)))
                .as("a tunnel that does not come up")
                .isInstanceOf(MachinesException.class)
                .hasMessageContaining(
                        "did not forward /run/user/1001/porelo/porelod.sock within 1500 ms");
        assertThat(dir.resolve("porelo-intellij-build-1.sock"))
                .as("the plugin's end")
                .doesNotExist();
    }

    @Test
    @DisplayName("Something already answering at the plugin's end is left alone, and named")
    void leavesSomethingAnsweringAlone() throws IOException {
        final Path socket = dir.resolve("porelo-intellij-build-1.sock");
        try (ServerSocketChannel other = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            other.bind(UnixDomainSocketAddress.of(socket));

            assertThatThrownBy(() -> Tunnel.raise(BUILD1, dir, ssh("forward")))
                    .as("a socket in use")
                    .isInstanceOf(MachinesException.class)
                    .hasMessageContaining("Something already answers at " + socket);
            assertThat(dir.resolve("args")).as("whether ssh was started").doesNotExist();
        }
    }

    @Test
    @DisplayName("A stale socket nothing answers at is replaced")
    void replacesAStaleSocket() throws IOException {
        Files.createFile(dir.resolve("porelo-intellij-build-1.sock"));

        try (Tunnel tunnel = Tunnel.raise(BUILD1, dir, ssh("forward"))) {
            assertThat(tunnel.up()).as("whether ssh runs").isTrue();
        }
    }

    @Test
    @DisplayName("A host ssh would read as an option is refused before ssh starts")
    void refusesAHostThatLooksLikeAnOption() {
        final Machine evil =
                new Machine("evil", Path.of("/x.sock"), "-oProxyCommand=touch /tmp/x", "/r.sock");

        assertThatThrownBy(() -> Tunnel.raise(evil, dir, ssh("forward")))
                .as("a host starting with '-'")
                .isInstanceOf(MachinesException.class)
                .hasMessageContaining("which ssh would read as an option");
        assertThat(dir.resolve("args")).as("whether ssh was started").doesNotExist();
    }
}
