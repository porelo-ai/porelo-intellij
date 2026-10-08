package org.fuin.sokar.intellij.cache;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Bare repositories of the plugin's own, one per gate, under its cache directory.
 * <p>
 * A review fetches the waiting push and the gate mirror's default branch there, and reads both as git objects: with
 * no working tree there is nothing an IDE could open as a project, import, build or run, and no repository of the
 * person's is written to. git runs without a prompt, with the person's own configuration and ssh.
 */
public final class ReviewCache {

    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");

    /** A push's name is one path segment of a ref; the gate names it, and a name is still checked before git sees it. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private static final Pattern BRANCH = Pattern.compile("refs/heads/([A-Za-z0-9][A-Za-z0-9._/-]*)");

    private static final long TIMEOUT_SECONDS = 120;

    private final Path root;

    private final Map<String, String> environment;

    /**
     * Keeps the repositories under a directory.
     *
     * @param root The cache directory, such as {@code ~/.cache/sokar-intellij}.
     * @param environment Variables git runs with beyond the IDE's own, such as {@code GIT_CONFIG_GLOBAL} in a test.
     */
    public ReviewCache(Path root, Map<String, String> environment) {
        this.root = root;
        this.environment = Map.copyOf(environment);
    }

    /**
     * Returns the bare repository of one gate.
     *
     * @param machine The machine's name.
     * @param project The project's name.
     * @param repository The repository's name.
     * @return {@code <root>/<machine>/<project>/<repository>.git}, each part with every character but letters,
     *     digits, {@code _} and {@code -} made {@code -}.
     */
    public Path repository(String machine, String project, String repository) {
        return root.resolve(slug(machine)).resolve(slug(project)).resolve(slug(repository) + ".git");
    }

    /**
     * Fetches a waiting push and the branch it is read against, and says what it changes.
     *
     * @param repository The gate's bare repository in this cache.
     * @param url Where the gate's mirror is: its path on this computer, {@code ssh://<host><path>}, or
     *     {@code //wsl.localhost/<distribution><path>} on Windows.
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
        if (!Files.isDirectory(repository)) {
            try {
                Files.createDirectories(repository.getParent());
            } catch (IOException ex) {
                throw new CacheException("Cannot create " + repository.getParent() + ": " + ex.getMessage(), ex);
            }
            text(null, "init", "--quiet", "--bare", repository.toString());
        }
        final String branch = defaultBranch(repository, url);
        final String base = "refs/sokar/base/" + branch;
        final String incoming = "refs/sokar/incoming/" + name;
        text(repository, "fetch", "--quiet", "--no-tags", "--", url, "+refs/heads/" + branch + ":" + base,
                "+" + incoming + ":" + incoming);
        final String fetched = text(repository, "rev-parse", "--verify", incoming + "^{commit}").strip();
        if (!fetched.equals(commit)) {
            throw new CacheException(name + " now holds " + fetched + ", not " + commit
                    + ", which was shown; read what waits again");
        }
        final Result met = git(repository, "merge-base", base, commit);
        final String from = met.exit() == 0 ? met.text().strip() : text(repository, "rev-parse", base).strip();
        return new Changes(branch, from, commit, changes(repository, from, commit));
    }

    /**
     * Returns a file as it is in a commit.
     *
     * @param repository The gate's bare repository in this cache.
     * @param commit The commit.
     * @param path The file's path.
     * @return Its bytes, or {@code null} when the commit has no such file.
     */
    public byte @Nullable [] file(Path repository, String commit, String path) {
        if (!COMMIT.matcher(commit).matches()) {
            throw new CacheException("'" + commit + "' is not a full commit id");
        }
        final Result result = run(repository, "cat-file", "blob", commit + ":" + path);
        return result.exit() == 0 ? result.bytes() : null;
    }

    private String defaultBranch(Path repository, String url) {
        // The mirror's HEAD names its source's default branch, which is what the gate reads a push against.
        for (final String line : text(repository, "ls-remote", "--symref", "--", url, "HEAD").split("\n")) {
            if (line.startsWith("ref: ")) {
                final String ref = line.substring("ref: ".length()).split("\t", 2)[0];
                final var branch = BRANCH.matcher(ref);
                if (branch.matches() && !ref.contains("..")) {
                    return branch.group(1);
                }
                throw new CacheException("The mirror at " + url + " names '" + ref + "' as its HEAD");
            }
        }
        throw new CacheException("The mirror at " + url + " names no branch as its HEAD");
    }

    private List<Changes.Change> changes(Path repository, String from, String commit) {
        final List<Changes.Change> files = new ArrayList<>();
        final String[] fields = text(repository, "diff", "--no-renames", "--name-status", "-z", from, commit)
                .split("\0");
        for (int at = 0; at + 1 < fields.length; at += 2) {
            files.add(new Changes.Change(fields[at], fields[at + 1]));
        }
        return files;
    }

    private String text(@Nullable Path repository, String... arguments) {
        final Result result = git(repository, arguments);
        if (result.exit() != 0) {
            throw new CacheException("git " + String.join(" ", arguments) + " ended with " + result.exit() + ": "
                    + result.error().strip());
        }
        return result.text();
    }

    private Result git(@Nullable Path repository, String... arguments) {
        return run(repository, arguments);
    }

    private Result run(@Nullable Path repository, String... arguments) {
        final List<String> command = new ArrayList<>(List.of("git"));
        if (repository != null) {
            command.addAll(List.of("--git-dir", repository.toString()));
        }
        command.addAll(List.of(arguments));
        final ProcessBuilder builder = new ProcessBuilder(command);
        // No prompt can be answered from here: a fetch that would ask fails and says so.
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        if (List.of(arguments).stream().anyMatch(argument -> argument.startsWith("//wsl.localhost/"))) {
            // A gate mirror in a WSL distribution belongs to its Linux user, whom Windows' git sees as a stranger
            // and refuses to read from; trusted for this one call only, as a -c on its command line would be.
            builder.environment().put("GIT_CONFIG_COUNT", "1");
            builder.environment().put("GIT_CONFIG_KEY_0", "safe.directory");
            builder.environment().put("GIT_CONFIG_VALUE_0", "*");
        }
        builder.environment().putAll(environment);
        try {
            final Process git = builder.start();
            // Its standard input is closed at once: there is nothing to give it, and no /dev/null on Windows.
            git.getOutputStream().close();
            final ByteArrayOutputStream error = new ByteArrayOutputStream();
            final Thread errors = Thread.ofVirtual().start(() -> copy(git.getErrorStream(), error));
            final byte[] out = git.getInputStream().readAllBytes();
            if (!git.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                git.destroyForcibly();
                throw new CacheException("git " + String.join(" ", arguments) + " did not end within "
                        + TIMEOUT_SECONDS + " s");
            }
            errors.join();
            return new Result(git.exitValue(), out, error.toString(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new CacheException("Cannot run git: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CacheException("Interrupted while git ran", ex);
        }
    }

    private static void copy(InputStream from, ByteArrayOutputStream to) {
        try (from) {
            from.transferTo(to);
        } catch (IOException ex) {
            to.writeBytes(("[cannot read git's words: " + ex.getMessage() + "]").getBytes(StandardCharsets.UTF_8));
        }
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
