package org.fuin.porelo.intellij.machine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.JsonException;
import org.jspecify.annotations.Nullable;

/**
 * Reads the machines the person set up in the interface, never writing them.
 *
 * <p>The interface keeps them in {@code $XDG_CONFIG_HOME/porelo/frontend.json}, under {@code
 * machines}, each with {@code name}, {@code socket}, and {@code host} with {@code remoteSocket} or
 * neither. An entry with a {@code kind} (a WSL distribution, with its {@code distribution}) is one
 * the plugin does not reach yet: it is kept and shown as such, never opened as a path. Only those
 * fields are read; the file holds the interface's other settings too. This computer's own daemon is
 * reached as well, with no tunnel, whether or not the file names it.
 */
public final class Machines {

    /** What this computer's daemon is called when the interface's file does not name it. */
    public static final String THIS_COMPUTER = "This computer";

    private final Map<String, String> environment;

    private final Path home;

    private final boolean windows;

    /**
     * Reads with the given environment, as on Linux, so a test needs nothing of the machine it runs
     * on.
     *
     * @param environment The variables, as {@link System#getenv()} gives them.
     * @param home The person's home directory.
     */
    public Machines(Map<String, String> environment, Path home) {
        this(environment, home, false);
    }

    /**
     * Reads with the given environment, on Linux or on Windows.
     *
     * @param environment The variables, as {@link System#getenv()} gives them.
     * @param home The person's home directory.
     * @param windows Whether the IDE runs on Windows, where the interface keeps its file under
     *     {@code APPDATA} and this computer has no daemon of its own.
     */
    public Machines(Map<String, String> environment, Path home, boolean windows) {
        this.environment = Map.copyOf(environment);
        this.home = home;
        this.windows = windows;
    }

    /**
     * Returns every machine the person reaches: those the interface names, in its order, and this
     * computer's daemon when none of them is it.
     *
     * @return The machines, never empty.
     * @throws MachinesException If the interface's file exists and cannot be read as it writes it.
     */
    public List<Machine> all() {
        return read().machines();
    }

    /**
     * Returns every machine the person reaches, as {@link #all()} does, and the entries the plugin
     * keeps but cannot reach: those of a {@code kind} it does not speak.
     *
     * @return Both, the interface's order kept in each.
     * @throws MachinesException If the interface's file exists and cannot be read as it writes it.
     */
    public Listed read() {
        final List<Machine> machines = new ArrayList<>();
        final List<Unreachable> unreachable = new ArrayList<>();
        named(machines, unreachable);
        if (windows) {
            // Porelo runs on Linux; on Windows its daemons are in WSL distributions, each an entry
            // of its own.
            return new Listed(machines, unreachable);
        }
        final Path local = localSocket();
        if (machines.stream()
                .noneMatch(machine -> !machine.needsATunnel() && local.equals(machine.socket()))) {
            machines.add(new Machine(THIS_COMPUTER, local, null, null));
        }
        return new Listed(machines, unreachable);
    }

    /**
     * What the interface's file names, read.
     *
     * @param machines The machines the plugin reaches, never empty.
     * @param unreachable The entries it keeps but cannot reach, each with why.
     */
    public record Listed(List<Machine> machines, List<Unreachable> unreachable) {

        /**
         * Copies the lists.
         *
         * @param machines The machines.
         * @param unreachable The entries it cannot reach.
         */
        public Listed {
            machines = List.copyOf(machines);
            unreachable = List.copyOf(unreachable);
        }
    }

    /**
     * An entry of the interface's file that the plugin keeps and shows, but does not reach.
     *
     * @param name What the person calls it.
     * @param why Why it is not reached, as a clause that follows its name.
     */
    public record Unreachable(String name, String why) {}

    /**
     * Returns the interface's file, where it keeps the machines.
     *
     * @return {@code $XDG_CONFIG_HOME/porelo/frontend.json}, or under {@code ~/.config} when that
     *     is not set.
     */
    public Path file() {
        if (windows) {
            final String roaming = set("APPDATA");
            return (roaming == null ? home.resolve("AppData").resolve("Roaming") : Path.of(roaming))
                    .resolve("porelo")
                    .resolve("frontend.json");
        }
        final String config = set("XDG_CONFIG_HOME");
        return (config == null ? home.resolve(".config") : Path.of(config))
                .resolve("porelo")
                .resolve("frontend.json");
    }

    /**
     * Returns this computer's daemon socket.
     *
     * @return {@code $XDG_RUNTIME_DIR/porelo/porelod.sock}, or under {@code /run/user/<uid>} when
     *     that is not set.
     */
    public Path localSocket() {
        return runtimeDirectory().resolve("porelo").resolve("porelod.sock");
    }

    /**
     * Returns where this person's sockets go.
     *
     * @return {@code $XDG_RUNTIME_DIR}, or {@code /run/user/<uid>} when that is not set.
     */
    public Path runtimeDirectory() {
        if (windows) {
            final String local = set("LOCALAPPDATA");
            return (local == null ? home.resolve("AppData").resolve("Local") : Path.of(local))
                    .resolve("porelo-intellij");
        }
        final String runtime = set("XDG_RUNTIME_DIR");
        return runtime == null ? Path.of("/run/user", uid()) : Path.of(runtime);
    }

    private void named(List<Machine> machines, List<Unreachable> unreachable) {
        final Path file = file();
        final String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException ex) {
            return;
        } catch (IOException ex) {
            throw new MachinesException("Cannot read " + file + ": " + ex.getMessage(), ex);
        }
        final Object root;
        try {
            root = Json.parse(text);
        } catch (JsonException ex) {
            throw new MachinesException(file + " is not JSON: " + ex.getMessage(), ex);
        }
        if (!(root instanceof Map<?, ?> settings)) {
            throw new MachinesException(file + " holds no object at its top");
        }
        final Object entries = settings.get("machines");
        if (entries == null) {
            return;
        }
        if (!(entries instanceof List<?> list)) {
            throw new MachinesException(file + ": 'machines' is not a list");
        }
        for (final Object entry : list) {
            if (!(entry instanceof Map<?, ?> fields)) {
                throw new MachinesException(file + ": a machine is not an object: " + entry);
            }
            final String name = text(fields.get("name"));
            final String kind = text(fields.get("kind"));
            if (name.isEmpty()) {
                // The interface drops a nameless entry itself, so it is nothing the person sees.
                continue;
            }
            final String distribution = text(fields.get("distribution"));
            if (kind.equals("wsl") && windows) {
                if (distribution.isEmpty()) {
                    unreachable.add(
                            new Unreachable(
                                    name,
                                    "is a WSL distribution entry that names no distribution"));
                } else {
                    machines.add(Machine.wsl(name, distribution));
                }
                continue;
            }
            if (!kind.isEmpty()) {
                unreachable.add(
                        new Unreachable(
                                name,
                                kind.equals("wsl")
                                        ? "is a WSL distribution ('"
                                                + distribution
                                                + "'), which this plugin does not reach yet"
                                        : "is of a kind this plugin does not know ('"
                                                + kind
                                                + "')"));
                continue;
            }
            if (windows && !text(fields.get("socket")).isEmpty()) {
                unreachable.add(
                        new Unreachable(
                                name,
                                text(fields.get("host")).isEmpty()
                                        ? "is a socket of a Linux machine, which Windows cannot open"
                                        : "is reached over ssh, which this plugin does not do from Windows yet"));
                continue;
            }
            final Machine machine = machine(file, name, fields);
            if (machine != null) {
                machines.add(machine);
            }
        }
    }

    private static @Nullable Machine machine(Path file, String name, Map<?, ?> fields) {
        final String socket = text(fields.get("socket"));
        // The interface drops these itself when it reads the file, so they are nothing the person
        // sees.
        if (socket.isEmpty()) {
            return null;
        }
        final String host = text(fields.get("host"));
        final String remoteSocket = text(fields.get("remoteSocket"));
        try {
            return new Machine(
                    name,
                    Path.of(socket),
                    host.isEmpty() ? null : host,
                    remoteSocket.isEmpty() ? null : remoteSocket);
        } catch (IllegalArgumentException ex) {
            throw new MachinesException(file + ": " + ex.getMessage(), ex);
        }
    }

    private static String text(@Nullable Object value) {
        return value instanceof String string ? string : "";
    }

    private @Nullable String set(String name) {
        // An empty variable is unset, as the interface reads it, and a relative one is no place at
        // all.
        final String value = environment.get(name);
        return value == null || value.isEmpty() || !Path.of(value).isAbsolute() ? null : value;
    }

    private String uid() {
        final Object uid;
        try {
            uid = Files.getAttribute(home, "unix:uid");
        } catch (IOException | UnsupportedOperationException ex) {
            throw new MachinesException(
                    "XDG_RUNTIME_DIR is not set, and the user id cannot be read from "
                            + home
                            + ": "
                            + ex.getMessage(),
                    ex);
        }
        return String.valueOf(uid);
    }
}
