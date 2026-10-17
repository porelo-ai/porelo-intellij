package org.fuin.porelo.intellij.cache;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Bare repositories of the plugin's own, one per gate, under its cache directory.
 *
 * <p>A review fetches the waiting push and the gate mirror's default branch there, and reads both
 * as git objects: with no working tree there is nothing an IDE could open as a project, import,
 * build or run, and no repository of the person's is written to. git runs without a prompt, with
 * the person's own configuration and ssh.
 */
public final class ReviewCache {

    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");

    /**
     * A push's name is one path segment of a ref; the gate names it, and a name is still checked
     * before git sees it.
     */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private static final Pattern BRANCH =
            Pattern.compile("refs/heads/([A-Za-z0-9][A-Za-z0-9._/-]*)");

    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    /** What ssh runs as when the person named no ssh of their own: never asking at a terminal. */
    private static final String SSH_WITHOUT_PROMPT = "ssh -o BatchMode=yes";

    /**
     * Two reviews of one gate at once, from two clicks or two projects, take turns in its cache.
     */
    private static final ConcurrentMap<Path, Object> TURNS = new ConcurrentHashMap<>();

    private final Path root;

    private final Map<String, String> environment;

    private final Duration timeout;

    /**
     * Keeps the repositories under a directory.
     *
     * @param root The cache directory, such as {@code ~/.cache/porelo-intellij}.
     * @param environment Variables git runs with beyond the IDE's own, such as {@code
     *     GIT_CONFIG_GLOBAL} in a test.
     */
    public ReviewCache(Path root, Map<String, String> environment) {
        this(root, environment, TIMEOUT);
    }

    ReviewCache(Path root, Map<String, String> environment, Duration timeout) {
        this.root = root;
        this.environment = Map.copyOf(environment);
        this.timeout = timeout;
    }

    /**
     * Returns the bare repository of one gate.
     *
     * @param machine The machine's name.
     * @param project The project's name.
     * @param repository The repository's name.
     * @return {@code <root>/<machine>/<project>/<repository>.git}, each part with every character
     *     but letters, digits, {@code _} and {@code -} made {@code -}.
     */
    public Path repository(String machine, String project, String repository) {
        return root.resolve(slug(machine))
                .resolve(slug(project))
                .resolve(slug(repository) + ".git");
    }

    /**
     * Fetches a waiting push and the branch it is read against, and says what it changes.
     *
     * @param repository The gate's bare repository in this cache.
     * @param url Where the gate's mirror is: its path on this computer, {@code ssh://<host><path>},
     *     or {@code //wsl.localhost/<distribution><path>} on Windows.
     * @param name The push's name.
     * @param commit The commit that was shown, which is what the changes are of.
     * @return What the commit changes against where it and the branch last met.
     * @throws CacheException If git fails, or the push no longer holds the commit that was shown.
     */
    public Changes fetch(Path repository, String url, String name, String commit) {
        if (!NAME.matcher(name).matches()) {
            throw new CacheException("The push's name '" + name + "' is not a name the gate gives");
        }
        if (!COMMIT.matcher(commit).matches()) {
            throw new CacheException("'" + commit + "' is not a full commit id");
        }
        synchronized (
                TURNS.computeIfAbsent(
                        repository.toAbsolutePath().normalize(), key -> new Object())) {
            return fetched(repository, url, name, commit);
        }
    }

    private Changes fetched(Path repository, String url, String name, String commit) {
        if (!Files.isDirectory(repository)) {
            try {
                Files.createDirectories(repository.getParent());
            } catch (IOException ex) {
                throw new CacheException(
                        "Cannot create " + repository.getParent() + ": " + ex.getMessage(), ex);
            }
            text(null, "init", "--quiet", "--bare", repository.toString());
        }
        final String branch = defaultBranch(repository, url);
        final String base = "refs/porelo/base/" + branch;
        final String incoming = "refs/porelo/incoming/" + name;
        text(
                repository,
                "fetch",
                "--quiet",
                "--no-tags",
                "--",
                url,
                "+refs/heads/" + branch + ":" + base,
                "+" + incoming + ":" + incoming);
        final String fetched =
                text(repository, "rev-parse", "--verify", incoming + "^{commit}").strip();
        if (!fetched.equals(commit)) {
            throw new CacheException(
                    name
                            + " now holds "
                            + fetched
                            + ", not "
                            + commit
                            + ", which was shown; read what waits again");
        }
        // merge-base ends with 1 when the two never met, and anything else is a failure, not that.
        final Result met = run(repository, null, "merge-base", base, commit);
        final String from;
        if (met.exit() == 0) {
            from = met.text().strip();
        } else if (met.exit() == 1) {
            from = text(repository, "rev-parse", base).strip();
        } else {
            throw failed(new String[] {"merge-base", base, commit}, met);
        }
        return new Changes(branch, from, commit, changes(repository, from, commit));
    }

    /**
     * Returns a file as it is in a commit.
     *
     * @param repository The gate's bare repository in this cache.
     * @param commit The commit.
     * @param path The file's path.
     * @return Its bytes, or {@code null} when the commit has no such file.
     * @throws CacheException If git cannot read the repository or the commit.
     */
    public byte @Nullable [] file(Path repository, String commit, String path) {
        return files(repository, commit, List.of(path)).get(path);
    }

    /**
     * Returns files as they are in a commit, all read by one git.
     *
     * @param repository The gate's bare repository in this cache.
     * @param commit The commit.
     * @param paths The files' paths.
     * @return Each path's bytes, or {@code null} for a path the commit has no file at.
     * @throws CacheException If git cannot read the repository or the commit.
     */
    public Map<String, byte @Nullable []> files(
            Path repository, String commit, List<String> paths) {
        if (!COMMIT.matcher(commit).matches()) {
            throw new CacheException("'" + commit + "' is not a full commit id");
        }
        // A missing repository or commit would make every file read as absent.
        text(repository, "rev-parse", "--quiet", "--verify", commit + "^{commit}");
        final StringBuilder asked = new StringBuilder();
        for (final String path : paths) {
            if (path.indexOf('\n') >= 0) {
                throw new CacheException("A path holds a line break: " + path);
            }
            asked.append(commit).append(':').append(path).append('\n');
        }
        final Result result =
                run(
                        repository,
                        asked.toString().getBytes(StandardCharsets.UTF_8),
                        "cat-file",
                        "--batch");
        if (result.exit() != 0) {
            throw failed(new String[] {"cat-file", "--batch"}, result);
        }
        final Map<String, byte @Nullable []> files = new HashMap<>();
        final byte[] out = result.bytes();
        int at = 0;
        for (final String path : paths) {
            final int end = indexOf(out, (byte) '\n', at);
            if (end < 0) {
                throw new CacheException("git cat-file ended before " + path);
            }
            final String header = new String(out, at, end - at, StandardCharsets.UTF_8);
            at = end + 1;
            if (header.endsWith(" missing")) {
                files.put(path, null);
                continue;
            }
            final String[] words = header.split(" ");
            if (words.length != 3) {
                throw new CacheException("git cat-file answered '" + header + "' for " + path);
            }
            final int size = Integer.parseInt(words[2]);
            if (at + size > out.length) {
                throw new CacheException("git cat-file ended within " + path);
            }
            files.put(
                    path,
                    words[1].equals("blob")
                            ? java.util.Arrays.copyOfRange(out, at, at + size)
                            : null);
            at += size + 1;
        }
        return files;
    }

    private String defaultBranch(Path repository, String url) {
        // The mirror's HEAD names its source's default branch, which is what the gate reads a push
        // against.
        for (final String line :
                text(repository, "ls-remote", "--symref", "--", url, "HEAD").split("\n")) {
            if (line.startsWith("ref: ")) {
                final String ref = line.substring("ref: ".length()).split("\t", 2)[0];
                final var branch = BRANCH.matcher(ref);
                if (branch.matches() && !ref.contains("..")) {
                    return branch.group(1);
                }
                throw new CacheException(
                        "The mirror at " + url + " names '" + ref + "' as its HEAD");
            }
        }
        throw new CacheException("The mirror at " + url + " names no branch as its HEAD");
    }

    private List<Changes.Change> changes(Path repository, String from, String commit) {
        final List<Changes.Change> files = new ArrayList<>();
        final String[] fields =
                text(repository, "diff", "--no-renames", "--name-status", "-z", from, commit)
                        .split("\0");
        for (int at = 0; at + 1 < fields.length; at += 2) {
            files.add(new Changes.Change(fields[at], fields[at + 1]));
        }
        return files;
    }

    private String text(@Nullable Path repository, String... arguments) {
        final Result result = run(repository, null, arguments);
        if (result.exit() != 0) {
            throw failed(arguments, result);
        }
        return result.text();
    }

    private static CacheException failed(String[] arguments, Result result) {
        return new CacheException(
                "git "
                        + String.join(" ", arguments)
                        + " ended with "
                        + result.exit()
                        + ": "
                        + result.error().strip());
    }

    private Result run(@Nullable Path repository, byte @Nullable [] input, String... arguments) {
        final List<String> command = new ArrayList<>(List.of("git"));
        if (repository != null) {
            command.addAll(List.of("--git-dir", repository.toString()));
        }
        command.addAll(List.of(arguments));
        final ProcessBuilder builder = new ProcessBuilder(command);
        // No prompt can be answered from here: a fetch that would ask fails and says so.
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        if (List.of(arguments).stream()
                .anyMatch(argument -> argument.startsWith("//wsl.localhost/"))) {
            // A gate mirror in a WSL distribution belongs to its Linux user, whom Windows' git sees
            // as a stranger and refuses to read from; trusted for this one call only, as a -c on
            // its command line would be.
            builder.environment().put("GIT_CONFIG_COUNT", "1");
            builder.environment().put("GIT_CONFIG_KEY_0", "safe.directory");
            builder.environment().put("GIT_CONFIG_VALUE_0", "*");
        }
        builder.environment().putAll(environment);
        if (List.of(arguments).stream().anyMatch(argument -> argument.startsWith("ssh://"))
                && !builder.environment().containsKey("GIT_SSH_COMMAND")
                && !builder.environment().containsKey("GIT_SSH")
                && run(null, null, "config", "--get", "core.sshCommand").exit() != 0) {
            // A host key to accept or a passphrase to type would wait at the IDE's terminal for
            // good; the person's own ssh, if they named one, is theirs to set up.
            builder.environment().put("GIT_SSH_COMMAND", SSH_WITHOUT_PROMPT);
        }
        final Process git;
        try {
            git = builder.start();
        } catch (IOException ex) {
            throw new CacheException("Cannot run git: " + ex.getMessage(), ex);
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream error = new ByteArrayOutputStream();
        final AtomicReference<@Nullable IOException> unread = new AtomicReference<>();
        // Every stream on a thread of its own, so the wait below is what bounds git, not a read.
        final Thread output =
                Thread.ofVirtual().start(() -> copy(git.getInputStream(), out, unread));
        final Thread errors =
                Thread.ofVirtual()
                        .start(() -> copy(git.getErrorStream(), error, new AtomicReference<>()));
        Thread.ofVirtual().start(() -> give(git, input));
        try {
            if (!git.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                end(git);
                throw new CacheException(
                        "git "
                                + String.join(" ", arguments)
                                + " did not end within "
                                + timeout.toSeconds()
                                + " s");
            }
            output.join(timeout.toMillis());
            errors.join(timeout.toMillis());
        } catch (InterruptedException ex) {
            end(git);
            Thread.currentThread().interrupt();
            throw new CacheException("Interrupted while git ran", ex);
        }
        final IOException lost = unread.get();
        if (lost != null) {
            throw new CacheException("Cannot read what git said: " + lost.getMessage(), lost);
        }
        return new Result(
                git.exitValue(), out.toByteArray(), error.toString(StandardCharsets.UTF_8));
    }

    /** Ends git and what it started, such as ssh, which would otherwise outlive it. */
    private static void end(Process git) {
        git.descendants().forEach(ProcessHandle::destroyForcibly);
        git.destroyForcibly();
    }

    private static void give(Process git, byte @Nullable [] input) {
        // Closed when given, or at once: there is nothing else to give, and no /dev/null on
        // Windows. A git that ended first says why in its exit, so a write it refused is no news.
        try (var to = git.getOutputStream()) {
            if (input != null) {
                to.write(input);
            }
        } catch (IOException ex) {
            // See above.
        }
    }

    private static void copy(
            InputStream from,
            ByteArrayOutputStream to,
            AtomicReference<@Nullable IOException> lost) {
        try (from) {
            from.transferTo(to);
        } catch (IOException ex) {
            lost.set(ex);
        }
    }

    private static int indexOf(byte[] bytes, byte wanted, int from) {
        for (int at = from; at < bytes.length; at++) {
            if (bytes[at] == wanted) {
                return at;
            }
        }
        return -1;
    }

    private static String slug(String name) {
        return name.replaceAll("[^A-Za-z0-9_-]", "-");
    }

    private record Result(int exit, byte[] bytes, String error) {

        String text() {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
