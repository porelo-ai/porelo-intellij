package org.fuin.porelo.intellij.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.intellij.gate.Gate;
import org.fuin.porelo.intellij.gate.HeldItem;
import org.fuin.porelo.intellij.gate.ItemRefusedException;
import org.fuin.porelo.intellij.machine.Machine;
import org.fuin.porelo.intellij.machine.Tunnel;
import org.fuin.porelo.wire.LimitFactors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lists and decides an item a real task's run holds, through a real ssh tunnel - run alone, as
 * {@code ./gradlew liveTest}, never in the build.
 *
 * <p>The machine named as for {@link LiveGateTest} has a task {@code PORELO_LIVE_HELD_TASK} whose
 * run holds at least one outgoing item, undecided. The test refuses the first one, so nothing
 * reaches the forge; it is not run twice on the same item.
 */
@Tag("live")
class LiveHeldItemsTest {

    @TempDir Path runtime;

    private static String env(String name) {
        final String value = System.getenv(name);
        assertThat(value).as("the environment variable " + name).isNotBlank();
        return value;
    }

    @Test
    @DisplayName(
            "A real task's held item is counted in the watch, listed, kept from a stale digest and refused once")
    void decidesARealHeldItem() throws Exception {
        final Machine machine =
                new Machine(
                        "live",
                        Path.of("/nowhere.sock"),
                        env("PORELO_LIVE_HOST"),
                        env("PORELO_LIVE_SOCKET"),
                        Path.of(env("PORELO_LIVE_SSH_CONFIG")));
        final String task = env("PORELO_LIVE_HELD_TASK");

        try (Tunnel tunnel = Tunnel.raise(machine, runtime, List.of("ssh"), LimitFactors.DEFAULT)) {
            final Map<String, Integer> counted = firstCounts(tunnel.socket());
            try (Gate gate = LiveGates.opened(tunnel.socket())) {
                final List<HeldItem> held = gate.heldItems(task).items();
                assertThat(held).as("the items " + task + " holds").isNotEmpty();
                assertThat(counted.get(task))
                        .as("the count the watch gives " + task)
                        .isEqualTo(held.size());
                assertThat(gate.heldItems(null).items())
                        .as("every task's items, which hold this task's")
                        .containsAll(held);
                final HeldItem item =
                        held.stream()
                                .filter(one -> one.direction() == HeldItem.Direction.OUTGOING)
                                .findFirst()
                                .orElseThrow(
                                        () -> new AssertionError("no outgoing item in " + held));

                final HeldItem stale =
                        new HeldItem(
                                item.id(),
                                item.task(),
                                item.place(),
                                item.direction(),
                                item.destination(),
                                item.operation(),
                                item.anchor(),
                                item.thread(),
                                item.suggestion(),
                                item.commit(),
                                item.text(),
                                "0".repeat(64),
                                item.from(),
                                item.at(),
                                item.run());
                assertThatThrownBy(() -> gate.decideItem(stale, HeldItem.Decision.REFUSE))
                        .as("a decision on a text that was not shown")
                        .isInstanceOfSatisfying(
                                ItemRefusedException.class,
                                refused ->
                                        assertThat(refused.reason())
                                                .as("its reason")
                                                .isEqualTo("DigestChanged"));

                gate.decideItem(item, HeldItem.Decision.REFUSE);

                assertThat(gate.heldItems(task).items())
                        .as("the items after the refusal")
                        .extracting(HeldItem::id)
                        .doesNotContain(item.id());
                assertThatThrownBy(() -> gate.decideItem(item, HeldItem.Decision.RELEASE))
                        .as("a second decision on the refused item")
                        .isInstanceOfSatisfying(
                                ItemRefusedException.class,
                                refused ->
                                        assertThat(refused.reason())
                                                .as("its reason")
                                                .isEqualTo("AlreadyDecided"));
            }
        }
    }

    // The stream answers at once with the whole list; it is ended by closing its gate.
    private static Map<String, Integer> firstCounts(Path socket) throws Exception {
        final CompletableFuture<Map<String, Integer>> first = new CompletableFuture<>();
        try (Gate gate = LiveGates.opened(socket)) {
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try {
                                    gate.watchHeld(first::complete);
                                } catch (RuntimeException ex) {
                                    first.completeExceptionally(ex);
                                }
                            });
            return first.get(30, TimeUnit.SECONDS);
        }
    }
}
