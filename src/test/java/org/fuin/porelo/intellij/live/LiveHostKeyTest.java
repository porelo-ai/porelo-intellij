package org.fuin.porelo.intellij.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.porelo.intellij.gate.Gate;
import org.fuin.porelo.intellij.machine.Machine;
import org.fuin.porelo.intellij.machine.MachinesException;
import org.fuin.porelo.intellij.machine.PinnedConfig;
import org.fuin.porelo.intellij.machine.Relay;
import org.fuin.porelo.intellij.machine.Tunnel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Refuses a real machine whose host key is not the one its host card names, though the person's own
 * ssh configuration reaches and trusts it - run alone, as {@code ./gradlew liveTest}, never in the
 * build.
 *
 * <p>The machine is the one {@code LiveGateTest} names; {@code PORELO_LIVE_SSH_CONFIG} stands for
 * the person's own configuration, given to ssh as the plugin's ssh is given, and the host card's
 * configuration includes it, so the address, account and key are the ones that reach the machine.
 * {@code PORELO_LIVE_SSH_CONFIG} pins the machine's own host key, so given as the host card's
 * configuration it reaches the daemon, which shows that a refusal comes from the key.
 */
@Tag("live")
class LiveHostKeyTest {

    @TempDir Path runtime;

    @TempDir Path card;

    private static String env(String name) {
        final String value = System.getenv(name);
        assertThat(value).as("the environment variable " + name).isNotBlank();
        return value;
    }

    private static List<String> personsSsh() {
        return List.of("ssh", "-F", env("PORELO_LIVE_SSH_CONFIG"));
    }

    @Test
    @DisplayName(
            "A tunnel to a host that answers with another key than its card names is refused, naming the machine")
    void aTunnelToAHostWithAnotherKeyIsRefused() throws IOException {
        final Machine machine =
                new Machine(
                        "live",
                        runtime.resolve("unused.sock"),
                        env("PORELO_LIVE_HOST"),
                        env("PORELO_LIVE_SOCKET"),
                        PinnedConfig.ofAnotherHost(card, Path.of(env("PORELO_LIVE_SSH_CONFIG"))));

        assertThatThrownBy(() -> Tunnel.raise(machine, runtime, personsSsh()).close())
                .as("a tunnel to a host whose key is not the card's")
                .isInstanceOf(MachinesException.class)
                .hasMessageContaining("'live'")
                .hasMessageContaining("Host key verification failed");
        try (var left = Files.list(runtime)) {
            assertThat(left.filter(path -> path.toString().endsWith(".sock")))
                    .as("the plugin's ends left behind")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName(
            "A relay to a host that answers with another key than its card names is refused, naming the host")
    void aRelayToAHostWithAnotherKeyIsRefused() throws IOException {
        final Machine machine =
                Machine.overSsh(
                        "live",
                        env("PORELO_LIVE_HOST"),
                        env("PORELO_LIVE_SOCKET"),
                        PinnedConfig.ofAnotherHost(card, Path.of(env("PORELO_LIVE_SSH_CONFIG"))));

        assertThatThrownBy(() -> Relay.overSsh(machine, runtime, personsSsh()).close())
                .as("a relay to a host whose key is not the card's")
                .isInstanceOf(MachinesException.class)
                .hasMessageContaining("ssh to " + env("PORELO_LIVE_HOST"))
                .hasMessageContaining("Host key verification failed");
    }

    @Test
    @DisplayName("A tunnel and a relay to a host that answers with its card's key reach the daemon")
    void aHostWithItsCardsKeyIsReached() {
        final Path config = Path.of(env("PORELO_LIVE_SSH_CONFIG"));
        final Machine tunnelled =
                new Machine(
                        "live",
                        runtime.resolve("unused.sock"),
                        env("PORELO_LIVE_HOST"),
                        env("PORELO_LIVE_SOCKET"),
                        config);
        final Machine relayed =
                Machine.overSsh("live", env("PORELO_LIVE_HOST"), env("PORELO_LIVE_SOCKET"), config);

        try (Tunnel tunnel = Tunnel.raise(tunnelled, runtime, personsSsh());
                Gate gate = new Gate(tunnel.socket())) {
            assertThat(gate.projects()).as("the projects read through the tunnel").isNotEmpty();
        }
        try (Relay relay = Relay.overSsh(relayed, runtime, personsSsh());
                Gate gate = new Gate(relay.socket())) {
            assertThat(gate.projects()).as("the projects read through the relay").isNotEmpty();
        }
    }
}
