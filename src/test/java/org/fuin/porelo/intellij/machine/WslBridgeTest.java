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
import org.junit.jupiter.api.io.TempDir;

/**
 * Opens bridges to a WSL distribution with a stand-in for {@code wsl.exe} in a process of its own,
 * which relays to a stand-in daemon on a real unix socket, so what is asked of {@code wsl.exe} and
 * what the plugin reads through it are both seen.
 */
class WslBridgeTest {

    private static final Machine UBUNTU = Machine.wsl("Ubuntu on Windows", "Ubuntu");

    @TempDir Path dir;

    private VarlinkServer daemon;

    @BeforeEach
    void startTheDaemon() throws IOException {
        daemon = new VarlinkServer(dir.resolve("porelod.sock"), Gate.INTERFACE);
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
                                                        1,
                                                        "tasks",
                                                        1,
                                                        "running",
                                                        1,
                                                        "prepared",
                                                        true,
                                                        "preparedState",
                                                        "READY",
                                                        "repositories",
                                                        List.of(
                                                                Map.of(
                                                                        "name",
                                                                        "app",
                                                                        "own",
                                                                        true,
                                                                        "upstream",
                                                                        "/u/app",
                                                                        "mirror",
                                                                        "/m/app",
                                                                        "pending",
                                                                        1)))))));
        Thread.ofVirtual().start(daemon);
        Files.writeString(
                dir.resolve("distributions"),
                "* Ubuntu    Running         2\r\n"
                        + "  Debian    Stopped         2\r\n  Old       Running         1\r\n",
                StandardCharsets.UTF_8);
    }

    @AfterEach
    void stopTheDaemon() throws Exception {
        daemon.close();
    }

    private List<String> wsl(Path daemonSocket) {
        final String java = ProcessHandle.current().info().command().orElseThrow();
        // Its source file, which java runs as it is: the test's class path is longer than one
        // command line may be.
        return List.of(
                java,
                Path.of("src/test/java", FakeWsl.class.getName().replace('.', '/') + ".java")
                        .toString(),
                dir.resolve("distributions").toString(),
                daemonSocket.toString(),
                dir.resolve("args").toString());
    }

    @Test
    @DisplayName(
            "A running WSL 2 distribution's daemon is read through the bridge, with wsl.exe asked for no shell")
    void readsTheDaemonThroughWsl() throws IOException {
        try (WslBridge bridge = WslBridge.open(UBUNTU, dir, wsl(daemon.socketPath()));
                Gate gate = new Gate(bridge.socket())) {
            assertThat(bridge.socket())
                    .as("the plugin's end")
                    .isEqualTo(dir.resolve("porelo-intellij-Ubuntu-on-Windows.sock"));
            assertThat(gate.projects())
                    .extracting(Project::name)
                    .as("the projects read through wsl.exe")
                    .containsExactly("default");
        }
        assertThat(Files.readAllLines(dir.resolve("args"), StandardCharsets.UTF_8))
                .as(
                        "what wsl.exe was given, in"
                                + " turn: the list, the probe and the gate's connection")
                .containsExactly(
                        "-l", "-v", "-d", "Ubuntu", "--exec", "porelo", "daemon", "connect", "-d",
                        "Ubuntu", "--exec", "porelo", "daemon", "connect");
        assertThat(dir.resolve("porelo-intellij-Ubuntu-on-Windows.sock"))
                .as("the socket once the bridge is closed")
                .doesNotExist();
    }

    @Test
    @DisplayName(
            "Closing the bridge ends every wsl.exe it started for a connection still open, within seconds")
    void closingEndsTheRelaysOfOpenConnections() throws IOException {
        final WslBridge bridge = WslBridge.open(UBUNTU, dir, wsl(daemon.socketPath()));
        try (Gate gate = new Gate(bridge.socket())) {
            gate.projects();
            final List<ProcessHandle> relays =
                    ProcessHandle.current()
                            .descendants()
                            .filter(
                                    handle ->
                                            handle.info()
                                                    .commandLine()
                                                    .orElse("")
                                                    .contains("FakeWsl"))
                            .toList();
            assertThat(relays).as("the wsl.exe of the open connection").hasSize(1);

            final long started = System.nanoTime();
            bridge.close();

            assertThat(relays.getFirst().onExit())
                    .as("the relay once the bridge is closed")
                    .succeedsWithin(java.time.Duration.ofSeconds(10));
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
                    .as("how long closing took")
                    .isLessThan(java.time.Duration.ofSeconds(10));
        }
    }

    @Test
    @DisplayName(
            "A stopped distribution is never started: it is refused, and wsl.exe is not asked to run anything")
    void neverStartsAStoppedDistribution() throws IOException {
        assertThatThrownBy(
                        () ->
                                WslBridge.open(
                                        Machine.wsl("Debian here", "Debian"),
                                        dir,
                                        wsl(daemon.socketPath())))
                .as("a stopped distribution")
                .isInstanceOf(MachinesException.class)
                .hasMessage(
                        "'Debian here': the WSL distribution 'Debian' is stopped, and the plugin never starts one;"
                                + " start it, then it is read again");
        assertThat(Files.readAllLines(dir.resolve("args"), StandardCharsets.UTF_8))
                .as("what wsl.exe was given")
                .containsExactly("-l", "-v");
    }

    @Test
    @DisplayName("A WSL 1 distribution and one this computer does not have are refused in words")
    void refusesWsl1AndAMissingDistribution() {
        assertThatThrownBy(
                        () ->
                                WslBridge.open(
                                        Machine.wsl("old", "Old"), dir, wsl(daemon.socketPath())))
                .as("a WSL 1 distribution")
                .isInstanceOf(MachinesException.class)
                .hasMessageContaining("Porelo needs WSL 2 (wsl --set-version Old 2)");
        assertThatThrownBy(
                        () ->
                                WslBridge.open(
                                        Machine.wsl("gone", "Fedora"),
                                        dir,
                                        wsl(daemon.socketPath())))
                .as("a distribution that is not there")
                .isInstanceOf(MachinesException.class)
                .hasMessage("'gone': this computer has no WSL distribution 'Fedora'");
    }

    @Test
    @DisplayName("A distribution whose daemon does not answer is refused with what wsl.exe said")
    void saysWhatWslSaidWhenTheDaemonDoesNotAnswer() {
        assertThatThrownBy(() -> WslBridge.open(UBUNTU, dir, wsl(dir.resolve("nothing-here.sock"))))
                .as("a daemon that does not answer")
                .isInstanceOf(MachinesException.class)
                .hasMessageStartingWith(
                        "Porelo's daemon in 'Ubuntu on Windows' does not answer through -d Ubuntu --exec"
                                + " porelo daemon connect (exit code 69): porelo: no daemon answers at "
                                + dir.resolve("nothing-here.sock"));
        assertThat(dir.resolve("porelo-intellij-Ubuntu-on-Windows.sock"))
                .as("the plugin's end")
                .doesNotExist();
    }

    @Test
    @DisplayName(
            "wsl.exe's list is read in UTF-16LE as it prints it by default, and in UTF-8 with WSL_UTF8 set")
    void readsTheListInBothEncodings() {
        final String table =
                "﻿  NAME      STATE           VERSION\r\n* Ubuntu-26.04    Running         2\r\n";

        assertThat(WslBridge.distributions(table.getBytes(StandardCharsets.UTF_16LE)))
                .as("in UTF-16LE")
                .singleElement()
                .isEqualTo(new String[] {"Ubuntu-26.04", "Running", "2"});
        assertThat(WslBridge.distributions(table.getBytes(StandardCharsets.UTF_8)))
                .as("in UTF-8")
                .singleElement()
                .isEqualTo(new String[] {"Ubuntu-26.04", "Running", "2"});
    }
}
