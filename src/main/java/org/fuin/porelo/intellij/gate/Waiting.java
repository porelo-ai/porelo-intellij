package org.fuin.porelo.intellij.gate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import org.fuin.porelo.intellij.machine.Machine;
import org.fuin.porelo.intellij.machine.Machines;

/**
 * Everything that waits at the gates of every machine the person reaches, newest first, and the
 * machines that did not answer - which are said, never shown as having nothing.
 *
 * @param pushes What waits, newest first.
 * @param silent The machines that did not answer, each with why.
 */
public record Waiting(List<Entry> pushes, List<Silent> silent) {

    /**
     * Copies the lists.
     *
     * @param pushes What waits.
     * @param silent The machines that did not answer.
     */
    public Waiting {
        pushes = List.copyOf(pushes);
        silent = List.copyOf(silent);
    }

    /**
     * Returns what waits now and did not wait before: a push that is new, or one that moved to
     * another commit.
     *
     * <p>A machine that was silent before and answers now has nothing new to say for that alone;
     * its pushes are new only if they were never seen.
     *
     * @param before What waited when last read.
     * @return What is new, newest first.
     */
    public List<Entry> newSince(Waiting before) {
        final java.util.Set<String> seen = new java.util.HashSet<>();
        before.pushes().forEach(entry -> seen.add(entry.key()));
        return pushes.stream().filter(entry -> !seen.contains(entry.key())).toList();
    }

    /**
     * Returns what has been seen once this is read: what waits now, and what waited before on the
     * machines that did not answer now, so a machine silent for one read has nothing new to say
     * when it answers again.
     *
     * @param before What had been seen before.
     * @return What has been seen.
     */
    public Waiting seenAfter(Waiting before) {
        final java.util.Set<String> silentNow = new java.util.HashSet<>();
        silent.forEach(machine -> silentNow.add(machine.machine()));
        final List<Entry> seen = new ArrayList<>(pushes);
        before.pushes().stream()
                .filter(entry -> silentNow.contains(entry.machine().name()))
                .forEach(seen::add);
        return new Waiting(seen, silent);
    }

    /**
     * Tells whether a machine's projects say something else waits now than before: a repository
     * whose count of waiting pushes moved, one that appeared with pushes, or one that went while
     * pushes waited in it. Any other change, such as a task started, says nothing of the gate.
     *
     * @param before The projects the machine answered last.
     * @param now The projects it answers now.
     * @return Whether its gates are to be read again.
     */
    public static boolean pendingMoved(List<Project> before, List<Project> now) {
        return !counts(before).equals(counts(now));
    }

    private static java.util.Map<String, Integer> counts(List<Project> projects) {
        final java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (final Project project : projects) {
            for (final Project.Repository repository : project.repositories()) {
                if (repository.pending() > 0) {
                    counts.put(project.name() + "\u0000" + repository.name(), repository.pending());
                }
            }
        }
        return counts;
    }

    /**
     * Returns what waits once one machine was read again: its pushes and its silence replaced by
     * what that read says, everything of the other machines as it was.
     *
     * @param machine The machine's name.
     * @param read What reading that machine alone said.
     * @return What waits now, newest first.
     */
    public Waiting withMachine(String machine, Waiting read) {
        final List<Entry> merged = new ArrayList<>();
        pushes.stream()
                .filter(entry -> !entry.machine().name().equals(machine))
                .forEach(merged::add);
        merged.addAll(read.pushes());
        merged.sort(Comparator.comparing((Entry entry) -> entry.push().at()).reversed());
        final List<Silent> quiet = new ArrayList<>();
        silent.stream().filter(one -> !one.machine().equals(machine)).forEach(quiet::add);
        quiet.addAll(read.silent());
        return new Waiting(merged, quiet);
    }

    /**
     * Says what the push an open review shows is in this read: the same, moved to another commit,
     * gone from a machine that answered, or unknown because its machine did not answer.
     *
     * <p>A machine that is not silent answered, even with nothing waiting: the read lists every
     * machine the plugin reaches either by its pushes or among the silent.
     *
     * @param shown The push as the review was opened with it.
     * @return What it is now.
     */
    public Now now(Entry shown) {
        final String machine = shown.machine().name();
        if (silent.stream().anyMatch(one -> one.machine().equals(machine))) {
            return new Now.Unknown();
        }
        for (final Entry entry : pushes) {
            if (entry.machine().name().equals(machine)
                    && entry.project().equals(shown.project())
                    && entry.repository().equals(shown.repository())
                    && entry.push().name().equals(shown.push().name())) {
                return entry.push().commit().equals(shown.push().commit())
                        ? new Now.Same()
                        : new Now.Moved(entry);
            }
        }
        return new Now.Gone();
    }

    /** What the push an open review shows is in a newer read. */
    public sealed interface Now {

        /** It still waits at the commit shown. */
        record Same() implements Now {}

        /**
         * It waits at another commit.
         *
         * @param entry The push as it waits now.
         */
        record Moved(Entry entry) implements Now {}

        /** Its machine answered, and it waits no more: decided elsewhere. */
        record Gone() implements Now {}

        /** Its machine did not answer, or nothing was read: nothing is said. */
        record Unknown() implements Now {}
    }

    /**
     * Returns what waits grouped by project and repository, the group with the newest push first.
     *
     * <p>A group's label is {@code project / repository}; where the same one waits on several
     * machines, each group also names its machine, and only then.
     *
     * @return The groups, each with its pushes newest first.
     */
    public List<Group> groups() {
        // Keyed apart from the label, which two places can share, such as project 'a / b' with
        // repository 'c' and project 'a' with repository 'b / c'.
        final java.util.Map<String, java.util.Set<String>> machines = new java.util.HashMap<>();
        for (final Entry entry : pushes) {
            machines.computeIfAbsent(place(entry), key -> new java.util.HashSet<>())
                    .add(entry.machine().name());
        }
        final java.util.Map<String, List<Entry>> groups = new java.util.LinkedHashMap<>();
        for (final Entry entry : pushes) {
            groups.computeIfAbsent(
                            place(entry)
                                    + (several(machines, entry)
                                            ? "\u0000" + entry.machine().name()
                                            : ""),
                            key -> new ArrayList<>())
                    .add(entry);
        }
        return groups.values().stream()
                .map(
                        group -> {
                            final Entry first = group.getFirst();
                            final String label = first.project() + " / " + first.repository();
                            return new Group(
                                    several(machines, first)
                                            ? label + " on " + first.machine().name()
                                            : label,
                                    group);
                        })
                .toList();
    }

    private static boolean several(
            java.util.Map<String, java.util.Set<String>> machines, Entry entry) {
        return machines.getOrDefault(place(entry), java.util.Set.of()).size() > 1;
    }

    private static String place(Entry entry) {
        return entry.project() + "\u0000" + entry.repository();
    }

    /**
     * Returns what waits grouped by machine and, under each, by project and repository; the machine
     * with the newest push first, and only machines where something waits.
     *
     * @return The machines, each with its groups, which name no machine.
     */
    public List<MachineGroup> byMachine() {
        final java.util.Map<String, List<Entry>> machines = new java.util.LinkedHashMap<>();
        for (final Entry entry : pushes) {
            machines.computeIfAbsent(entry.machine().name(), key -> new ArrayList<>()).add(entry);
        }
        return machines.entrySet().stream()
                .map(
                        machine ->
                                new MachineGroup(
                                        machine.getKey(),
                                        new Waiting(machine.getValue(), List.of()).groups()))
                .toList();
    }

    /**
     * What waits on one machine.
     *
     * @param machine The machine's name.
     * @param groups Its projects and repositories where something waits.
     */
    public record MachineGroup(String machine, List<Group> groups) {

        /**
         * Copies the list.
         *
         * @param machine The machine's name.
         * @param groups The groups.
         */
        public MachineGroup {
            groups = List.copyOf(groups);
        }
    }

    /**
     * The pushes waiting at one repository's gate.
     *
     * @param label {@code project / repository}, with the machine where that alone does not say
     *     which.
     * @param pushes Its pushes, newest first.
     */
    public record Group(String label, List<Entry> pushes) {

        /**
         * Copies the list.
         *
         * @param label The label.
         * @param pushes The pushes.
         */
        public Group {
            pushes = List.copyOf(pushes);
        }
    }

    /**
     * One push that waits, and where.
     *
     * @param machine The machine whose gate it waits at.
     * @param project The project's name.
     * @param repository The repository's name.
     * @param upstream Where the repository's approved work goes, as its gate says, or {@code ""}
     *     when it stays on the machine.
     * @param mirror The path of the repository's gate mirror on the machine.
     * @param push The push.
     * @param farReaching How many of the files it changes reach far, as the daemon ranks them.
     */
    public record Entry(
            Machine machine,
            String project,
            String repository,
            String upstream,
            String mirror,
            PendingPush push,
            int farReaching) {

        /**
         * Says where a review fetches this push from.
         *
         * <p>Over the person's ssh to the host the plugin tunnels to; for a WSL distribution, from
         * the path Windows reaches it at; from the mirror's path when the machine is this computer;
         * and for a socket somebody else forwards, from the ssh address the gate itself names.
         *
         * @param here Tells whether a directory exists on this computer.
         * @return A path or an {@code ssh://} address.
         * @throws GateException If the gate names no address that can be fetched from.
         */
        public String source(java.util.function.Predicate<String> here) {
            // The path is appended to a host or a distribution; anything but an absolute path of
            // its own would make it another host, port or distribution.
            if (!mirror.startsWith("/") || List.of(mirror.split("/")).contains("..")) {
                throw new GateException(
                        "The gate of "
                                + machine.name()
                                + " names the mirror '"
                                + mirror
                                + "', which is no absolute path of its own");
            }
            if (machine.distribution() != null) {
                return "//wsl.localhost/" + machine.distribution() + mirror;
            }
            if (machine.host() != null) {
                return "ssh://" + machine.host() + mirror;
            }
            if (here.test(mirror)) {
                return mirror;
            }
            // "git fetch <address> <refspec>", as the gate writes it for a person to run.
            final String[] words = push.fetch().strip().split("\\s+");
            if (words.length == 4
                    && words[0].equals("git")
                    && words[1].equals("fetch")
                    && words[2].startsWith("ssh://")
                    && words[2].endsWith(mirror)) {
                return words[2];
            }
            throw new GateException(
                    "The mirror "
                            + mirror
                            + " of "
                            + machine.name()
                            + " is not on this computer, and"
                            + " the gate names no ssh address for it: "
                            + push.fetch());
        }

        private String key() {
            return String.join(
                    "\u0000", machine.name(), project, repository, push.name(), push.commit());
        }
    }

    /**
     * A machine that did not answer, or that the plugin does not reach.
     *
     * @param machine What the person calls it.
     * @param why A clause that follows its name: what failed, in the words of whatever failed, or
     *     why it is not reached.
     */
    public record Silent(String machine, String why) {}

    /**
     * Asks every machine the plugin reaches what waits at its gates, as {@link #read(List,
     * Function)} does, and says the entries it keeps but does not reach after the machines that did
     * not answer.
     *
     * @param listed The machines, as the interface's file names them.
     * @param connect Opens a gate to a machine.
     * @return What waits, and who did not answer or is not reached.
     */
    public static Waiting read(Machines.Listed listed, Function<Machine, Gate> connect) {
        return read(listed, connect, new Waiting(List.of(), List.of()));
    }

    /**
     * Asks every machine the plugin reaches what waits, as {@link #read(Machines.Listed, Function)}
     * does, taking how far a push reaches from what was read before where it is the same push at
     * the same commit.
     *
     * @param listed The machines, as the interface's file names them.
     * @param connect Opens a gate to a machine.
     * @param before What was read last.
     * @return What waits, and who did not answer or is not reached.
     */
    public static Waiting read(
            Machines.Listed listed, Function<Machine, Gate> connect, Waiting before) {
        final Waiting read = read(listed.machines(), connect, before);
        final List<Silent> silent = new ArrayList<>(read.silent());
        listed.unreachable().forEach(entry -> silent.add(new Silent(entry.name(), entry.why())));
        return new Waiting(read.pushes(), silent);
    }

    /**
     * Asks every machine what waits at its gates.
     *
     * <p>Only a repository that says something waits is asked what; each push is reviewed for its
     * ranking. A machine that fails anywhere is silent as a whole, so its list is never shown half
     * read.
     *
     * @param machines The machines.
     * @param connect Opens a gate to a machine; the caller keeps what it needs, such as a tunnel,
     *     and this closes only the gate.
     * @return What waits, and who did not answer.
     */
    public static Waiting read(List<Machine> machines, Function<Machine, Gate> connect) {
        return read(machines, connect, new Waiting(List.of(), List.of()));
    }

    /**
     * Asks machines what waits at their gates, as {@link #read(List, Function)} does, taking how
     * far a push reaches from what was read before where it is the same push at the same commit.
     *
     * @param machines The machines.
     * @param connect Opens a gate to a machine.
     * @param before What was read last.
     * @return What waits, and who did not answer.
     */
    public static Waiting read(
            List<Machine> machines, Function<Machine, Gate> connect, Waiting before) {
        final java.util.Map<String, Integer> known = new java.util.HashMap<>();
        before.pushes().forEach(entry -> known.put(entry.key(), entry.farReaching()));
        final List<Entry> pushes = new ArrayList<>();
        final List<Silent> silent = new ArrayList<>();
        for (final Machine machine : machines) {
            final List<Entry> read;
            Gate gate = null;
            try {
                gate = connect.apply(machine);
                read = at(machine, gate, known);
            } catch (RuntimeException ex) {
                // An interrupted read is no machine that did not answer; it is given up whole.
                if (Thread.currentThread().isInterrupted()) {
                    throw new GateException("Interrupted while reading what waits", ex);
                }
                silent.add(new Silent(machine.name(), "does not answer: " + ex.getMessage()));
                continue;
            } finally {
                if (gate != null) {
                    close(gate);
                }
            }
            pushes.addAll(read);
        }
        pushes.sort(Comparator.comparing((Entry entry) -> entry.push().at()).reversed());
        return new Waiting(pushes, silent);
    }

    // A machine read in full stays read, even if closing its connection then fails.
    private static void close(Gate gate) {
        try {
            gate.close();
        } catch (RuntimeException ex) {
            // See above.
        }
    }

    private static List<Entry> at(
            Machine machine, Gate gate, java.util.Map<String, Integer> known) {
        final List<Entry> pushes = new ArrayList<>();
        for (final Project project : gate.projects()) {
            for (final Project.Repository repository : project.repositories()) {
                if (repository.pending() == 0) {
                    continue;
                }
                final Gate.Pending pending = gate.pending(project.name(), repository.name());
                for (final PendingPush push : pending.pushes()) {
                    final Entry unranked =
                            new Entry(
                                    machine,
                                    project.name(),
                                    repository.name(),
                                    repository.upstream(),
                                    pending.mirror(),
                                    push,
                                    0);
                    // A push at the same commit changes the same files: its whole diff is read
                    // once, not on every read.
                    final Integer ranked = known.get(unranked.key());
                    final int farReaching =
                            ranked != null
                                    ? ranked
                                    : (int)
                                            gate
                                                    .review(
                                                            project.name(),
                                                            repository.name(),
                                                            push.name())
                                                    .files()
                                                    .stream()
                                                    .filter(Review.File::farReaching)
                                                    .count();
                    pushes.add(
                            new Entry(
                                    machine,
                                    project.name(),
                                    repository.name(),
                                    repository.upstream(),
                                    pending.mirror(),
                                    push,
                                    farReaching));
                }
            }
        }
        return pushes;
    }
}
