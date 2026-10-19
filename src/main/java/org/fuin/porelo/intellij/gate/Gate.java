package org.fuin.porelo.intellij.gate;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.jspecify.annotations.Nullable;

/**
 * One connection to a machine's daemon, for its gate.
 *
 * <p>Speaks {@code org.fuin.porelo.Tasks1} with {@code porelo-wire}, the client that defines it.
 * Every answer is checked for the fields this plugin reads, and one that lacks them is refused
 * rather than read as empty.
 */
public final class Gate implements AutoCloseable {

    /** The daemon's interface. */
    public static final String INTERFACE = "org.fuin.porelo.Tasks1";

    /** How long a read waits for the daemon's answer. */
    private static final Duration ANSWER = Duration.ofSeconds(30);

    private final VarlinkClient client;

    private final Duration answer;

    /**
     * Connects to a daemon's socket.
     *
     * @param socket The daemon's socket, or the plugin's end of a tunnel to it.
     * @throws GateException If nothing answers there.
     */
    public Gate(Path socket) {
        this(socket, ANSWER);
    }

    Gate(Path socket, Duration answer) {
        this.answer = answer;
        try {
            this.client = new VarlinkClient(socket);
        } catch (VarlinkException ex) {
            throw new GateException("Nothing answers at " + socket + ": " + causeOf(ex), ex);
        }
    }

    /**
     * Returns every project the machine knows, with its repositories and what waits in them.
     *
     * @return The projects.
     */
    public List<Project> projects() {
        return projectsOf(read("Projects", Map.of()));
    }

    private static List<Project> projectsOf(Map<String, Object> reply) {
        final List<Project> projects = new ArrayList<>();
        for (final Map<String, Object> project : objects(reply, "projects")) {
            final List<Project.Repository> repositories = new ArrayList<>();
            for (final Map<String, Object> repository : objects(project, "repositories")) {
                repositories.add(
                        new Project.Repository(
                                string(repository, "name"),
                                flag(repository, "own"),
                                string(repository, "upstream"),
                                number(repository, "pending")));
            }
            projects.add(
                    new Project(string(project, "name"), repositories, number(project, "pending")));
        }
        return projects;
    }

    /**
     * Follows the daemon's stream of projects, handing over every answer as {@link #projects()}
     * reads one: the whole list again each time it changed. Returns when the daemon ends the
     * stream; it has no time limit of its own, and {@link #close()} from another thread ends it.
     *
     * @param each Gets every answer, on this thread.
     * @throws GateException If the daemon refuses the call, or the stream is cut off.
     */
    public void watch(java.util.function.Consumer<List<Project>> each) {
        try {
            client.callMore(
                    INTERFACE + ".WatchProjects",
                    Map.of(),
                    reply -> {
                        each.accept(projectsOf(reply));
                        return true;
                    });
        } catch (VarlinkException ex) {
            throw refusal("WatchProjects", ex);
        }
    }

    /**
     * Returns what waits at one repository's gate, newest first, and where its mirror is.
     *
     * @param project The project's name.
     * @param repository The repository's name.
     * @return The gate's mirror and the pushes that wait.
     */
    public Pending pending(String project, String repository) {
        final Map<String, Object> reply = read("Pending", parameters(project, repository));
        final List<PendingPush> pushes = new ArrayList<>();
        for (final Map<String, Object> push : objects(reply, "pending")) {
            pushes.add(
                    new PendingPush(
                            string(push, "name"),
                            string(push, "commit"),
                            string(push, "subject"),
                            string(push, "waiting"),
                            string(push, "at"),
                            string(push, "fetch")));
        }
        pushes.sort((one, other) -> other.at().compareTo(one.at()));
        return new Pending(string(reply, "mirror"), pushes);
    }

    /**
     * What waits at one repository's gate.
     *
     * @param mirror The path of the gate's bare mirror on the machine, which a review fetches from.
     * @param pushes The pushes that wait, newest first.
     */
    public record Pending(String mirror, List<PendingPush> pushes) {

        /**
         * Copies the list.
         *
         * @param mirror The mirror's path.
         * @param pushes The pushes.
         */
        public Pending {
            pushes = List.copyOf(pushes);
        }
    }

    /**
     * Returns what a pending push changes, ranked.
     *
     * @param project The project's name.
     * @param repository The repository's name.
     * @param name The push's name.
     * @return What it changes.
     */
    public Review review(String project, String repository, String name) {
        final Map<String, Object> call = parameters(project, repository);
        call.put("name", name);
        final Map<String, Object> reply = read("Review", call);
        final List<Review.File> files = new ArrayList<>();
        for (final Map<String, Object> file : objects(reply, "files")) {
            files.add(
                    new Review.File(
                            string(file, "path"),
                            string(file, "status"),
                            number(file, "added"),
                            number(file, "removed"),
                            string(file, "rank"),
                            string(file, "reason")));
        }
        // Absent when the task is gone; anything but text is no answer to what it asked.
        final Object asked = reply.get("asked");
        if (asked != null && !(asked instanceof String)) {
            throw new GateException("The daemon answered 'asked' with no text: " + shown(reply));
        }
        return new Review(string(reply, "diff"), string(reply, "log"), files, (String) asked);
    }

    /**
     * Forwards a pending push onto a branch, exactly the commit that was shown.
     *
     * @param project The project's name.
     * @param repository The repository's name.
     * @param name The push's name.
     * @param branch The branch it goes onto; never guessed.
     * @param commit The full commit id the person reviewed.
     * @return What was forwarded, as the daemon says it.
     * @throws MovedSinceReviewException If the push moved after it was shown; nothing was
     *     forwarded.
     * @throws BranchExistsException If the branch holds a commit the work did not grow from;
     *     nothing was forwarded.
     */
    public String approve(
            String project, String repository, String name, String branch, String commit) {
        final Map<String, Object> call = parameters(project, repository);
        call.put("name", name);
        call.put("branch", branch);
        call.put("commit", commit);
        final Map<String, Object> reply = call("Approve", call);
        return string(reply, "forwarded") + " onto " + string(reply, "branch");
    }

    /**
     * Drops a pending push and tells the task that pushed it why.
     *
     * @param project The project's name.
     * @param repository The repository's name.
     * @param name The push's name.
     * @param reason The person's words, which reach the task.
     * @return Whether the task was told; {@code false} when it is gone from the machine.
     */
    public boolean reject(String project, String repository, String name, String reason) {
        final Map<String, Object> call = parameters(project, repository);
        call.put("name", name);
        call.put("reason", reason);
        final Map<String, Object> reply = call("Reject", call);
        if (!(reply.get("told") instanceof Boolean told)) {
            throw new GateException("Reject answered without 'told': " + shown(reply));
        }
        return told;
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (IOException ex) {
            throw new GateException(
                    "Cannot close the connection to the daemon: " + ex.getMessage(), ex);
        }
    }

    // A read is bounded: a daemon that takes the call and never answers would otherwise hold every
    // machine read after it. Approving and rejecting are not: the daemon bounds its own push to the
    // forge, which may rightly take longer than any read.
    private Map<String, Object> read(String method, Map<String, Object> parameters) {
        final CompletableFuture<Map<String, Object>> answered = new CompletableFuture<>();
        Thread.ofVirtual()
                .start(
                        () -> {
                            try {
                                answered.complete(call(method, parameters));
                            } catch (RuntimeException ex) {
                                answered.completeExceptionally(ex);
                            }
                        });
        try {
            return answered.get(answer.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            // Closing the connection ends the read that waits on it.
            closeQuietly();
            throw new GateException(
                    method + " did not answer within " + answer.toSeconds() + " s", ex);
        } catch (InterruptedException ex) {
            closeQuietly();
            Thread.currentThread().interrupt();
            throw new GateException(method + " was interrupted", ex);
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException failed) {
                throw failed;
            }
            throw new GateException(method + " failed: " + ex.getCause(), ex);
        }
    }

    private void closeQuietly() {
        try {
            client.close();
        } catch (IOException ex) {
            // The connection is given up either way.
        }
    }

    private Map<String, Object> call(String method, Map<String, Object> parameters) {
        try {
            return client.call(INTERFACE + "." + method, parameters);
        } catch (VarlinkException ex) {
            throw refusal(method, ex);
        }
    }

    private static GateException refusal(String method, VarlinkException ex) {
        final String error = ex.getErrorName();
        if (error == null) {
            return new GateException(method + " failed: " + causeOf(ex), ex);
        }
        final @Nullable Map<String, Object> parameters = ex.getParameters();
        final Map<String, Object> answer = parameters == null ? Map.of() : parameters;
        if (error.equals(INTERFACE + ".MovedSinceReview")
                && answer.get("name") instanceof String name
                && answer.get("reviewed") instanceof String reviewed
                && answer.get("now") instanceof String now) {
            return new MovedSinceReviewException(name, reviewed, now);
        }
        // Without its fields it cannot be said in words; then the daemon's own words below stand.
        if (error.equals(INTERFACE + ".BranchExists")
                && answer.get("branch") instanceof String branch
                && answer.get("at") instanceof String at) {
            return new BranchExistsException(branch, at);
        }
        return new GateException(
                method
                        + " was refused: "
                        + error.substring(error.lastIndexOf('.') + 1)
                        + (answer.isEmpty() ? "" : " " + shown(answer)),
                ex);
    }

    private static Map<String, Object> parameters(String project, String repository) {
        final Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("project", project);
        parameters.put("repository", repository);
        return parameters;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(Map<String, Object> reply, String field) {
        if (!(reply.get(field) instanceof List<?> list)) {
            throw new GateException("The daemon answered without '" + field + "': " + shown(reply));
        }
        final List<Map<String, Object>> objects = new ArrayList<>();
        for (final Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) {
                throw new GateException(
                        "The daemon's '"
                                + field
                                + "' holds something that is not an object: "
                                + shown(entry));
            }
            objects.add((Map<String, Object>) map);
        }
        return objects;
    }

    private static String string(Map<String, Object> reply, String field) {
        if (!(reply.get(field) instanceof String value)) {
            throw new GateException("The daemon answered without '" + field + "': " + shown(reply));
        }
        return value;
    }

    private static boolean flag(Map<String, Object> reply, String field) {
        if (!(reply.get(field) instanceof Boolean value)) {
            throw new GateException("The daemon answered without '" + field + "': " + shown(reply));
        }
        return value;
    }

    // A count is whole and not negative, and one an int cannot hold is refused, never cut to fit.
    private static int number(Map<String, Object> reply, String field) {
        if (!(reply.get(field) instanceof Number value)) {
            throw new GateException("The daemon answered without '" + field + "': " + shown(reply));
        }
        final double count = value.doubleValue();
        if (count != Math.rint(count) || count < 0 || count > Integer.MAX_VALUE) {
            throw new GateException(
                    "The daemon answered '" + field + "' with " + value + ", which is no count");
        }
        return value.intValue();
    }

    // An answer can carry a whole diff; a message shows its start.
    private static String shown(Object answer) {
        final String text = String.valueOf(answer);
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }

    private static String causeOf(Throwable ex) {
        final @Nullable Throwable cause = ex.getCause();
        return cause == null || cause.getMessage() == null
                ? String.valueOf(ex.getMessage())
                : ex.getMessage() + " (" + cause.getMessage() + ")";
    }
}
