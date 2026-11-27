package org.fuin.porelo.intellij.ide;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.fuin.porelo.intellij.gate.HeldItem;
import org.fuin.porelo.intellij.gate.HeldList;

/**
 * The items every machine's tasks hold for the owner account's decision, as last read, the machines
 * whose items could not be read, each with why, and the tasks whose holds the daemon cannot read:
 * said, never shown as holding nothing.
 *
 * @param items Each machine's held items, by machine name.
 * @param unreadable Why a machine's items could not be read, by machine name.
 * @param unknown The tasks whose count of held items is unknown, by machine name.
 * @param journals The tasks whose held items could not be read, by machine name.
 */
public record Held(
        Map<String, List<HeldItem>> items,
        Map<String, String> unreadable,
        Map<String, List<String>> unknown,
        Map<String, List<String>> journals) {

    /** Nothing read yet. */
    public static final Held NONE = new Held(Map.of(), Map.of(), Map.of(), Map.of());

    private static final String UNSIGNED =
            "Unsigned. No certified key of the task signed this item; release it only if you trust"
                    + " what the task wrote.";

    /**
     * Copies the maps, ordered by machine name.
     *
     * @param items Each machine's items.
     * @param unreadable Why a machine could not be read.
     * @param unknown The tasks whose count is unknown.
     * @param journals The tasks whose items could not be read.
     */
    public Held {
        final Map<String, List<HeldItem>> copied = new TreeMap<>();
        items.forEach((machine, held) -> copied.put(machine, List.copyOf(held)));
        items = java.util.Collections.unmodifiableMap(copied);
        unreadable = java.util.Collections.unmodifiableMap(new TreeMap<>(unreadable));
        final Map<String, List<String>> tasks = new TreeMap<>();
        unknown.forEach(
                (machine, names) -> {
                    if (!names.isEmpty()) {
                        tasks.put(machine, List.copyOf(names));
                    }
                });
        unknown = byMachine(unknown);
        journals = byMachine(journals);
    }

    // A machine without such tasks is left out, so "none" has one form.
    private static Map<String, List<String>> byMachine(Map<String, List<String>> tasks) {
        final Map<String, List<String>> copied = new TreeMap<>();
        tasks.forEach(
                (machine, names) -> {
                    if (!names.isEmpty()) {
                        copied.put(machine, List.copyOf(names));
                    }
                });
        return java.util.Collections.unmodifiableMap(copied);
    }

    /**
     * Returns every task of a machine whose holds its daemon cannot read, whether its count or its
     * items said so, each once.
     *
     * @return The tasks, by machine name.
     */
    Map<String, List<String>> cannotRead() {
        final Map<String, java.util.Set<String>> tasks = new TreeMap<>();
        unknown.forEach(
                (machine, names) ->
                        tasks.computeIfAbsent(machine, any -> new java.util.LinkedHashSet<>())
                                .addAll(names));
        journals.forEach(
                (machine, names) ->
                        tasks.computeIfAbsent(machine, any -> new java.util.LinkedHashSet<>())
                                .addAll(names));
        final Map<String, List<String>> said = new TreeMap<>();
        tasks.forEach((machine, names) -> said.put(machine, List.copyOf(names)));
        return said;
    }

    /**
     * Returns this with the tasks of one machine whose count is unknown, as its daemon answered
     * last.
     *
     * @param machine The machine's name.
     * @param counts Each task's count, {@code null} where it is unknown.
     * @return The new state.
     */
    Held counted(String machine, Map<String, @org.jspecify.annotations.Nullable Integer> counts) {
        final Map<String, List<String>> now = new LinkedHashMap<>(unknown);
        now.put(
                machine,
                counts.entrySet().stream()
                        .filter(entry -> entry.getValue() == null)
                        .map(Map.Entry::getKey)
                        .toList());
        return new Held(items, unreadable, now, journals);
    }

    /**
     * Returns this with one machine's items read again.
     *
     * @param machine The machine's name.
     * @param read Its items now, and its tasks whose items could not be read.
     * @return The new state.
     */
    Held with(String machine, HeldList read) {
        final Map<String, List<HeldItem>> now = new LinkedHashMap<>(items);
        final Map<String, String> why = new LinkedHashMap<>(unreadable);
        final Map<String, List<String>> tasks = new LinkedHashMap<>(journals);
        now.put(machine, read.items());
        why.remove(machine);
        tasks.put(machine, read.unreadable());
        return new Held(now, why, unknown, tasks);
    }

    /**
     * Returns this with one machine's items unreadable; what was read before is kept and said to be
     * old by the reason beside it.
     *
     * @param machine The machine's name.
     * @param reason Why it could not be read.
     * @return The new state.
     */
    Held unreadable(String machine, String reason) {
        final Map<String, String> why = new LinkedHashMap<>(unreadable);
        why.put(machine, reason);
        return new Held(items, why, unknown, journals);
    }

    /**
     * Returns this without a machine that is no longer listed.
     *
     * @param machine The machine's name.
     * @return The new state.
     */
    Held without(String machine) {
        final Map<String, List<HeldItem>> now = new LinkedHashMap<>(items);
        final Map<String, String> why = new LinkedHashMap<>(unreadable);
        final Map<String, List<String>> tasks = new LinkedHashMap<>(unknown);
        final Map<String, List<String>> unread = new LinkedHashMap<>(journals);
        now.remove(machine);
        why.remove(machine);
        tasks.remove(machine);
        unread.remove(machine);
        return new Held(now, why, tasks, unread);
    }

    /**
     * Returns the tasks whose count of held items rose, so each is announced once per rise; a task
     * not counted before counts as holding none. An unknown count rises from nothing, and a count
     * known again rises from the last one known.
     *
     * @param before Each task's last known count; empty before the first answer.
     * @param now Each task's count now, {@code null} where it is unknown.
     * @return The tasks whose count rose, with their count now, in the order answered.
     */
    static Map<String, Integer> rose(
            Map<String, Integer> before,
            Map<String, @org.jspecify.annotations.Nullable Integer> now) {
        final Map<String, Integer> rose = new LinkedHashMap<>();
        now.forEach(
                (task, count) -> {
                    if (count != null && count > before.getOrDefault(task, 0)) {
                        rose.put(task, count);
                    }
                });
        return rose;
    }

    /**
     * Returns each task's last known count once an answer is taken: an unknown count keeps the one
     * known before, so a journal read again after a fault announces only what is new.
     *
     * @param before Each task's last known count.
     * @param now Each task's count now, {@code null} where it is unknown.
     * @return Each task's last known count, for the tasks answered now.
     */
    static Map<String, Integer> known(
            Map<String, Integer> before,
            Map<String, @org.jspecify.annotations.Nullable Integer> now) {
        final Map<String, Integer> known = new LinkedHashMap<>();
        now.forEach(
                (task, count) -> {
                    final @org.jspecify.annotations.Nullable Integer kept =
                            count != null ? count : before.get(task);
                    if (kept != null) {
                        known.put(task, kept);
                    }
                });
        return known;
    }

    /**
     * Groups one machine's items by their task, in the order the daemon answered them.
     *
     * @param items The machine's items.
     * @return Each task's items, by task name in the order first answered.
     */
    static Map<String, List<HeldItem>> byTask(List<HeldItem> items) {
        final Map<String, List<HeldItem>> byTask = new LinkedHashMap<>();
        for (final HeldItem item : items) {
            byTask.computeIfAbsent(item.task(), task -> new ArrayList<>()).add(item);
        }
        return byTask;
    }

    /**
     * Returns what is shown above an item's text.
     *
     * @param machine The machine that holds it.
     * @param item The item.
     * @return One line each.
     */
    static List<String> facts(String machine, HeldItem item) {
        // The order is the person's reading order: who and where first, then what it stands on.
        final List<String> lines = new ArrayList<>();
        lines.add(
                item.direction() == HeldItem.Direction.OUTGOING
                        ? "Going out to the forge, held before it was sent"
                        : "Came in from forge account " + item.from() + ", withheld from the task");
        final String mark = item.mark();
        if (mark != null) {
            lines.add(marked(mark));
        }
        lines.add("Destination: " + item.destination());
        lines.add(where(item));
        final @org.jspecify.annotations.Nullable String withheld = withheld(item);
        if (withheld != null) {
            lines.add(withheld);
        }
        lines.add("Commit: " + item.commit());
        lines.add("Place: " + item.place());
        lines.add("Task: " + item.task() + " on " + machine + ", run " + item.run());
        lines.add("Held at: " + item.at());
        return lines;
    }

    /**
     * Returns what an item's row shows beside its operation about how it reached the hold.
     *
     * @param item The item.
     * @return The daemon's word for its mark, or {@code null} when it came signed.
     */
    static @org.jspecify.annotations.Nullable String mark(HeldItem item) {
        return item.mark();
    }

    /**
     * Says what an item's mark means for the decision.
     *
     * @param mark The daemon's word for it.
     * @return One line.
     */
    static String marked(String mark) {
        if (mark.equals("unsigned")) {
            return UNSIGNED;
        }
        // A mark not known yet is still said, so nothing the daemon warns of is lost.
        return "Marked " + mark + " by Porelo, unknown to this plugin; weigh it before deciding.";
    }

    /**
     * Says where a comment stands, which thread a reply answers or a resolve marks, or which
     * operation this plugin does not know.
     *
     * @param item The item.
     * @return One line.
     */
    static String where(HeldItem item) {
        final HeldItem.Anchor anchor = item.anchor();
        return switch (item.operation()) {
            case "comment" ->
                    anchor == null
                            ? "Comment"
                            : "Comment on "
                                    + anchor.path()
                                    + ", "
                                    + anchor.side()
                                    + " side, "
                                    + (anchor.startLine() == anchor.endLine()
                                            ? "line " + anchor.startLine()
                                            : "lines "
                                                    + anchor.startLine()
                                                    + " to "
                                                    + anchor.endLine());
            case "reply" -> "Reply in thread " + item.thread();
            case "resolve" -> "Resolve thread " + item.thread();
            default -> "Operation " + item.operation() + ", unknown to this plugin";
        };
    }

    /**
     * Returns how an item's row starts, before its mark: a resolve by the thread it marks, anything
     * else by its operation.
     *
     * @param item The item.
     * @return The row's first words.
     */
    static String row(HeldItem item) {
        return item.operation().equals("resolve") ? where(item) : item.operation();
    }

    /**
     * Returns a text's first line as a row shows it after the mark.
     *
     * @param text The item's text.
     * @return Its first line, cut after 80 code points; empty for no text.
     */
    static String firstLine(String text) {
        final String line = text.lines().findFirst().orElse("");
        // Cut between code points, never inside a surrogate pair.
        return line.codePointCount(0, line.length()) <= 80
                ? line
                : line.substring(0, line.offsetByCodePoints(0, 80)) + "...";
    }

    /**
     * Says why an item offers no decision.
     *
     * @param item The item.
     * @return One line, or {@code null} when it offers its direction's decisions.
     */
    static @org.jspecify.annotations.Nullable String withheld(HeldItem item) {
        if (!item.decisions().isEmpty()) {
            return null;
        }
        final List<HeldItem.Decision> decisions = item.direction().decisions();
        final String both = decisions.getFirst().label() + " and " + decisions.getLast().label();
        if (HeldItem.KNOWN.contains(item.operation())) {
            return both
                    + " are withheld: a "
                    + item.operation()
                    + " comes only from a task's run, so one that came in cannot be shown as it"
                    + " would go.";
        }
        return both
                + " are withheld: this plugin cannot show what it would do; update the Porelo"
                + " plugin to decide it.";
    }

    /**
     * Says what a comment's proposed replacement replaces.
     *
     * @param item The item.
     * @return The heading above the replacement, or {@code null} when it proposes none.
     */
    static @org.jspecify.annotations.Nullable String replaces(HeldItem item) {
        final HeldItem.Anchor anchor = item.anchor();
        if (item.suggestion() == null) {
            return null;
        }
        return anchor == null
                ? "Proposed replacement:"
                : anchor.startLine() == anchor.endLine()
                        ? "Proposed replacement of line " + anchor.startLine() + ":"
                        : "Proposed replacement of lines "
                                + anchor.startLine()
                                + " to "
                                + anchor.endLine()
                                + ":";
    }
}
