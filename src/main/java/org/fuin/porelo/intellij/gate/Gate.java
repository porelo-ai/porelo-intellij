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
import java.util.regex.Pattern;
import org.fuin.porelo.wire.LimitFactors;
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

    /**
     * How long an approval or rejection waits: the daemon bounds its own push to the forge, which
     * may rightly take longer than any read.
     */
    private static final Duration FORWARD = Duration.ofMinutes(10);

    /** What the daemon takes as a comment's id. */
    private static final Pattern COMMENT_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    // A mark is a name of the interface's enum; anything else could pose as the plugin's own words.
    private static final Pattern MARK = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

    /** The calls whose refusals are a {@link ReviewRefusedException}. */
    private static final java.util.Set<String> COMMENT_CALLS =
            java.util.Set.of("Comment", "Comments", "ResolveThread");

    /** The daemon's refusals of those calls, each said in words. */
    private static final java.util.Set<String> COMMENT_REFUSALS =
            java.util.Set.of(
                    "NoSuchPush",
                    "ProfileViolation",
                    "ForbiddenField",
                    "TooLarge",
                    "UnsafeText",
                    "AnchorOutsideBlob",
                    "FixOutsideAnchor",
                    "OldSideFix",
                    "NoSuchResult",
                    "NoSuchThread",
                    "CursorRefused",
                    "IdReused");

    private final VarlinkClient client;

    private volatile Duration answer;

    private volatile Duration forward;

    /**
     * Connects to a daemon's socket.
     *
     * @param socket The daemon's socket, or the plugin's end of a tunnel to it.
     * @throws GateException If nothing answers there.
     */
    public Gate(Path socket) {
        this(socket, LimitFactors.DEFAULT);
    }

    /**
     * Connects to a daemon's socket, waiting on its answers by the factors last read from its
     * machine until {@link #limits()} reads its own.
     *
     * @param socket The daemon's socket, or the plugin's end of a tunnel to it.
     * @param factors The factors last read from the machine, or all four at 1.0.
     * @throws GateException If nothing answers there.
     */
    public Gate(Path socket, LimitFactors factors) {
        this(socket, factors.services(ANSWER), factors.services(FORWARD));
    }

    Gate(Path socket, Duration answer) {
        this(socket, answer, FORWARD);
    }

    private Gate(Path socket, Duration answer, Duration forward) {
        this.answer = answer;
        this.forward = forward;
        try {
            this.client = new VarlinkClient(socket);
        } catch (VarlinkException ex) {
            throw new GateException("Nothing answers at " + socket + ": " + causeOf(ex), ex);
        }
    }

    /**
     * Reads the factors every time limit follows, as the daemon read them at its start, and waits
     * on this connection's later answers by its {@code services} factor.
     *
     * @return The factors.
     * @throws GateException If the daemon does not answer them, or answers no limits line naming
     *     all four.
     */
    public LimitFactors limits() {
        final String line = string(read("Limits", Map.of()), "line");
        if (line.isEmpty()) {
            throw new GateException("The daemon's limits line '' does not name all four factors");
        }
        final LimitFactors factors;
        try {
            factors = LimitFactors.parse(line);
        } catch (IllegalArgumentException ex) {
            throw new GateException(
                    "The daemon's limits line '" + shown(line) + "' is refused: " + ex.getMessage(),
                    ex);
        }
        // The grammar takes a name left out as 1.0; porelod writes all four, so a line without them
        // is no daemon's.
        if (line.split(",", -1).length != 4) {
            throw new GateException(
                    "The daemon's limits line '"
                            + shown(line)
                            + "' does not name all four factors");
        }
        answer = factors.services(ANSWER);
        forward = factors.services(FORWARD);
        return factors;
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
                            string(push, "fetch"),
                            optionalString(push, "sourceLevel"),
                            optionalString(push, "sourceWords"),
                            optionalString(push, "riskClass"),
                            approvalsNeeded(push),
                            optionalString(push, "task"),
                            push.get("review") == null ? null : summary(object(push, "review"))));
        }
        pushes.sort((one, other) -> other.at().compareTo(one.at()));
        return new Pending(string(reply, "mirror"), pushes);
    }

    // Fewer than one would read as approval needing nobody.
    private static @Nullable Integer approvalsNeeded(Map<String, Object> push) {
        if (push.get("approvalsNeeded") == null) {
            return null;
        }
        final int needed = number(push, "approvalsNeeded");
        if (needed < 1) {
            throw new GateException(
                    "The daemon answered 'approvalsNeeded' with "
                            + needed
                            + ", which needs nobody");
        }
        return needed;
    }

    private static ReviewSummary summary(Map<String, Object> review) {
        final List<ReviewSummary.Run> runs = new ArrayList<>();
        for (final Map<String, Object> run : objects(review, "runs")) {
            runs.add(
                    new ReviewSummary.Run(
                            string(run, "run"),
                            string(run, "name"),
                            string(run, "kind"),
                            string(run, "state"),
                            number(run, "findings"),
                            number(run, "openFindings")));
        }
        return new ReviewSummary(
                number(review, "round"),
                number(review, "openFindings"),
                number(review, "openThreads"),
                number(review, "heldInbound"),
                number(review, "waitingOutbound"),
                runs);
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
     * Returns what a pending push changes, ranked, with every file however many pages the daemon
     * sends them in.
     *
     * @param project The project's name.
     * @param repository The repository's name.
     * @param name The push's name.
     * @param commit The commit the push was listed with; every page is read for it.
     * @return What it changes.
     * @throws MovedSinceReviewException If the push moved while its pages were read.
     * @throws GateException If the pages do not add up to the files the daemon says there are.
     */
    public Review review(String project, String repository, String name, String commit) {
        final Map<String, Object> call = parameters(project, repository);
        call.put("name", name);
        final Map<String, Object> reply = read("Review", call);
        final List<Review.File> files = filesOf(objects(reply, "files"));
        if (reply.get("paged") != null) {
            final Map<String, Object> paged = object(reply, "paged");
            final int whole = number(paged, "files");
            // The base Review read against, never a default resolved again for a later page.
            final String base = string(paged, "base");
            @Nullable Integer next = optionalNumber(paged, "filesNext");
            while (next != null) {
                // A page starts where the files read so far end, so none is read twice or left out.
                if (next != files.size() || files.size() >= whole) {
                    throw new GateException(
                            "The daemon's review of "
                                    + name
                                    + " asks for files from "
                                    + next
                                    + " after "
                                    + files.size()
                                    + " of "
                                    + whole);
                }
                final Map<String, Object> page = parameters(project, repository);
                page.put("name", name);
                page.put("commit", commit);
                page.put("against", base);
                page.put("ignoreLineEndings", false);
                page.put("part", "files");
                page.put("from", next);
                final Map<String, Object> answer = read("ReviewPage", page);
                final List<Review.File> more = filesOf(objects(answer, "files"));
                // Every page brings a file, so pages end within the count the daemon named.
                if (more.isEmpty()) {
                    throw new GateException(
                            "The daemon's review of "
                                    + name
                                    + " sent a page of files without a file");
                }
                files.addAll(more);
                next = optionalNumber(answer, "next");
            }
            if (files.size() != whole) {
                throw new GateException(
                        "The daemon's review of "
                                + name
                                + " holds "
                                + files.size()
                                + " files, but says it has "
                                + whole);
            }
        }
        // Absent when the task is gone; anything but text is no answer to what it asked.
        final Object asked = reply.get("asked");
        if (asked != null && !(asked instanceof String)) {
            throw new GateException("The daemon answered 'asked' with no text: " + shown(reply));
        }
        return new Review(
                string(reply, "diff"),
                string(reply, "log"),
                files,
                (String) asked,
                optionalString(reply, "base"));
    }

    /**
     * Stores one comment on a pending push, here only: it never goes to the forge.
     *
     * <p>Bounded like a read: without the forge the daemon only stores, and a retry with the same
     * id answers the comment already stored, never a second one.
     *
     * @param project The project's name.
     * @param repository The repository's name.
     * @param name The push's name.
     * @param sarif One SARIF document holding exactly one result, as a person's client sends it.
     * @param replyTo The guid of the result it answers; {@code null} for a new thread.
     * @param id The caller's id for this comment, {@code [A-Za-z0-9._-]{1,64}}, the same on every
     *     retry of it.
     * @return The result as the daemon stored it.
     * @throws IllegalArgumentException If the id is outside that grammar; nothing was sent.
     * @throws MovedSinceReviewException If the push moved after it was shown; nothing was stored.
     * @throws ReviewRefusedException If the daemon refused the comment; nothing was stored.
     */
    public Stored comment(
            String project,
            String repository,
            String name,
            String sarif,
            @Nullable String replyTo,
            String id) {
        if (!COMMENT_ID.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "A comment's id is 1 to 64 of A-Z, a-z, 0-9, '.', '_' and '-': '" + id + "'");
        }
        final Map<String, Object> call = parameters(project, repository);
        call.put("name", name);
        call.put("sarif", sarif);
        if (replyTo != null) {
            call.put("replyTo", replyTo);
        }
        call.put("id", id);
        final Map<String, Object> reply = read("Comment", call);
        return new Stored(string(reply, "guid"), string(reply, "thread"), string(reply, "sarif"));
    }

    /**
     * A comment as the daemon stored it.
     *
     * @param guid The daemon's id of the stored result.
     * @param thread The thread it is in.
     * @param sarif The stored result as one SARIF document, as the daemon stamped it.
     */
    public record Stored(String guid, String thread, String sarif) {}

    /**
     * Reads every page of a pending push's comment threads: the owner account's and its tasks',
     * whatever review runs the push has.
     *
     * <p>No count of pages bounds it, since a large project's review may yield any number; a daemon
     * that hands new cursors forever is ended only by interrupting the reading thread.
     *
     * @param project The project's name.
     * @param repository The repository's name.
     * @param name The push's name.
     * @return Each page's SARIF document, in the daemon's order; only the last speaks for the
     *     threads' completeness.
     * @throws GateException If the daemon answers a cursor it answered before, or the reading
     *     thread is interrupted.
     */
    public List<String> comments(String project, String repository, String name) {
        final List<String> pages = new ArrayList<>();
        final java.util.Set<String> cursors = new java.util.HashSet<>();
        @Nullable String cursor = null;
        do {
            final Map<String, Object> call = parameters(project, repository);
            call.put("name", name);
            if (cursor != null) {
                call.put("cursor", cursor);
            }
            final Map<String, Object> reply = read("Comments", call);
            pages.add(string(reply, "sarif"));
            cursor = optionalString(reply, "cursor");
            if (cursor != null && !cursors.add(cursor)) {
                throw new GateException(
                        "The threads of "
                                + name
                                + " answered the cursor "
                                + shown(cursor)
                                + " again");
            }
        } while (cursor != null);
        return pages;
    }

    /**
     * Resolves or reopens a thread of a pending push. It only shows the thread resolved: it clears
     * no finding and approves nothing.
     *
     * @param project The project's name.
     * @param repository The repository's name.
     * @param name The push's name.
     * @param thread The thread's id.
     * @param reopen {@code true} reopens a resolved thread.
     * @return Whether the thread is resolved now.
     * @throws ReviewRefusedException If the daemon refused; nothing changed.
     */
    public boolean resolveThread(
            String project, String repository, String name, String thread, boolean reopen) {
        final Map<String, Object> call = parameters(project, repository);
        call.put("name", name);
        call.put("thread", thread);
        if (reopen) {
            call.put("reopen", true);
        }
        final Map<String, Object> reply = read("ResolveThread", call);
        if (!thread.equals(string(reply, "thread"))) {
            throw new GateException(
                    "ResolveThread of " + thread + " answered for another thread: " + shown(reply));
        }
        return switch (string(reply, "state")) {
            case "resolved" -> true;
            case "open" -> false;
            default ->
                    throw new GateException(
                            "ResolveThread answered a state that is neither open nor resolved: "
                                    + shown(reply));
        };
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
        final Map<String, Object> reply = call("Approve", call, forward);
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
        final Map<String, Object> reply = call("Reject", call, forward);
        if (!(reply.get("told") instanceof Boolean told)) {
            throw new GateException("Reject answered without 'told': " + shown(reply));
        }
        return told;
    }

    /**
     * Follows the daemon's stream of tasks, handing over how many items each task holds for the
     * owner account's decision: every task's count again each time the list changed. Returns when
     * the daemon ends the stream; {@link #close()} from another thread ends it.
     *
     * @param each Gets every answer, each task's name with its count, on this thread; the count is
     *     {@code null} where the daemon cannot read the task's holds, which is unknown, never none.
     * @throws GateException If the daemon refuses the call, or the stream is cut off.
     */
    public void watchHeld(java.util.function.Consumer<Map<String, @Nullable Integer>> each) {
        try {
            client.callMore(
                    INTERFACE + ".Watch",
                    Map.of(),
                    reply -> {
                        final Map<String, @Nullable Integer> held = new LinkedHashMap<>();
                        for (final Map<String, Object> task : objects(reply, "tasks")) {
                            held.put(string(task, "name"), optionalNumber(task, "held"));
                        }
                        each.accept(held);
                        return true;
                    });
        } catch (VarlinkException ex) {
            throw refusal("Watch", ex);
        }
    }

    /**
     * Reads every item held for the owner account's decision that is not decided yet, however many
     * pages the daemon sends them in.
     *
     * @param task The task whose items are read; {@code null} for every task on the machine.
     * @return The items, in the daemon's order, and the tasks whose holds the daemon cannot read.
     * @throws GateException If the daemon answers a cursor it answered before, or an item lacks a
     *     field this plugin shows.
     */
    public HeldList heldItems(@Nullable String task) {
        final List<HeldItem> items = new ArrayList<>();
        // A task may be named on every page; it is said once.
        final java.util.Set<String> unreadable = new java.util.LinkedHashSet<>();
        final java.util.Set<String> cursors = new java.util.HashSet<>();
        @Nullable String cursor = null;
        do {
            final Map<String, Object> call = new LinkedHashMap<>();
            if (task != null) {
                call.put("task", task);
            }
            if (cursor != null) {
                call.put("cursor", cursor);
            }
            final Map<String, Object> reply = read("HeldItems", call);
            for (final Map<String, Object> item : objects(reply, "items")) {
                items.add(heldItem(item));
            }
            unreadable.addAll(optionalStrings(reply, "unreadable"));
            cursor = optionalString(reply, "cursor");
            if (cursor != null && !cursors.add(cursor)) {
                throw new GateException(
                        "HeldItems answered the cursor " + shown(cursor) + " again");
            }
        } while (cursor != null);
        return new HeldList(items, List.copyOf(unreadable));
    }

    // Absent is none; anything but a list of text is no answer to what it asked.
    private static List<String> optionalStrings(Map<String, Object> reply, String field) {
        final Object value = reply.get(field);
        if (value == null) {
            return List.of();
        }
        final List<String> texts = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (final Object entry : list) {
                if (!(entry instanceof String text)) {
                    throw new GateException(
                            "The daemon's '"
                                    + field
                                    + "' holds something that is not text: "
                                    + shown(reply));
                }
                texts.add(text);
            }
            return texts;
        }
        throw new GateException(
                "The daemon answered '" + field + "' with no list: " + shown(reply));
    }

    private static HeldItem heldItem(Map<String, Object> item) {
        final String way = string(item, "direction");
        final HeldItem.Direction direction =
                switch (way) {
                    case "outgoing" -> HeldItem.Direction.OUTGOING;
                    case "incoming" -> HeldItem.Direction.INCOMING;
                    default ->
                            throw new GateException(
                                    "The daemon answered a held item going neither out nor in: "
                                            + shown(item));
                };
        // An incoming item is shown by the forge account it came from; without it, who wrote it is
        // unknown.
        final @Nullable String from =
                direction == HeldItem.Direction.INCOMING
                        ? string(item, "from")
                        : optionalString(item, "from");
        final String operation = string(item, "operation");
        // What a release sends is shown whole: a comment without its lines, or a reply without its
        // thread, could not be shown as it would go out.
        final HeldItem.@Nullable Anchor anchor;
        final @Nullable String thread;
        switch (operation) {
            case "comment" -> {
                anchor = anchor(item);
                thread = null;
            }
            case "reply" -> {
                anchor = null;
                thread = string(item, "thread");
            }
            default ->
                    throw new GateException(
                            "The daemon answered a held item that is neither a comment nor a reply: "
                                    + shown(item));
        }
        return new HeldItem(
                string(item, "id"),
                string(item, "task"),
                string(item, "place"),
                direction,
                string(item, "destination"),
                operation,
                anchor,
                thread,
                optionalString(item, "suggestion"),
                string(item, "commit"),
                string(item, "text"),
                string(item, "digest"),
                from,
                string(item, "at"),
                number(item, "run"),
                mark(item));
    }

    private static @Nullable String mark(Map<String, Object> item) {
        final @Nullable String mark = optionalString(item, "mark");
        if (mark != null && !MARK.matcher(mark).matches()) {
            throw new GateException(
                    "The daemon answered a held item whose mark is no word: " + shown(item));
        }
        return mark;
    }

    private static HeldItem.Anchor anchor(Map<String, Object> item) {
        final String side = string(item, "side");
        final int start = number(item, "startLine");
        final int end = number(item, "endLine");
        if (!side.equals("old") && !side.equals("new")) {
            throw new GateException(
                    "The daemon answered a comment on neither the old nor the new side: "
                            + shown(item));
        }
        if (start < 1 || end < start) {
            throw new GateException(
                    "The daemon answered a comment on lines "
                            + start
                            + " to "
                            + end
                            + ": "
                            + shown(item));
        }
        return new HeldItem.Anchor(string(item, "path"), side, start, end);
    }

    /**
     * Decides one held item, as it was shown: the digest read with it goes with the decision, so an
     * item that changed since is not decided.
     *
     * @param item The item as it was shown.
     * @param decision What is decided; it must be one its direction takes.
     * @throws IllegalArgumentException If the decision is not one of its direction's; nothing was
     *     sent.
     * @throws ItemRefusedException If the daemon refused it; nothing was decided.
     */
    public void decideItem(HeldItem item, HeldItem.Decision decision) {
        if (!item.direction().decisions().contains(decision)) {
            throw new IllegalArgumentException(
                    "An "
                            + item.direction().wire()
                            + " item is not decided with "
                            + decision.wire());
        }
        final Map<String, Object> call = new LinkedHashMap<>();
        call.put("task", item.task());
        call.put("id", item.id());
        call.put("digest", item.digest());
        call.put("decision", decision.wire());
        read("DecideItem", call);
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
    // machine read after it.
    private Map<String, Object> read(String method, Map<String, Object> parameters) {
        final Duration answer = this.answer;
        final CompletableFuture<Map<String, Object>> answered = new CompletableFuture<>();
        Thread.ofVirtual()
                .start(
                        () -> {
                            try {
                                answered.complete(call(method, parameters, answer));
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

    // The client bounds every call by its own default unless given one, so each names its own.
    private Map<String, Object> call(
            String method, Map<String, Object> parameters, Duration deadline) {
        try {
            return client.call(INTERFACE + "." + method, parameters, deadline);
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
            return COMMENT_CALLS.contains(method)
                    ? new MovedSinceReviewException(name, reviewed, now, "nothing was stored")
                    : new MovedSinceReviewException(name, reviewed, now);
        }
        if (COMMENT_CALLS.contains(method) && error.startsWith(INTERFACE + ".")) {
            final @Nullable ReviewRefusedException refused =
                    commentRefusal(method, error.substring(INTERFACE.length() + 1), answer);
            if (refused != null) {
                return refused;
            }
        }
        if (method.equals("DecideItem")
                && error.startsWith(INTERFACE + ".")
                && answer.get("id") instanceof String id) {
            final @Nullable String words =
                    itemRefusal(error.substring(INTERFACE.length() + 1), shown(id));
            if (words != null) {
                return new ItemRefusedException(error.substring(INTERFACE.length() + 1), id, words);
            }
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

    // Every one says nothing was decided, so a person knows the item still stands as it was.
    private static @Nullable String itemRefusal(String reason, String id) {
        return switch (reason) {
            case "NoSuchItem" -> "Item " + id + " is not held; nothing was decided";
            case "DigestChanged" ->
                    "Item "
                            + id
                            + " changed since it was shown; nothing was decided, and it is shown"
                            + " again as it stands";
            case "AlreadyDecided" -> "Item " + id + " was already decided; nothing was decided now";
            case "Lapsed" ->
                    "Item "
                            + id
                            + " lapsed with the run that held it; nothing was decided, and it"
                            + " never went further";
            default -> null;
        };
    }

    // Each refusal in words where its fields are there; without them, the daemon's own words.
    private static @Nullable ReviewRefusedException commentRefusal(
            String method, String reason, Map<String, Object> answer) {
        if (!COMMENT_REFUSALS.contains(reason)) {
            return null;
        }
        final @Nullable String words =
                switch (reason) {
                    case "NoSuchPush" -> words(answer, "No push named %s waits", "name");
                    case "ProfileViolation" ->
                            words(
                                    answer,
                                    "The comment is outside Porelo's review profile at %s (%s): %s",
                                    "path",
                                    "rule",
                                    "detail");
                    case "ForbiddenField" ->
                            words(
                                    answer,
                                    "The comment names %s, which only the daemon sets: %s",
                                    "path",
                                    "detail");
                    case "TooLarge" ->
                            words(
                                    answer,
                                    "The comment is over the limit of %s at %s: %s",
                                    "limit",
                                    "path",
                                    "detail");
                    case "UnsafeText" ->
                            words(
                                    answer,
                                    "The comment holds a character that is not allowed at %s: %s",
                                    "path",
                                    "detail");
                    case "AnchorOutsideBlob" ->
                            words(
                                    answer,
                                    "Lines %s to %s are not in %s on that side of the diff",
                                    "startLine",
                                    "endLine",
                                    "path");
                    case "FixOutsideAnchor" ->
                            words(
                                    answer,
                                    "The suggestion does not replace exactly the commented lines of %s: %s",
                                    "path",
                                    "detail");
                    case "OldSideFix" ->
                            words(
                                    answer,
                                    "A suggestion is made only on the new side, not in %s's old one: %s",
                                    "path",
                                    "detail");
                    case "NoSuchResult" ->
                            words(answer, "The comment answered, %s, is not on this push", "guid");
                    case "NoSuchThread" ->
                            words(answer, "The thread %s is not on this push", "thread");
                    case "CursorRefused" ->
                            words(
                                    answer,
                                    "The daemon refused the page cursor %s; read the comments again",
                                    "cursor");
                    case "IdReused" ->
                            words(answer, "The comment id %s was used for another comment", "id");
                    default -> null;
                };
        return new ReviewRefusedException(
                reason,
                words != null
                        ? words + "; nothing was stored or changed"
                        : method
                                + " was refused: "
                                + reason
                                + (answer.isEmpty() ? "" : " " + shown(answer)));
    }

    // A field may be text or a whole number; any other, or a missing one, gives no words.
    private static @Nullable String words(
            Map<String, Object> answer, String format, String... fields) {
        final Object[] values = new Object[fields.length];
        for (int i = 0; i < fields.length; i++) {
            final Object value = answer.get(fields[i]);
            if (value instanceof String text) {
                values[i] = shown(text);
            } else if (value instanceof Number number
                    && number.doubleValue() == Math.rint(number.doubleValue())) {
                values[i] = number.longValue();
            } else {
                return null;
            }
        }
        return String.format(java.util.Locale.ROOT, format, values);
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> reply, String field) {
        if (!(reply.get(field) instanceof Map<?, ?> map)) {
            throw new GateException(
                    "The daemon answered '" + field + "' with no object: " + shown(reply));
        }
        return (Map<String, Object>) map;
    }

    // Absent is absent; anything but text is no answer to what it asked.
    private static @Nullable String optionalString(Map<String, Object> reply, String field) {
        final Object value = reply.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new GateException(
                    "The daemon answered '" + field + "' with no text: " + shown(reply));
        }
        return text;
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

    private static List<Review.File> filesOf(List<Map<String, Object>> answered) {
        final List<Review.File> files = new ArrayList<>();
        for (final Map<String, Object> file : answered) {
            files.add(
                    new Review.File(
                            string(file, "path"),
                            string(file, "status"),
                            number(file, "added"),
                            number(file, "removed"),
                            string(file, "rank"),
                            string(file, "reason"),
                            optionalString(file, "riskClass")));
        }
        return files;
    }

    private static @Nullable Integer optionalNumber(Map<String, Object> reply, String field) {
        return reply.get(field) == null ? null : number(reply, field);
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
