package org.fuin.porelo.intellij.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Lists and decides the items a task's run holds for the owner account, and follows how many each
 * task holds, with a stand-in daemon on a real unix socket whose answers carry the fields the
 * daemon's interface definition gives them.
 */
@DisabledOnOs(
        value = OS.WINDOWS,
        disabledReason =
                "its stand-in daemon, porelo-wire's VarlinkServer, makes its socket owner-only with POSIX permissions, which Windows has not")
class GateHeldTest {

    private static final String COMMIT = "9119cf0a6a99d1e2b3c4d5e6f708192a3b4c5d6e";

    @TempDir Path dir;

    private VarlinkServer daemon;

    @BeforeEach
    void startTheDaemon() {
        daemon = new VarlinkServer(dir.resolve("porelod.sock"), Gate.INTERFACE);
        Thread.ofVirtual().start(daemon);
    }

    @AfterEach
    void stopTheDaemon() throws Exception {
        daemon.close();
    }

    @Test
    @DisplayName(
            "A list of held items over two pages is read to its end, each page asked with the cursor the one before answered")
    void readsHeldItemsToTheirEnd() {
        final List<Map<String, Object>> asked = new CopyOnWriteArrayList<>();
        daemon.method(
                "HeldItems",
                (parameters, replies) -> {
                    asked.add(parameters);
                    if (parameters.get("cursor") == null) {
                        replies.last(
                                Map.of(
                                        "items",
                                        List.of(item("1", "outgoing", null)),
                                        "cursor",
                                        "page-2"));
                    } else {
                        replies.last(
                                Map.of("items", List.of(item("2", "incoming", "octo-reviewer"))));
                    }
                });

        try (Gate gate = new Gate(daemon.socketPath())) {
            final List<HeldItem> items = gate.heldItems("review-7").items();

            assertThat(items)
                    .as("both pages' items, in the daemon's order")
                    .extracting(HeldItem::id)
                    .containsExactly("1", "2");
            assertThat(items.get(1))
                    .as("the incoming item with its forge account")
                    .isEqualTo(
                            new HeldItem(
                                    "2",
                                    "review-7",
                                    "pull request 12",
                                    HeldItem.Direction.INCOMING,
                                    "github.com/a/p/pull/12",
                                    "reply",
                                    null,
                                    "4021",
                                    null,
                                    COMMIT,
                                    "Looks fine to me.\n<b>not markup</b>",
                                    "sha256:2",
                                    "octo-reviewer",
                                    "2026-11-25T11:30:00Z",
                                    3));
            assertThat(asked)
                    .as("what each page asked")
                    .containsExactly(
                            Map.of("task", "review-7"),
                            Map.of("task", "review-7", "cursor", "page-2"));
        }
    }

    @Test
    @DisplayName("Every task's held items are asked for when no task is named")
    void readsEveryTasksItems() {
        final AtomicReference<Map<String, Object>> asked = new AtomicReference<>();
        daemon.method(
                "HeldItems",
                (parameters, replies) -> {
                    asked.set(parameters);
                    replies.last(Map.of("items", List.of()));
                });

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThat(gate.heldItems(null))
                    .as("the answer")
                    .isEqualTo(new HeldList(List.of(), List.of()));
            assertThat(asked.get()).as("a call naming no task").isEmpty();
        }
    }

    @Test
    @DisplayName(
            "The tasks whose holds cannot be read are handed over beside the items, each once over every page")
    void handsOverTheTasksThatCannotBeRead() {
        daemon.method(
                "HeldItems",
                (parameters, replies) -> {
                    if (parameters.get("cursor") == null) {
                        replies.last(
                                Map.of(
                                        "items",
                                        List.of(item("1", "outgoing", null)),
                                        "unreadable",
                                        List.of("broken", "other"),
                                        "cursor",
                                        "page-2"));
                    } else {
                        replies.last(Map.of("items", List.of(), "unreadable", List.of("broken")));
                    }
                });

        try (Gate gate = new Gate(daemon.socketPath())) {
            final HeldList read = gate.heldItems(null);
            assertThat(read.items())
                    .as("the items read")
                    .extracting(HeldItem::id)
                    .containsExactly("1");
            assertThat(read.unreadable())
                    .as("the tasks that cannot be read")
                    .containsExactly("broken", "other");
        }
    }

    @Test
    @DisplayName("A list of tasks that cannot be read holding something else than names is refused")
    void refusesUnreadableThatIsNoListOfNames() {
        daemon.method(
                "HeldItems",
                (parameters, replies) ->
                        replies.last(Map.of("items", List.of(), "unreadable", List.of(3))));

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThatThrownBy(() -> gate.heldItems(null))
                    .as("a task named by a number")
                    .isInstanceOf(GateException.class)
                    .hasMessageContaining("not text");
        }
    }

    @Test
    @DisplayName("A cursor answered twice ends the reading instead of asking forever")
    void refusesACursorAnsweredTwice() {
        daemon.method(
                "HeldItems",
                (parameters, replies) ->
                        replies.last(
                                Map.of(
                                        "items",
                                        List.of(item("1", "outgoing", null)),
                                        "cursor",
                                        "same")));

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThatThrownBy(() -> gate.heldItems(null))
                    .as("a daemon that hands the same cursor again")
                    .isInstanceOf(GateException.class)
                    .hasMessageContaining("cursor same again");
        }
    }

    @Test
    @DisplayName("An incoming item without the forge account it came from is refused, not shown")
    void refusesAnIncomingItemWithoutItsAccount() {
        daemon.method(
                "HeldItems",
                (parameters, replies) ->
                        replies.last(Map.of("items", List.of(item("1", "incoming", null)))));

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThatThrownBy(() -> gate.heldItems(null))
                    .as("an incoming item that names nobody")
                    .isInstanceOf(GateException.class)
                    .hasMessageContaining("without 'from'");
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"id", "task", "text", "digest", "commit", "at", "run"})
    @DisplayName("An item without a field it is shown or decided by is refused, not read as empty")
    void refusesAnItemWithoutAField(String field) {
        final Map<String, Object> item = item("1", "outgoing", null);
        item.remove(field);
        daemon.method(
                "HeldItems", (parameters, replies) -> replies.last(Map.of("items", List.of(item))));

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThatThrownBy(() -> gate.heldItems(null))
                    .as("an item without " + field)
                    .isInstanceOf(GateException.class)
                    .hasMessageContaining("without '" + field + "'");
        }
    }

    @Test
    @DisplayName("A comment is read with its file, side, lines and proposed replacement, exactly")
    void readsACommentsAnchorAndSuggestion() {
        final Map<String, Object> item = comment();
        item.put("suggestion", "  return  <b>x</b>;\n");
        daemon.method(
                "HeldItems", (parameters, replies) -> replies.last(Map.of("items", List.of(item))));

        try (Gate gate = new Gate(daemon.socketPath())) {
            final HeldItem read = gate.heldItems(null).items().getFirst();
            assertThat(read.anchor())
                    .as("where it stands")
                    .isEqualTo(new HeldItem.Anchor("src/App.java", "new", 3, 5));
            assertThat(read.suggestion())
                    .as("its replacement, unchanged")
                    .isEqualTo("  return  <b>x</b>;\n");
            assertThat(read.thread()).as("the thread of a comment").isNull();
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"path", "side", "startLine", "endLine"})
    @DisplayName("A comment without a part of where it stands is refused, not shown")
    void refusesACommentWithoutItsAnchor(String field) {
        final Map<String, Object> item = comment();
        item.remove(field);
        daemon.method(
                "HeldItems", (parameters, replies) -> replies.last(Map.of("items", List.of(item))));

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThatThrownBy(() -> gate.heldItems(null))
                    .as("a comment without " + field)
                    .isInstanceOf(GateException.class)
                    .hasMessageContaining("without '" + field + "'");
        }
    }

    @ParameterizedTest(name = "lines {0} to {1} on the {2} side")
    @CsvSource({"0, 1, new", "5, 4, new", "3, 5, middle"})
    @DisplayName("A comment on lines or a side no file has is refused, not shown")
    void refusesACommentOnNoLines(int start, int end, String side) {
        final Map<String, Object> item = comment();
        item.put("startLine", start);
        item.put("endLine", end);
        item.put("side", side);
        daemon.method(
                "HeldItems", (parameters, replies) -> replies.last(Map.of("items", List.of(item))));

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThatThrownBy(() -> gate.heldItems(null))
                    .as("a comment on lines " + start + " to " + end + " on the " + side + " side")
                    .isInstanceOf(GateException.class)
                    .hasMessageContaining("comment on");
        }
    }

    @Test
    @DisplayName("A reply without its thread, or an item neither comment nor reply, is refused")
    void refusesAReplyWithoutItsThread() {
        final Map<String, Object> reply = item("1", "outgoing", null);
        reply.remove("thread");
        final Map<String, Object> other = item("2", "outgoing", null);
        other.put("operation", "approve");
        daemon.method(
                "HeldItems",
                (parameters, replies) ->
                        replies.last(
                                Map.of(
                                        "items",
                                        List.of(parameters.get("task") == null ? reply : other))));

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThatThrownBy(() -> gate.heldItems(null))
                    .as("a reply without its thread")
                    .isInstanceOf(GateException.class)
                    .hasMessageContaining("without 'thread'");
            assertThatThrownBy(() -> gate.heldItems("review-7"))
                    .as("an item that approves")
                    .isInstanceOf(GateException.class)
                    .hasMessageContaining("neither a comment nor a reply");
        }
    }

    @Test
    @DisplayName("A refusal that names no item is said in the daemon's own words")
    void saysARefusalWithoutItsItemInTheDaemonsWords() {
        daemon.method(
                "DecideItem",
                (parameters, replies) -> {
                    throw new VarlinkException(Gate.INTERFACE + ".Lapsed", Map.of());
                });

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThatThrownBy(
                            () ->
                                    gate.decideItem(
                                            held("7", HeldItem.Direction.OUTGOING, "d"),
                                            HeldItem.Decision.RELEASE))
                    .as("a refusal without its id")
                    .isNotInstanceOf(ItemRefusedException.class)
                    .isInstanceOf(GateException.class)
                    .hasMessage("DecideItem was refused: Lapsed");
        }
    }

    @Test
    @DisplayName("An item going neither out nor in is refused, not shown")
    void refusesAnUnknownDirection() {
        daemon.method(
                "HeldItems",
                (parameters, replies) ->
                        replies.last(Map.of("items", List.of(item("1", "sideways", null)))));

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThatThrownBy(() -> gate.heldItems(null))
                    .as("an item of an unknown direction")
                    .isInstanceOf(GateException.class)
                    .hasMessageContaining("neither out nor in");
        }
    }

    @Test
    @DisplayName("A release sends the task, the item, the digest that was shown and the decision")
    void releaseSendsTheDigestShown() {
        final AtomicReference<Map<String, Object>> asked = new AtomicReference<>();
        daemon.method(
                "DecideItem",
                (parameters, replies) -> {
                    asked.set(parameters);
                    replies.last(Map.of());
                });
        final HeldItem shown = held("7", HeldItem.Direction.OUTGOING, "sha256:shown");

        try (Gate gate = new Gate(daemon.socketPath())) {
            gate.decideItem(shown, HeldItem.Decision.RELEASE);
        }

        assertThat(asked.get())
                .as("what the release sent")
                .isEqualTo(
                        Map.of(
                                "task",
                                "review-7",
                                "id",
                                "7",
                                "digest",
                                "sha256:shown",
                                "decision",
                                "release"));
    }

    @Test
    @DisplayName("A decision its item's direction does not take is refused before anything is sent")
    void refusesADecisionOfTheOtherDirection() {
        final List<Map<String, Object>> asked = new CopyOnWriteArrayList<>();
        daemon.method(
                "DecideItem",
                (parameters, replies) -> {
                    asked.add(parameters);
                    replies.last(Map.of());
                });

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThatThrownBy(
                            () ->
                                    gate.decideItem(
                                            held("7", HeldItem.Direction.INCOMING, "d"),
                                            HeldItem.Decision.RELEASE))
                    .as("releasing an incoming item")
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(asked).as("what reached the daemon").isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "NoSuchItem     | Item 7 is not held; nothing was decided",
                "DigestChanged  | Item 7 changed since it was shown; nothing was decided, and it is shown again as it stands",
                "AlreadyDecided | Item 7 was already decided; nothing was decided now",
                "Lapsed         | Item 7 lapsed with the run that held it; nothing was decided, and it never went further"
            })
    @DisplayName("Each refusal of a decision is said in words with the item it names")
    void saysEachRefusalInWords(String reason, String words) {
        daemon.method(
                "DecideItem",
                (parameters, replies) -> {
                    throw new VarlinkException(Gate.INTERFACE + "." + reason, Map.of("id", "7"));
                });

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThatThrownBy(
                            () ->
                                    gate.decideItem(
                                            held("7", HeldItem.Direction.OUTGOING, "d"),
                                            HeldItem.Decision.REFUSE))
                    .as("the refusal " + reason)
                    .isInstanceOfSatisfying(
                            ItemRefusedException.class,
                            refused -> {
                                assertThat(refused.reason()).as("its reason").isEqualTo(reason);
                                assertThat(refused.id()).as("its item").isEqualTo("7");
                                assertThat(refused.getMessage()).as("its words").isEqualTo(words);
                            });
        }
    }

    @Test
    @DisplayName("Watching the tasks hands over each task's held count with every answer")
    void watchesEachTasksHeldCount() {
        daemon.method(
                "Watch",
                (parameters, replies) -> {
                    assertThat(replies.streaming()).as("whether the stream was asked for").isTrue();
                    replies.more(Map.of("tasks", List.of(task("a", 0), task("b", 1))));
                    replies.last(Map.of("tasks", List.of(task("a", 2), task("b", 1))));
                });
        final List<Map<String, Integer>> seen = new ArrayList<>();

        try (Gate gate = new Gate(daemon.socketPath())) {
            gate.watchHeld(seen::add);
        }

        assertThat(seen)
                .as("every answer, in its order")
                .containsExactly(Map.of("a", 0, "b", 1), Map.of("a", 2, "b", 1));
    }

    @Test
    @DisplayName(
            "A task whose holds the daemon cannot read is handed over with an unknown count, never none")
    void handsOverAnUnknownCount() {
        daemon.method(
                "Watch",
                (parameters, replies) ->
                        replies.last(
                                Map.of(
                                        "tasks",
                                        List.of(Map.of("name", "a"), task("b", 0)),
                                        "unrecognized",
                                        List.of())));
        final List<Map<String, Integer>> seen = new ArrayList<>();

        try (Gate gate = new Gate(daemon.socketPath())) {
            gate.watchHeld(seen::add);
        }

        final Map<String, Integer> unknown = new HashMap<>();
        unknown.put("a", null);
        unknown.put("b", 0);
        assertThat(seen).as("the answer").containsExactly(unknown);
    }

    @Test
    @DisplayName("A held count that is no count ends the watch instead of being read")
    void refusesAHeldCountThatIsNoCount() {
        daemon.method(
                "Watch",
                (parameters, replies) ->
                        replies.last(
                                Map.of(
                                        "tasks",
                                        List.of(Map.of("name", "a", "held", -1)),
                                        "unrecognized",
                                        List.of())));

        try (Gate gate = new Gate(daemon.socketPath())) {
            assertThatThrownBy(() -> gate.watchHeld(held -> {}))
                    .as("a daemon whose task holds -1 items")
                    .isInstanceOf(GateException.class)
                    .hasMessageContaining("no count");
        }
    }

    private static Map<String, Object> task(String name, int held) {
        return Map.of("name", name, "running", true, "held", held);
    }

    private static Map<String, Object> item(String id, String direction, String from) {
        final Map<String, Object> item = new HashMap<>();
        item.put("id", id);
        item.put("task", "review-7");
        item.put("place", "pull request 12");
        item.put("direction", direction);
        item.put("destination", "github.com/a/p/pull/12");
        item.put("operation", "reply");
        item.put("thread", "4021");
        item.put("commit", COMMIT);
        item.put("text", "Looks fine to me.\n<b>not markup</b>");
        item.put("digest", "sha256:" + id);
        if (from != null) {
            item.put("from", from);
        }
        item.put("at", "2026-11-25T11:30:00Z");
        item.put("run", 3);
        return item;
    }

    private static Map<String, Object> comment() {
        final Map<String, Object> item = item("1", "outgoing", null);
        item.put("operation", "comment");
        item.remove("thread");
        item.put("path", "src/App.java");
        item.put("side", "new");
        item.put("startLine", 3);
        item.put("endLine", 5);
        return item;
    }

    private static HeldItem held(String id, HeldItem.Direction direction, String digest) {
        return new HeldItem(
                id,
                "review-7",
                "pull request 12",
                direction,
                "github.com/a/p/pull/12",
                "comment",
                new HeldItem.Anchor("src/App.java", "new", 3, 5),
                null,
                null,
                COMMIT,
                "text",
                digest,
                direction == HeldItem.Direction.INCOMING ? "octo" : null,
                "2026-11-25T11:30:00Z",
                3);
    }
}
