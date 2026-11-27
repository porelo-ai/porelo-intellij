package org.fuin.porelo.intellij.ide;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.fuin.porelo.intellij.gate.HeldItem;
import org.fuin.porelo.intellij.gate.HeldList;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the tool window and the notifications make of the items tasks hold. */
class HeldTest {

    @Test
    @DisplayName(
            "A task is announced only when its count rises, and one not counted before counts none")
    void announcesOnlyARise() {
        assertThat(Held.rose(Map.of("a", 1, "b", 2), Map.of("a", 2, "b", 1, "c", 0, "d", 3)))
                .as("the tasks that rose")
                .isEqualTo(Map.of("a", 2, "d", 3));
        assertThat(Held.rose(Map.of(), Map.of("a", 0, "b", 1)))
                .as("the first answer of a machine")
                .isEqualTo(Map.of("b", 1));
        assertThat(Held.rose(Map.of("a", 2), Map.of("a", 2)))
                .as("an answer that repeats the counts")
                .isEmpty();
    }

    @Test
    @DisplayName(
            "An unknown count announces nothing and keeps the count known before, so its return announces only a rise")
    void keepsTheLastKnownCountThroughAnUnknownOne() {
        final Map<String, Integer> unknown = new java.util.HashMap<>();
        unknown.put("a", null);
        unknown.put("b", 1);

        assertThat(Held.rose(Map.of("a", 2), unknown))
                .as("the tasks that rose while a's count is unknown")
                .isEqualTo(Map.of("b", 1));
        final Map<String, Integer> known = Held.known(Map.of("a", 2), unknown);
        assertThat(known).as("the counts kept").isEqualTo(Map.of("a", 2, "b", 1));
        assertThat(Held.rose(known, Map.of("a", 2, "b", 1)))
                .as("a's count read again, unchanged")
                .isEmpty();
        assertThat(Held.rose(known, Map.of("a", 3, "b", 1)))
                .as("a's count read again, risen")
                .isEqualTo(Map.of("a", 3));
    }

    @Test
    @DisplayName(
            "The tasks whose count is unknown are kept per machine, and none leaves the machine out")
    void keepsTheTasksWhoseCountIsUnknown() {
        final Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        counts.put("a", null);
        counts.put("b", 0);

        final Held held = Held.NONE.counted("m", counts);

        assertThat(held.unknown()).as("the unknown counts").isEqualTo(Map.of("m", List.of("a")));
        assertThat(held.counted("m", Map.of("a", 1)).unknown())
                .as("once a's count is known again")
                .isEmpty();
        assertThat(held.without("m")).as("the machine taken out").isEqualTo(Held.NONE);
    }

    @Test
    @DisplayName(
            "A task whose holds cannot be read is said once, whether its count or its items said so")
    void saysEachTaskThatCannotBeReadOnce() {
        final Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        counts.put("a", null);

        final Held held =
                Held.NONE
                        .counted("m", counts)
                        .with("m", new HeldList(List.of(), List.of("a", "b")));

        assertThat(held.cannotRead())
                .as("the tasks said")
                .isEqualTo(Map.of("m", List.of("a", "b")));
        assertThat(held.with("m", new HeldList(List.of(), List.of())).journals())
                .as("once the items are read again")
                .isEmpty();
    }

    @Test
    @DisplayName("A machine's items are grouped by task in the order the daemon answered them")
    void groupsByTask() {
        final HeldItem one = item("1", "b", HeldItem.Direction.OUTGOING);
        final HeldItem two = item("2", "a", HeldItem.Direction.OUTGOING);
        final HeldItem three = item("3", "b", HeldItem.Direction.OUTGOING);

        assertThat(Held.byTask(List.of(one, two, three)))
                .as("the items by task")
                .containsExactly(Map.entry("b", List.of(one, three)), Map.entry("a", List.of(two)));
    }

    @Test
    @DisplayName(
            "A machine that cannot be read keeps what it held before beside why, and its next read clears why")
    void keepsWhatWasReadBesideWhy() {
        final HeldItem one = item("1", "a", HeldItem.Direction.OUTGOING);
        final Held read = Held.NONE.with("m", new HeldList(List.of(one), List.of()));

        final Held silent = read.unreadable("m", "nothing answers");

        assertThat(silent.items()).as("the items kept").isEqualTo(Map.of("m", List.of(one)));
        assertThat(silent.unreadable()).as("why").isEqualTo(Map.of("m", "nothing answers"));
        assertThat(silent.with("m", new HeldList(List.of(), List.of())).unreadable())
                .as("why, after the next read")
                .isEmpty();
        assertThat(silent.without("m")).as("the machine taken out").isEqualTo(Held.NONE);
    }

    @Test
    @DisplayName(
            "An incoming item is said to come from its forge account and to be withheld from the task")
    void saysWhereAnIncomingItemCameFrom() {
        assertThat(Held.facts("laptop", item("4", "review-7", HeldItem.Direction.INCOMING)))
                .as("the lines above its text")
                .containsExactly(
                        "Came in from forge account octo, withheld from the task",
                        "Destination: github.com/a/p/pull/12",
                        "Comment on src/App.java, new side, lines 3 to 5",
                        "Commit: 9119cf0a6a99d1e2b3c4d5e6f708192a3b4c5d6e",
                        "Place: pull request 12",
                        "Task: review-7 on laptop, run 3",
                        "Held at: 2026-11-25T11:30:00Z");
    }

    @Test
    @DisplayName(
            "An unsigned item says so right below its direction, with what to weigh before releasing it")
    void saysAnItemIsUnsigned() {
        final List<String> facts = Held.facts("laptop", marked("unsigned"));

        assertThat(facts.get(1))
                .as("the line below the direction of an unsigned item")
                .contains("Unsigned")
                .contains("release it only if you trust what the task wrote");
        assertThat(Held.mark(marked("unsigned")))
                .as("what its row shows beside the operation")
                .isEqualTo("unsigned");
    }

    @Test
    @DisplayName("A mark the plugin does not know is shown by its word, never dropped")
    void showsAMarkItDoesNotKnow() {
        assertThat(Held.facts("laptop", marked("forged")).get(1))
                .as("the line below the direction of an item marked forged")
                .contains("forged");
        assertThat(Held.mark(marked("forged")))
                .as("what its row shows beside the operation")
                .isEqualTo("forged");
        assertThat(Held.mark(item("4", "review-7", HeldItem.Direction.OUTGOING)))
                .as("what the row of an item without a mark shows")
                .isNull();
    }

    @Test
    @DisplayName(
            "A reply names the thread it answers, and a replacement says which lines it replaces")
    void saysWhereAnItemStands() {
        final HeldItem comment = item("5", "a", HeldItem.Direction.OUTGOING);
        final HeldItem oneLine =
                new HeldItem(
                        "6",
                        "a",
                        "p",
                        HeldItem.Direction.OUTGOING,
                        "p",
                        "comment",
                        new HeldItem.Anchor("build.gradle.kts", "old", 7, 7),
                        null,
                        null,
                        "c",
                        "t",
                        "d",
                        null,
                        "2026-11-25T11:30:00Z",
                        1,
                        null);
        final HeldItem reply =
                new HeldItem(
                        "7",
                        "a",
                        "p",
                        HeldItem.Direction.OUTGOING,
                        "p",
                        "reply",
                        null,
                        "4021",
                        null,
                        "c",
                        "t",
                        "d",
                        null,
                        "2026-11-25T11:30:00Z",
                        1,
                        null);

        assertThat(Held.where(oneLine))
                .as("a comment on one line")
                .isEqualTo("Comment on build.gradle.kts, old side, line 7");
        assertThat(Held.where(reply)).as("a reply").isEqualTo("Reply in thread 4021");
        assertThat(Held.replaces(comment))
                .as("the heading of a replacement")
                .isEqualTo("Proposed replacement of lines 3 to 5:");
        assertThat(Held.replaces(oneLine)).as("a comment that proposes none").isNull();
    }

    @Test
    @DisplayName("A resolve is shown by the thread it marks, in its row and above its empty text")
    void showsAResolveByItsThread() {
        final HeldItem resolve = operated("resolve", "4021");

        assertThat(Held.where(resolve))
                .as("its line above the text")
                .isEqualTo("Resolve thread 4021");
        assertThat(Held.row(resolve)).as("its row").isEqualTo("Resolve thread 4021");
        assertThat(Held.withheld(resolve)).as("why its decisions are withheld").isNull();
    }

    @Test
    @DisplayName(
            "A resolve that came in says its decisions are withheld, since only a task sends one")
    void saysWhyAnIncomingResolveIsNotDecided() {
        final HeldItem resolve = operated("resolve", "4021");
        final HeldItem incoming =
                new HeldItem(
                        resolve.id(),
                        resolve.task(),
                        resolve.place(),
                        HeldItem.Direction.INCOMING,
                        resolve.destination(),
                        "resolve",
                        null,
                        "4021",
                        null,
                        resolve.commit(),
                        "",
                        resolve.digest(),
                        "octo",
                        resolve.at(),
                        resolve.run(),
                        null);

        assertThat(incoming.decisions()).as("what it offers").isEmpty();
        assertThat(Held.withheld(incoming))
                .as("why its decisions are withheld")
                .isEqualTo(
                        "Pass and Dismiss are withheld: a resolve comes only from a task's run, so"
                                + " one that came in cannot be shown as it would go.");
    }

    @Test
    @DisplayName(
            "An operation the plugin does not know is shown by its word and says its decisions are withheld")
    void showsAnUnknownOperationByItsWord() {
        final HeldItem unknown = operated("approve", null);

        assertThat(Held.row(unknown)).as("its row").isEqualTo("approve");
        assertThat(Held.where(unknown))
                .as("its line above the text")
                .isEqualTo("Operation approve, unknown to this plugin");
        assertThat(Held.withheld(unknown))
                .as("why its decisions are withheld")
                .contains("Release and Refuse are withheld")
                .contains("update the Porelo plugin");
        assertThat(unknown.decisions()).as("what it offers").isEmpty();
        assertThat(Held.facts("laptop", unknown))
                .as("the lines above its text")
                .contains(Held.where(unknown), Held.withheld(unknown));
    }

    @Test
    @DisplayName("A reply's row starts with its operation, and shows its text's first line")
    void showsARepliesRowByItsFirstLine() {
        final HeldItem reply = operated("reply", "4021");

        assertThat(Held.row(reply)).as("a reply's row").isEqualTo("reply");
        assertThat(Held.firstLine(reply.text())).as("its first line").isEqualTo("first line");
        assertThat(Held.firstLine("")).as("the first line of no text").isEmpty();
    }

    @Test
    @DisplayName("An outgoing item is released or refused, an incoming one passed or dismissed")
    void offersEachDirectionsDecisions() {
        assertThat(HeldItem.Direction.OUTGOING.decisions())
                .as("an outgoing item's decisions")
                .containsExactly(HeldItem.Decision.RELEASE, HeldItem.Decision.REFUSE);
        assertThat(HeldItem.Direction.INCOMING.decisions())
                .as("an incoming item's decisions")
                .containsExactly(HeldItem.Decision.PASS, HeldItem.Decision.DISMISS);
    }

    private static HeldItem operated(String operation, @Nullable String thread) {
        return new HeldItem(
                "8",
                "a",
                "p",
                HeldItem.Direction.OUTGOING,
                "p",
                operation,
                null,
                thread,
                null,
                "c",
                operation.equals("reply") ? "first line\nsecond line" : "",
                "d",
                null,
                "2026-11-25T11:30:00Z",
                1,
                null);
    }

    private static HeldItem marked(String mark) {
        final HeldItem item = item("5", "review-7", HeldItem.Direction.OUTGOING);
        return new HeldItem(
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
                item.digest(),
                item.from(),
                item.at(),
                item.run(),
                mark);
    }

    private static HeldItem item(String id, String task, HeldItem.Direction direction) {
        return new HeldItem(
                id,
                task,
                "pull request 12",
                direction,
                "github.com/a/p/pull/12",
                "comment",
                new HeldItem.Anchor("src/App.java", "new", 3, 5),
                null,
                "return x;",
                "9119cf0a6a99d1e2b3c4d5e6f708192a3b4c5d6e",
                "text",
                "sha256:" + id,
                direction == HeldItem.Direction.INCOMING ? "octo" : null,
                "2026-11-25T11:30:00Z",
                3,
                null);
    }
}
