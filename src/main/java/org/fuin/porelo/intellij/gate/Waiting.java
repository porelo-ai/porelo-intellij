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
     * Returns what waits grouped by project and repository, the group with the newest push first.
     *
     * <p>A group's label is {@code project / repository}; where the same one waits on several
     * machines, each group also names its machine, and only then.
     *
     * @return The groups, each with its pushes newest first.
     */
    public List<Group> groups() {
        final java.util.Map<String, java.util.Set<String>> machines = new java.util.HashMap<>();
        for (final Entry entry : pushes) {
            machines.computeIfAbsent(
                            entry.project() + " / " + entry.repository(),
                            key -> new java.util.HashSet<>())
                    .add(entry.machine().name());
        }
        final java.util.Map<String, List<Entry>> groups = new java.util.LinkedHashMap<>();
        for (final Entry entry : pushes) {
            final String place = entry.project() + " / " + entry.repository();
            final boolean several = machines.getOrDefault(place, java.util.Set.of()).size() > 1;
            final String label = several ? place + " on " + entry.machine().name() : place;
            groups.computeIfAbsent(label, key -> new ArrayList<>()).add(entry);
        }
        return groups.entrySet().stream()
                .map(group -> new Group(group.getKey(), group.getValue()))
                .toList();
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
     * @param mirror The path of the repository's gate mirror on the machine.
     * @param push The push.
     * @param farReaching How many of the files it changes reach far, as the daemon ranks them.
     */
    public record Entry(
            Machine machine,
            String project,
            String repository,
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
        final Waiting read = read(listed.machines(), connect);
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
        final List<Entry> pushes = new ArrayList<>();
        final List<Silent> silent = new ArrayList<>();
        for (final Machine machine : machines) {
            try (Gate gate = connect.apply(machine)) {
                pushes.addAll(at(machine, gate));
            } catch (RuntimeException ex) {
                silent.add(new Silent(machine.name(), "does not answer: " + ex.getMessage()));
            }
        }
        pushes.sort(Comparator.comparing((Entry entry) -> entry.push().at()).reversed());
        return new Waiting(pushes, silent);
    }

    private static List<Entry> at(Machine machine, Gate gate) {
        final List<Entry> pushes = new ArrayList<>();
        for (final Project project : gate.projects()) {
            for (final Project.Repository repository : project.repositories()) {
                if (repository.pending() == 0) {
                    continue;
                }
                final Gate.Pending pending = gate.pending(project.name(), repository.name());
                for (final PendingPush push : pending.pushes()) {
                    final int farReaching =
                            (int)
                                    gate
                                            .review(project.name(), repository.name(), push.name())
                                            .files()
                                            .stream()
                                            .filter(Review.File::farReaching)
                                            .count();
                    pushes.add(
                            new Entry(
                                    machine,
                                    project.name(),
                                    repository.name(),
                                    pending.mirror(),
                                    push,
                                    farReaching));
                }
            }
        }
        return pushes;
    }
}
