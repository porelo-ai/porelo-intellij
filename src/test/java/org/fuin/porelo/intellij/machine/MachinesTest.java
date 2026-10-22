package org.fuin.porelo.intellij.machine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Reads the machines from a file shaped as the interface writes it. */
class MachinesTest {

    @TempDir Path dir;

    private Machines machines() {
        return new Machines(
                Map.of(
                        "XDG_CONFIG_HOME",
                        dir.resolve("config").toString(),
                        "XDG_RUNTIME_DIR",
                        dir.resolve("run").toString()),
                dir.resolve("home"));
    }

    private void write(String json) throws IOException {
        final Path file = dir.resolve("config/porelo/frontend.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    @Test
    @DisabledOnOs(
            value = OS.WINDOWS,
            disabledReason =
                    "it reads the interface's file as Linux writes it, with Linux sockets, which are no paths on Windows")
    @DisplayName(
            "A machine with a host and a remote socket is reached through a tunnel, one without is opened as it is")
    void readsBothKindsOfMachine() throws IOException {
        write(
                """
                {"refreshSeconds": 60, "muted": [],
                 "machines": [
                   {"name": "build1", "socket": "/run/user/1000/porelo-tunnel-build1.sock",
                    "host": "dev@build1", "remoteSocket": "/run/user/1001/porelo/porelod.sock"},
                   {"name": "forwarded", "socket": "/tmp/somebody-elses.sock"}
                 ]}
                """);

        final List<Machine> all = machines().all();

        assertThat(all)
                .as("the interface's machines in its order, then this computer's daemon")
                .containsExactly(
                        new Machine(
                                "build1",
                                Path.of("/run/user/1000/porelo-tunnel-build1.sock"),
                                "dev@build1",
                                "/run/user/1001/porelo/porelod.sock"),
                        new Machine("forwarded", Path.of("/tmp/somebody-elses.sock"), null, null),
                        new Machine(
                                Machines.THIS_COMPUTER,
                                dir.resolve("run/porelo/porelod.sock"),
                                null,
                                null));
        assertThat(all)
                .extracting(Machine::needsATunnel)
                .as("which of them needs a tunnel")
                .containsExactly(true, false, false);
    }

    @Test
    @DisplayName("This computer's daemon is not listed twice when the interface names it")
    void doesNotListThisComputerTwice() throws IOException {
        write(
                "{\"machines\": [{\"name\": \"here\", \"socket\": \""
                        + dir.resolve("run/porelo/porelod.sock").toString().replace("\\", "\\\\")
                        + "\"}]}");

        assertThat(machines().all())
                .extracting(Machine::name)
                .as("the machines")
                .containsExactly("here");
    }

    @Test
    @DisplayName("Without the interface's file, this computer's daemon is the one machine")
    void withoutTheFileThisComputerIsReached() {
        assertThat(machines().all())
                .as("the machines")
                .containsExactly(
                        new Machine(
                                Machines.THIS_COMPUTER,
                                dir.resolve("run/porelo/porelod.sock"),
                                null,
                                null));
    }

    @Test
    @DisplayName("An entry without a name or a socket is dropped, as the interface drops it")
    void dropsAnEntryWithoutANameOrASocket() throws IOException {
        write(
                "{\"machines\": [{\"name\": \"\", \"socket\": \"/a.sock\"}, {\"name\": \"b\"}, {\"name\": 3, \"socket\": \"/c.sock\"}]}");

        assertThat(machines().all())
                .extracting(Machine::name)
                .as("the machines")
                .containsExactly(Machines.THIS_COMPUTER);
    }

    @Test
    @DisabledOnOs(
            value = OS.WINDOWS,
            disabledReason =
                    "it reads the interface's file as Linux writes it, with Linux sockets, which are no paths on Windows")
    @DisplayName(
            "A WSL entry and one of an unknown kind are kept as not reachable, never opened as a path")
    void keepsEntriesOfAKindItDoesNotSpeak() throws IOException {
        write(
                """
                {"machines": [
                   {"name": "Ubuntu on Windows", "kind": "wsl", "distribution": "Ubuntu"},
                   {"name": "later", "kind": "tcp", "socket": "/tmp/would-be-opened.sock"},
                   {"name": "forwarded", "socket": "/tmp/somebody-elses.sock"}
                 ]}
                """);

        final Machines.Listed listed = machines().read();

        assertThat(listed.machines())
                .extracting(Machine::name)
                .as("the machines the plugin reaches")
                .containsExactly("forwarded", Machines.THIS_COMPUTER);
        assertThat(listed.unreachable())
                .as("what it keeps but does not reach, in the file's order")
                .containsExactly(
                        new Machines.Unreachable(
                                "Ubuntu on Windows",
                                "is a WSL distribution ('Ubuntu'), which this plugin does not reach yet"),
                        new Machines.Unreachable(
                                "later", "is of a kind this plugin does not know ('tcp')"));
    }

    @Test
    @DisplayName(
            "On Windows the file is under APPDATA, a WSL entry and an ssh entry are reached, and no Linux socket is")
    void onWindowsAWslEntryAndAnSshEntryAreReached() throws IOException {
        final Path file = dir.resolve("roaming/porelo/frontend.json");
        Files.createDirectories(file.getParent());
        Files.writeString(
                file,
                """
                {"machines": [
                   {"name": "Ubuntu on Windows", "kind": "wsl", "distribution": "Ubuntu"},
                   {"name": "build1", "socket": "C:\\\\Users\\\\a\\\\t.sock", "host": "a@build1",
                    "remoteSocket": "/run/user/1001/porelo/porelod.sock"},
                   {"name": "carried over", "socket": "/run/user/1000/porelo/porelod.sock"},
                   {"name": "nameless distribution", "kind": "wsl"}
                 ]}
                """,
                StandardCharsets.UTF_8);
        final Machines windows =
                new Machines(
                        Map.of(
                                "APPDATA",
                                dir.resolve("roaming").toString(),
                                "LOCALAPPDATA",
                                dir.resolve("local").toString()),
                        dir.resolve("home"),
                        true);

        final Machines.Listed listed = windows.read();

        assertThat(windows.file())
                .as("where the interface keeps the file on Windows")
                .isEqualTo(file);
        assertThat(listed.machines())
                .as(
                        "what the plugin reaches on Windows: no daemon of this computer's own,"
                                + " and the machine over ssh without the interface's own end")
                .containsExactly(
                        Machine.wsl("Ubuntu on Windows", "Ubuntu"),
                        Machine.overSsh(
                                "build1", "a@build1", "/run/user/1001/porelo/porelod.sock"));
        assertThat(listed.unreachable())
                .as("what it keeps but does not reach there")
                .containsExactly(
                        new Machines.Unreachable(
                                "carried over",
                                "is a socket of a Linux machine, which Windows cannot open"),
                        new Machines.Unreachable(
                                "nameless distribution",
                                "is a WSL distribution entry that names no" + " distribution"));
        assertThat(windows.runtimeDirectory())
                .as("where the plugin's own sockets go on Windows")
                .isEqualTo(dir.resolve("local/porelo-intellij"));
    }

    @Test
    @DisplayName(
            "On Windows the plugin runs Windows' own ssh.exe, and a SystemRoot that is no drive's directory names no other program")
    void onWindowsItRunsWindowsOwnSsh() {
        final Path home = dir.resolve("home");

        assertThat(new Machines(Map.of("SystemRoot", "D:\\Windows"), home, true).ssh())
                .as("the ssh under SystemRoot")
                .isEqualTo("D:\\Windows\\System32\\OpenSSH\\ssh.exe");
        assertThat(
                        new Machines(
                                        Map.of("SystemRoot", "\\\\evil\\share\\x\" & calc & \""),
                                        home,
                                        true)
                                .ssh())
                .as("the ssh when SystemRoot names a share and a command")
                .isEqualTo("C:\\Windows\\System32\\OpenSSH\\ssh.exe");
        assertThat(new Machines(Map.of(), home).ssh()).as("the ssh on Linux").isEqualTo("ssh");
    }

    @Test
    @DisplayName("A host without a remote socket is refused, naming the file and the machine")
    void refusesHalfATunnel() throws IOException {
        write("{\"machines\": [{\"name\": \"half\", \"socket\": \"/a.sock\", \"host\": \"h\"}]}");

        assertThatThrownBy(() -> machines().all())
                .as("a tunnel with one end")
                .isInstanceOf(MachinesException.class)
                .hasMessageContaining("frontend.json")
                .hasMessageContaining("'half'")
                .hasMessageContaining("without a remote socket");
    }

    @Test
    @DisplayName("A file that is not JSON is refused, never read as no machines")
    void refusesAFileThatIsNotJson() throws IOException {
        write("{\"machines\": [");

        assertThatThrownBy(() -> machines().all())
                .as("a broken file")
                .isInstanceOf(MachinesException.class)
                .hasMessageContaining("frontend.json is not JSON");
    }

    @Test
    @DisplayName("An empty or relative XDG_CONFIG_HOME is not set, and the file is under ~/.config")
    void anEmptyOrRelativeConfigHomeIsNotSet() {
        final Path home = dir.resolve("home");

        assertThat(new Machines(Map.of("XDG_CONFIG_HOME", ""), home).file())
                .as("with an empty variable")
                .isEqualTo(home.resolve(".config/porelo/frontend.json"));
        assertThat(new Machines(Map.of("XDG_CONFIG_HOME", "relative"), home).file())
                .as("with a relative one")
                .isEqualTo(home.resolve(".config/porelo/frontend.json"));
    }

    @Test
    @DisplayName(
            "The second machine of a name is not reached, so it is never read or decided through the first one's connection")
    @DisabledOnOs(
            value = OS.WINDOWS,
            disabledReason =
                    "it reads the interface's file as Linux writes it, with Linux sockets, which are no paths on Windows")
    void theSecondMachineOfANameIsNotReached() throws IOException {
        write(
                """
                {"machines": [
                   {"name": "x", "socket": "/run/user/1000/a.sock"},
                   {"name": "x", "socket": "/run/user/1000/b.sock",
                    "host": "dev@other", "remoteSocket": "/run/user/1001/porelo/porelod.sock"}
                 ]}
                """);

        final Machines.Listed listed = machines().read();

        assertThat(listed.machines())
                .filteredOn(machine -> machine.name().equals("x"))
                .as("the machines named x the plugin reaches")
                .containsExactly(new Machine("x", Path.of("/run/user/1000/a.sock"), null, null));
        assertThat(listed.unreachable())
                .extracting(Machines.Unreachable::name)
                .as("the entries it keeps but does not reach")
                .containsExactly("x");
    }

    @Test
    @DisplayName(
            "A socket that is no absolute path is refused, so a tunnel never forwards to a port or opens a file where the IDE happens to run")
    void refusesASocketThatIsNoPath() throws IOException {
        write(
                "{\"machines\": [{\"name\": \"port\", \"socket\": \"/a.sock\", \"host\": \"h\", \"remoteSocket\": \"localhost:22\"}]}");
        assertThatThrownBy(() -> machines().all())
                .as("a remote socket that is a port")
                .isInstanceOf(MachinesException.class)
                .hasMessageContaining("'port'");

        write("{\"machines\": [{\"name\": \"here\", \"socket\": \"rel.sock\"}]}");
        assertThatThrownBy(() -> machines().all())
                .as("a relative socket")
                .isInstanceOf(MachinesException.class)
                .hasMessageContaining("'here'");
    }
}
