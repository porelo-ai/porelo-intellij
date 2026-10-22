package org.fuin.porelo.intellij.machine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A socket of the plugin's own that reaches the daemon in a WSL distribution: each connection to it
 * starts {@code wsl.exe -d <distribution> --exec porelo daemon connect}, a process with its own
 * byte streams, and joins the two.
 *
 * <p>No shell is in between, on Windows or in the distribution, so nothing re-encodes the bytes,
 * and no port is opened. {@code porelo daemon connect} finds the account's socket under {@code
 * /run/user/<uid>} without a login's environment. A distribution that is not running is never
 * started: it is refused with what {@code wsl.exe -l -v} says of it.
 */
public final class WslBridge implements AutoCloseable {

    private final Relay relay;

    private WslBridge(Relay relay) {
        this.relay = relay;
    }

    /**
     * Opens the bridge once the distribution is seen running and its daemon answers.
     *
     * @param machine A machine in a WSL distribution.
     * @param runtimeDirectory Where the plugin's end goes.
     * @param wsl The {@code wsl.exe} command, {@code wsl.exe} unless a test stands in for it.
     * @return The bridge, open.
     * @throws MachinesException If the distribution is not there, not WSL2 or not running, or its
     *     daemon does not answer - with {@code wsl.exe}'s own words.
     */
    public static WslBridge open(Machine machine, Path runtimeDirectory, List<String> wsl) {
        final String distribution =
                Objects.requireNonNull(machine.distribution(), "a machine outside WSL");
        // wsl.exe would read a name that starts with '-' as one of its own options.
        if (distribution.startsWith("-")) {
            throw new MachinesException(
                    "Machine '"
                            + machine.name()
                            + "' names the distribution '"
                            + distribution
                            + "', which wsl.exe would read as an option");
        }
        running(machine, distribution, wsl);
        final List<String> connect =
                List.of("-d", distribution, "--exec", "porelo", "daemon", "connect");
        final List<String> command = new ArrayList<>(wsl);
        command.addAll(connect);
        return new WslBridge(
                Relay.open(machine, runtimeDirectory, command, String.join(" ", connect)));
    }

    /**
     * Reads what {@code wsl.exe -l -v} prints: one row per distribution, its name, state and WSL
     * version. The state is in the language Windows speaks, and may be more than one word; the name
     * is the first and the version the last.
     *
     * @param printed What it printed, in UTF-16LE as {@code wsl.exe} prints by default, or in
     *     UTF-8.
     * @return Each row as {@code [name, state, version]}, the default's {@code *} taken off.
     */
    static List<String[]> distributions(byte[] printed) {
        // UTF-8 text never holds a NUL byte; UTF-16LE text of ASCII names holds one in every
        // second.
        final String text =
                new String(
                                printed,
                                utf16(printed) ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_8)
                        .replace("﻿", "");
        final List<String[]> rows = new ArrayList<>();
        for (final String line : text.split("\\R")) {
            final String[] words = line.replace("*", " ").strip().split("\\s+");
            // The heading is in Windows' language too, and the only row whose version is no number.
            if (words.length >= 3 && words[words.length - 1].matches("[0-9]+")) {
                rows.add(
                        new String[] {
                            words[0],
                            String.join(" ", List.of(words).subList(1, words.length - 1)),
                            words[words.length - 1]
                        });
            }
        }
        return rows;
    }

    private static boolean utf16(byte[] printed) {
        boolean utf16 = false;
        for (final byte b : printed) {
            utf16 |= b == 0;
        }
        return utf16;
    }

    /**
     * Returns the plugin's end, which a gate connects to.
     *
     * @return The socket.
     */
    public Path socket() {
        return relay.socket();
    }

    /**
     * Tells whether the bridge still takes connections.
     *
     * @return {@code true} until it is closed.
     */
    public boolean up() {
        return relay.up();
    }

    @Override
    public void close() {
        relay.close();
    }

    private static void running(Machine machine, String distribution, List<String> wsl) {
        final List<String> list = new ArrayList<>(wsl);
        list.addAll(List.of("-l", "-v"));
        final Relay.Ran ran = Relay.run(list, null);
        if (ran.exit() != 0) {
            throw new MachinesException(
                    "wsl.exe -l -v ended with exit code "
                            + ran.exit()
                            + " for '"
                            + machine.name()
                            + "': "
                            + ran.said());
        }
        for (final String[] row : distributions(ran.out())) {
            if (!row[0].equals(distribution)) {
                continue;
            }
            if (!row[2].equals("2")) {
                throw new MachinesException(
                        "'"
                                + machine.name()
                                + "' is the WSL "
                                + row[2]
                                + " distribution '"
                                + distribution
                                + "'; Porelo needs WSL 2 (wsl --set-version "
                                + distribution
                                + " 2)");
            }
            // English names the state itself; any other language is asked the running ones' names.
            final boolean running =
                    row[1].equalsIgnoreCase("Running")
                            || !row[1].equalsIgnoreCase("Stopped")
                                    && runningByName(distribution, wsl);
            if (!running) {
                throw new MachinesException(
                        "'"
                                + machine.name()
                                + "': the WSL distribution '"
                                + distribution
                                + "' is "
                                + row[1].toLowerCase(java.util.Locale.ROOT)
                                + ", and the plugin never starts one; start it,"
                                + " then it is read again");
            }
            return;
        }
        throw new MachinesException(
                "'"
                        + machine.name()
                        + "': this computer has no WSL distribution '"
                        + distribution
                        + "'");
    }

    // The state is named in Windows' language; the running ones by name alone are the same in all.
    private static boolean runningByName(String distribution, List<String> wsl) {
        final List<String> list = new ArrayList<>(wsl);
        list.addAll(List.of("-l", "--running", "-q"));
        final Relay.Ran ran = Relay.run(list, null);
        final String printed =
                new String(
                        ran.out(),
                        utf16(ran.out()) ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_8);
        return printed.replace("\uFEFF", "")
                .lines()
                .map(String::strip)
                .anyMatch(distribution::equals);
    }
}
