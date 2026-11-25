package org.fuin.porelo.intellij.ide;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.fuin.porelo.intellij.cache.Changes;
import org.fuin.porelo.intellij.cache.ReviewCache;
import org.fuin.porelo.intellij.gate.Gate;
import org.fuin.porelo.intellij.gate.HeldItem;
import org.fuin.porelo.intellij.gate.HeldList;
import org.fuin.porelo.intellij.gate.Waiting;
import org.fuin.porelo.intellij.machine.Connections;
import org.fuin.porelo.intellij.machine.Machine;
import org.fuin.porelo.intellij.machine.Machines;
import org.fuin.porelo.intellij.machine.MachinesException;
import org.fuin.porelo.wire.LimitFactors;
import org.jspecify.annotations.Nullable;

/**
 * Reads what waits at every machine's gates, once a minute times the daemons' polling factor and on
 * demand, whatever project is open or none, and says when something new waits.
 *
 * <p>Each tunnel it raises is kept while it forwards and closed with the IDE; a tunnel that dropped
 * is raised again at the next read.
 */
@Service(Service.Level.APP)
public final class PoreloWatch implements Disposable {

    /** The notification group, as {@code plugin.xml} declares it. */
    public static final String NOTIFICATIONS = "Porelo";

    /** How often every machine is read, before the polling factor. */
    private static final Duration EVERY = Duration.ofSeconds(60);

    /**
     * How long a machine's stream that ended waits before it is opened again, before the factor.
     */
    private static final Duration REOPEN = Duration.ofSeconds(10);

    /**
     * The factors each machine's daemon answered last, by name, for what starts before it is asked.
     */
    private final Map<String, LimitFactors> factors = new ConcurrentHashMap<>();

    /** The machines whose stream of projects is followed, by name, each on a thread of its own. */
    private final Map<String, Thread> streams = new java.util.concurrent.ConcurrentHashMap<>();

    /** The gates the streams hold open, closed with the IDE, which ends their streams. */
    private final java.util.Set<Gate> streaming =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private volatile boolean disposed;

    private final Connections connections =
            new Connections(
                    PoreloWatch::runtimeDirectory,
                    List.of(machines().ssh()),
                    List.of("wsl.exe"),
                    windows());

    private final List<Consumer<Snapshot>> listeners = new CopyOnWriteArrayList<>();

    private static final Logger LOG = Logger.getInstance(PoreloWatch.class);

    private volatile Snapshot last = Snapshot.NONE;

    /** The machines whose stream of tasks is followed for their held items, by name. */
    private final Map<String, Thread> heldStreams = new ConcurrentHashMap<>();

    /** Each machine's tasks' held counts as last answered, kept across a stream that reopens. */
    private final Map<String, Map<String, Integer>> heldCounts = new ConcurrentHashMap<>();

    private final List<Consumer<Held>> heldListeners = new CopyOnWriteArrayList<>();

    private volatile Held held = Held.NONE;

    /** Guards each change of {@link #held}; never held while a machine is asked. */
    private final Object heldLock = new Object();

    /** One lock per machine, held while its items are read. */
    private final Map<String, Object> heldReads = new ConcurrentHashMap<>();

    /** What has been announced, kept across a read in which its machine did not answer. */
    private Waiting seen = new Waiting(List.of(), List.of());

    private @Nullable ScheduledFuture<?> schedule;

    /**
     * Returns the IDE's one watch.
     *
     * @return It.
     */
    public static PoreloWatch get() {
        return ApplicationManager.getApplication().getService(PoreloWatch.class);
    }

    /** Starts reading, from now; a second call changes nothing. */
    public synchronized void start() {
        if (schedule == null) {
            next(Duration.ZERO);
        }
    }

    private synchronized void next(Duration delay) {
        if (!disposed) {
            schedule =
                    AppExecutorUtil.getAppScheduledExecutorService()
                            .schedule(this::tick, delay.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    // Scheduled anew after each read, so a polling factor read meanwhile sets the next delay.
    private void tick() {
        try {
            read();
        } finally {
            next(every(listedFactors()));
        }
    }

    /**
     * Returns how long until every machine is read again.
     *
     * @param read The factors the machines answered.
     * @return A minute times the largest polling factor among them, which looks least often.
     */
    static Duration every(Collection<LimitFactors> read) {
        return widest(read).polling(EVERY);
    }

    /**
     * Returns, for each of the four, the largest factor any machine answered: what a limit that
     * serves no one machine waits by.
     *
     * @param read The factors the machines answered.
     * @return The largest of each, or all four at 1.0 when none answered.
     */
    static LimitFactors widest(Collection<LimitFactors> read) {
        if (read.isEmpty()) {
            return LimitFactors.DEFAULT;
        }
        final LimitFactors first = read.iterator().next();
        BigDecimal services = first.services();
        BigDecimal machines = first.machines();
        BigDecimal polling = first.polling();
        BigDecimal retries = first.retries();
        for (final LimitFactors one : read) {
            services = services.max(one.services());
            machines = machines.max(one.machines());
            polling = polling.max(one.polling());
            retries = retries.max(one.retries());
        }
        return new LimitFactors(services, machines, polling, retries);
    }

    /**
     * Returns the factors of the machines still listed, so one taken out no longer counts.
     *
     * @param read The factors read, by machine name.
     * @param listed The names of the machines listed now.
     * @return Their factors.
     */
    static Collection<LimitFactors> ofListed(
            Map<String, LimitFactors> read, java.util.Set<String> listed) {
        return read.entrySet().stream()
                .filter(entry -> listed.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();
    }

    // A list that cannot be read counts no machine, so every limit waits by 1.0.
    private Collection<LimitFactors> listedFactors() {
        try {
            return ofListed(
                    factors,
                    machines().read().machines().stream()
                            .map(Machine::name)
                            .collect(java.util.stream.Collectors.toSet()));
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    /**
     * Returns how long a machine's stream that ended waits before it is opened again.
     *
     * @param machine The factors its daemon answered last.
     * @return Ten seconds times its retries factor.
     */
    static Duration reopen(LimitFactors machine) {
        return machine.retryPause(REOPEN);
    }

    /** Reads again now, in the background. */
    public void readNow() {
        AppExecutorUtil.getAppExecutorService().execute(this::read);
    }

    /**
     * Returns what was read last.
     *
     * @return The last snapshot.
     */
    public Snapshot last() {
        return last;
    }

    /**
     * Tells a listener every new snapshot, on the thread that read it, until the given parent is
     * disposed.
     *
     * @param listener Gets each snapshot.
     * @param parent Ends the listening when it is disposed.
     */
    public void listen(Consumer<Snapshot> listener, Disposable parent) {
        listeners.add(listener);
        com.intellij.openapi.util.Disposer.register(parent, () -> listeners.remove(listener));
    }

    /**
     * Returns the held items read last.
     *
     * @return Every machine's held items, and the machines that could not be read.
     */
    public Held held() {
        return held;
    }

    /**
     * Tells a listener that the held items changed, on the thread that read them, until the given
     * parent is disposed; two machines' changes may arrive out of order, so {@link #held()} gives
     * what stands now.
     *
     * @param listener Gets each state.
     * @param parent Ends the listening when it is disposed.
     */
    public void listenHeld(Consumer<Held> listener, Disposable parent) {
        heldListeners.add(listener);
        com.intellij.openapi.util.Disposer.register(parent, () -> heldListeners.remove(listener));
    }

    /** Reads every listed machine's held items again now, in the background. */
    public void readHeldNow() {
        AppExecutorUtil.getAppExecutorService()
                .execute(
                        () -> {
                            final List<Machine> listed;
                            try {
                                listed = machines().read().machines();
                            } catch (RuntimeException ex) {
                                // Already said by the read of what waits.
                                return;
                            }
                            listed.forEach(this::readHeld);
                        });
    }

    /**
     * Decides one held item as it was shown, then reads that machine's held items again, whether
     * the daemon took the decision or refused it, so what is shown is what stands now.
     *
     * <p>Blocks; never call it on the event thread.
     *
     * @param machine The name of the machine that holds it.
     * @param item The item as it was shown.
     * @param decision What is decided.
     * @throws org.fuin.porelo.intellij.gate.ItemRefusedException If the daemon refused it.
     * @throws org.fuin.porelo.intellij.gate.GateException If the daemon could not be asked.
     * @throws MachinesException If the machine is no longer listed.
     */
    public void decide(String machine, HeldItem item, HeldItem.Decision decision) {
        final Machine listed =
                machines().read().machines().stream()
                        .filter(one -> one.name().equals(machine))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new MachinesException(
                                                "The machine "
                                                        + machine
                                                        + " is no longer listed; nothing was decided"));
        try (Gate gate = gate(listed)) {
            gate.decideItem(item, decision);
        } finally {
            readHeld(listed);
        }
    }

    /**
     * Fetches a waiting push and the branch it is read against into the plugin's own cache.
     *
     * <p>From where {@link Waiting.Entry#source} says.
     *
     * @param entry The push.
     * @return What it changes.
     */
    public Changes fetch(Waiting.Entry entry) {
        final String url = entry.source(mirror -> java.nio.file.Files.isDirectory(Path.of(mirror)));
        return cache(entry)
                .fetch(
                        repository(entry),
                        url,
                        entry.machine().sshConfig(),
                        entry.push().name(),
                        entry.push().commit());
    }

    /**
     * Returns a file of a fetched push or of its base.
     *
     * @param entry The push.
     * @param commit The commit, as {@link #fetch} named it.
     * @param path The file's path.
     * @return Its bytes, or {@code null} when that side has no such file.
     */
    public byte @Nullable [] file(Waiting.Entry entry, String commit, String path) {
        return cache(entry).file(repository(entry), commit, path);
    }

    /**
     * Returns files of a fetched push or of its base, all read at once.
     *
     * @param entry The push.
     * @param commit The commit, as {@link #fetch} named it.
     * @param paths The files' paths.
     * @return Each path's bytes, or {@code null} where that side has no such file.
     */
    public Map<String, byte @Nullable []> files(
            Waiting.Entry entry, String commit, List<String> paths) {
        return cache(entry).files(repository(entry), commit, paths);
    }

    /**
     * Fetches a waiting push into a repository of the person's as {@code
     * refs/remotes/porelo/<push>}, from where {@link Waiting.Entry#source} says, and nothing else.
     *
     * @param entry The push.
     * @param root The git root of the person's repository.
     */
    public void fetchInto(Waiting.Entry entry, Path root) {
        final String url = entry.source(mirror -> java.nio.file.Files.isDirectory(Path.of(mirror)));
        cache(entry)
                .fetchInto(
                        root,
                        url,
                        entry.machine().sshConfig(),
                        entry.push().name(),
                        entry.push().commit());
    }

    /**
     * Returns a repository's remotes.
     *
     * @param root The git root of the person's repository.
     * @return Each remote's name and address.
     */
    Map<String, String> remotes(Path root) {
        return OpenProjects.remotes(anyCache().remoteUrls(root));
    }

    /**
     * Fetches one remote of a repository of the person's.
     *
     * @param root The git root.
     * @param remote The remote's name.
     */
    void fetchRemote(Path root, String remote) {
        anyCache().fetchRemote(root, remote);
    }

    /**
     * Deletes a decided push's ref from a repository of the person's, where it was fetched.
     *
     * @param root The git root.
     * @param name The push's name.
     */
    void forget(Path root, String name) {
        anyCache().forget(root, name);
    }

    private Path repository(Waiting.Entry entry) {
        return cache(entry).repository(entry.machine().name(), entry.project(), entry.repository());
    }

    private ReviewCache cache(Waiting.Entry entry) {
        return cache(factorsOf(entry.machine()));
    }

    // A repository of the person's comes from no one machine.
    private ReviewCache anyCache() {
        return cache(widest(listedFactors()));
    }

    private static ReviewCache cache(LimitFactors factors) {
        final String set = System.getenv("XDG_CACHE_HOME");
        final Path root =
                set == null || set.isEmpty() || !Path.of(set).isAbsolute()
                        ? Path.of(System.getProperty("user.home"), ".cache")
                        : Path.of(set);
        return new ReviewCache(
                root.resolve("porelo-intellij"), Map.of(), machines().ssh(), factors);
    }

    /**
     * Opens a gate to a machine, through a tunnel when it needs one, and reads the factors its
     * daemon answers, which its later waits and this machine's next start follow.
     *
     * @param machine The machine.
     * @return The gate; the caller closes it.
     * @throws org.fuin.porelo.intellij.gate.GateException If the daemon answers no limits line.
     */
    public Gate gate(Machine machine) {
        final LimitFactors known = factorsOf(machine);
        final Gate gate = new Gate(connections.socketOf(machine, known), known);
        try {
            factors.put(machine.name(), gate.limits());
        } catch (RuntimeException ex) {
            closeQuietly(gate);
            throw ex;
        }
        return gate;
    }

    private LimitFactors factorsOf(Machine machine) {
        return factors.getOrDefault(machine.name(), LimitFactors.DEFAULT);
    }

    @Override
    public synchronized void dispose() {
        disposed = true;
        if (schedule != null) {
            schedule.cancel(true);
        }
        streaming.forEach(PoreloWatch::closeQuietly);
        connections.close();
    }

    private void read() {
        synchronized (this) {
            final Snapshot before = last;
            publish(
                    before,
                    Snapshot.after(
                            before,
                            () -> Waiting.read(machines().read(), this::gate, before.waiting())));
        }
        follow();
    }

    /**
     * Reads one machine again, keeping what the others said, as the interface's file lists it now,
     * so a stream opened before its configuration went cannot read it.
     */
    private synchronized void read(Machine machine) {
        final Snapshot before = last;
        publish(
                before,
                Snapshot.after(
                        before,
                        () -> before.waiting().readOne(machines().read(), machine, this::gate)));
    }

    /**
     * Follows the stream of projects of every machine listed, so a push is announced within seconds
     * of its arrival; the minute's read stays for a machine whose stream is not open.
     */
    private void follow() {
        final List<Machine> listed;
        try {
            listed = machines().read().machines();
        } catch (RuntimeException ex) {
            // Already said by the read.
            return;
        }
        final java.util.Set<String> names =
                listed.stream().map(Machine::name).collect(java.util.stream.Collectors.toSet());
        final java.util.Set<String> shown = new java.util.HashSet<>(held.items().keySet());
        shown.addAll(held.unreadable().keySet());
        for (final String gone : shown) {
            if (!names.contains(gone)) {
                forgetHeld(gone);
            }
        }
        for (final Machine machine : listed) {
            heldStreams.computeIfAbsent(
                    machine.name(),
                    name ->
                            Thread.ofVirtual()
                                    .name("Porelo held items of " + name)
                                    .start(() -> streamHeld(machine)));
            streams.computeIfAbsent(
                    machine.name(),
                    name ->
                            Thread.ofVirtual()
                                    .name("Porelo watch of " + name)
                                    .start(() -> stream(machine)));
        }
    }

    private void stream(Machine machine) {
        try {
            while (!disposed && listed(machine)) {
                Gate gate = null;
                try {
                    gate = gate(machine);
                    streaming.add(gate);
                    final java.util.concurrent.atomic.AtomicReference<
                                    @Nullable List<org.fuin.porelo.intellij.gate.Project>>
                            seen = new java.util.concurrent.atomic.AtomicReference<>();
                    gate.watch(
                            projects -> {
                                final List<org.fuin.porelo.intellij.gate.Project> before =
                                        seen.getAndSet(projects);
                                if (before != null && Waiting.pendingMoved(before, projects)) {
                                    read(machine);
                                }
                            });
                } catch (RuntimeException ex) {
                    LOG.info("The stream of projects of " + machine.name() + " ended", ex);
                } finally {
                    if (gate != null) {
                        streaming.remove(gate);
                        closeQuietly(gate);
                    }
                }
                if (disposed) {
                    return;
                }
                // A stream that ended may be a daemon that went; reading it again says so.
                read(machine);
                Thread.sleep(reopen(factorsOf(machine)).toMillis());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            streams.remove(machine.name());
        }
    }

    /**
     * Follows a machine's stream of tasks for their held counts: its held items are read again
     * whenever a count moved, and a task whose count rose is announced once they are read.
     */
    private void streamHeld(Machine machine) {
        try {
            while (!disposed && listed(machine)) {
                Gate gate = null;
                @Nullable String ended = null;
                try {
                    gate = gate(machine);
                    streaming.add(gate);
                    // A stream opened again reads once, since what it missed is unknown.
                    final java.util.concurrent.atomic.AtomicReference<
                                    @Nullable Map<String, @Nullable Integer>>
                            answered = new java.util.concurrent.atomic.AtomicReference<>();
                    gate.watchHeld(
                            counts -> {
                                final Map<String, Integer> before =
                                        heldCounts.getOrDefault(machine.name(), Map.of());
                                heldCounts.put(machine.name(), Held.known(before, counts));
                                updateHeld(now -> now.counted(machine.name(), counts));
                                if (!counts.equals(answered.getAndSet(counts))) {
                                    readHeld(machine);
                                }
                                announceHeld(machine.name(), Held.rose(before, counts));
                            });
                } catch (RuntimeException ex) {
                    LOG.info("The stream of tasks of " + machine.name() + " ended", ex);
                    ended = said(ex);
                } finally {
                    if (gate != null) {
                        streaming.remove(gate);
                        closeQuietly(gate);
                    }
                }
                if (disposed) {
                    return;
                }
                // A stream that ended may be a daemon that went; reading it again says so. One
                // that answers the read but not the stream is said too: no rise would be announced.
                if (readHeld(machine) && ended != null) {
                    final String why = ended;
                    updateHeld(
                            before ->
                                    before.unreadable(
                                            machine.name(),
                                            "its count of held items cannot be followed: " + why));
                }
                Thread.sleep(reopen(factorsOf(machine)).toMillis());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            // A machine listed again under its name meanwhile has a stream of its own, kept.
            if (heldStreams.remove(machine.name(), Thread.currentThread())
                    && !disposed
                    && !named(machine.name())) {
                forgetHeld(machine.name());
            }
        }
    }

    // One read per machine at a time, so an older answer is never published after a newer one.
    private boolean readHeld(Machine machine) {
        synchronized (heldReads.computeIfAbsent(machine.name(), name -> new Object())) {
            try (Gate gate = gate(machine)) {
                final HeldList read = gate.heldItems(null);
                updateHeld(before -> before.with(machine.name(), read));
                return true;
            } catch (RuntimeException ex) {
                updateHeld(before -> before.unreadable(machine.name(), said(ex)));
                return false;
            }
        }
    }

    private static String said(RuntimeException ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private static boolean named(String name) {
        try {
            return machines().read().machines().stream().anyMatch(one -> one.name().equals(name));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private void forgetHeld(String machine) {
        heldCounts.remove(machine);
        updateHeld(before -> before.without(machine));
    }

    private void updateHeld(java.util.function.UnaryOperator<Held> change) {
        final Held now;
        synchronized (heldLock) {
            now = change.apply(held);
            held = now;
        }
        publishHeld(now);
    }

    private void publishHeld(Held now) {
        for (final Consumer<Held> listener : heldListeners) {
            try {
                listener.accept(now);
            } catch (RuntimeException ex) {
                LOG.warn("A listener of the held items failed", ex);
            }
        }
    }

    private static void announceHeld(String machine, Map<String, Integer> rose) {
        rose.forEach(
                (task, count) -> {
                    try {
                        final Notification notification =
                                notification(
                                        count == 1
                                                ? "One item held for your decision"
                                                : count + " items held for your decision",
                                        "Task " + task + " on " + machine);
                        notification.addAction(
                                NotificationAction.create(
                                        "Show",
                                        (event, shown) -> {
                                            if (event.getProject() != null) {
                                                HeldPanel.show(event.getProject());
                                                shown.expire();
                                            }
                                        }));
                        notification.notify(null);
                    } catch (RuntimeException ex) {
                        LOG.warn("Cannot announce the items held by " + task, ex);
                    }
                });
    }

    private static boolean listed(Machine machine) {
        try {
            return machines().read().machines().contains(machine);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    // A stream's gate is closed to end it; closing what already ended is no news.
    private static void closeQuietly(Gate gate) {
        try {
            gate.close();
        } catch (RuntimeException ex) {
            // See above.
        }
    }

    private void publish(Snapshot before, Snapshot now) {
        last = now;
        // Nothing here may end the scheduled reads: a failed notice or listener is logged.
        try {
            tell(before, now, seen);
        } catch (RuntimeException ex) {
            LOG.warn("Cannot tell what is new", ex);
        }
        seen = now.waiting().seenAfter(seen);
        for (final Consumer<Snapshot> listener : listeners) {
            try {
                listener.accept(now);
            } catch (RuntimeException ex) {
                LOG.warn("A listener of the watch failed", ex);
            }
        }
    }

    private static void tell(Snapshot before, Snapshot now, Waiting seen) {
        if (before.at() == null) {
            final int waiting = now.waiting().pushes().size();
            if (waiting > 0) {
                notification(
                                waiting == 1
                                        ? "One push waits for review"
                                        : waiting + " pushes wait for review",
                                "")
                        .notify(null);
            }
            return;
        }
        for (final Waiting.Entry entry : now.waiting().newSince(seen)) {
            final Notification notification =
                    notification(Notices.title(entry), Notices.content(entry));
            notification.addAction(
                    NotificationAction.create(
                            "Review",
                            (event, shown) -> {
                                // A review opens in a project's window; from the welcome screen the
                                // tool window is a project away.
                                if (event.getProject() != null) {
                                    ReviewTab.open(event.getProject(), entry);
                                    shown.expire();
                                }
                            }));
            for (final IntoProjects.Target target : IntoProjects.holding(entry)) {
                notification.addAction(
                        NotificationAction.create(
                                target.label(),
                                (event, shown) -> IntoProjects.open(target, entry)));
            }
            notification.notify(null);
        }
    }

    private static Notification notification(String title, String content) {
        return NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATIONS)
                .createNotification(title, content, NotificationType.INFORMATION);
    }

    private static Path runtimeDirectory() {
        final Path directory = machines().runtimeDirectory();
        try {
            java.nio.file.Files.createDirectories(directory);
        } catch (java.io.IOException ex) {
            throw new MachinesException("Cannot make " + directory + ": " + ex.getMessage(), ex);
        }
        return directory;
    }

    private static Machines machines() {
        return new Machines(System.getenv(), Path.of(System.getProperty("user.home")), windows());
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }
}
