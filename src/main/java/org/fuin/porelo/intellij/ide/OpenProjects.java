package org.fuin.porelo.intellij.ide;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Which open projects hold a gate's upstream: a git root of theirs with a remote that names the
 * same address, however it is written.
 *
 * <p>Read without the IDE, from what each root's {@code git config} says, so the plugin needs no
 * part of IntelliJ's git plugin.
 */
final class OpenProjects {

    private OpenProjects() {}

    /**
     * One git root of an open project, with its remotes.
     *
     * @param project The project's name, as its window shows it.
     * @param root The git root's directory.
     * @param remotes Each remote's name and the address it fetches from.
     */
    record Root(String project, Path root, Map<String, String> remotes) {

        /**
         * Copies the map, keeping its order.
         *
         * @param project The project's name.
         * @param root The git root.
         * @param remotes The remotes.
         */
        Root {
            remotes = java.util.Collections.unmodifiableMap(new TreeMap<>(remotes));
        }
    }

    /**
     * A git root that holds the upstream, and the remote that names it there.
     *
     * @param project The project's name.
     * @param root The git root.
     * @param remote The remote's name.
     */
    record Match(String project, Path root, String remote) {}

    /**
     * Returns every root that holds the upstream, in the order given, with the first remote of each
     * that names it.
     *
     * @param upstream Where the gate's approved work goes, or {@code ""} when it stays on the
     *     machine, which no project holds.
     * @param roots The open projects' git roots.
     * @return The matches.
     */
    static List<Match> holding(String upstream, List<Root> roots) {
        final List<Match> matches = new ArrayList<>();
        if (upstream.isBlank()) {
            return matches;
        }
        final String wanted = address(upstream);
        for (final Root root : roots) {
            root.remotes().entrySet().stream()
                    .filter(remote -> address(remote.getValue()).equals(wanted))
                    .findFirst()
                    .ifPresent(
                            remote ->
                                    matches.add(
                                            new Match(
                                                    root.project(), root.root(), remote.getKey())));
        }
        return matches;
    }

    /**
     * Reads a git root's remotes from what {@code git config --get-regexp ^remote\..*\.url$}
     * prints: one {@code remote.<name>.url <address>} a line, where a name may hold dots.
     *
     * @param config What git printed.
     * @return Each remote's name and address, in git's order.
     */
    static Map<String, String> remotes(String config) {
        final Map<String, String> remotes = new LinkedHashMap<>();
        for (final String line : config.split("\n")) {
            final int space = line.indexOf(' ');
            if (space < 0) {
                continue;
            }
            final String key = line.substring(0, space);
            if (key.startsWith("remote.") && key.endsWith(".url")) {
                remotes.put(
                        key.substring("remote.".length(), key.length() - ".url".length()),
                        line.substring(space + 1).strip());
            }
        }
        return remotes;
    }

    /**
     * Writes an address the one way every form of it compares equal: the host in lower case and the
     * path without {@code .git} or slashes around it; the user, the protocol and its default port
     * left out. A local path stays a path.
     */
    private static String address(String url) {
        String rest = url.strip();
        String host;
        final int scheme = rest.indexOf("://");
        if (scheme >= 0) {
            final String protocol = rest.substring(0, scheme).toLowerCase(Locale.ROOT);
            rest = rest.substring(scheme + 3);
            if (protocol.equals("file")) {
                return "file:" + path(rest);
            }
            final int slash = rest.indexOf('/');
            host = slash < 0 ? rest : rest.substring(0, slash);
            rest = slash < 0 ? "" : rest.substring(slash);
            host = withoutDefaultPort(withoutUser(host), protocol);
        } else {
            // scp-like: [user@]host:path, as long as no slash comes before the colon.
            final int colon = rest.indexOf(':');
            final int slash = rest.indexOf('/');
            if (colon <= 0 || (slash >= 0 && slash < colon)) {
                return "file:" + path(rest);
            }
            host = withoutUser(rest.substring(0, colon));
            rest = rest.substring(colon + 1);
        }
        return host.toLowerCase(Locale.ROOT) + "/" + path(rest);
    }

    private static String withoutUser(String host) {
        return host.substring(host.lastIndexOf('@') + 1);
    }

    private static String withoutDefaultPort(String host, String protocol) {
        final String port =
                switch (protocol) {
                    case "ssh", "git+ssh", "ssh+git" -> ":22";
                    case "https" -> ":443";
                    case "http" -> ":80";
                    case "git" -> ":9418";
                    default -> "";
                };
        return !port.isEmpty() && host.endsWith(port)
                ? host.substring(0, host.length() - port.length())
                : host;
    }

    private static String path(String path) {
        String trimmed = path.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith(".git")) {
            trimmed = trimmed.substring(0, trimmed.length() - ".git".length());
        }
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed;
    }
}
