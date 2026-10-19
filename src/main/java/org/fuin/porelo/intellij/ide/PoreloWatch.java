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
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.fuin.porelo.intellij.cache.Changes;
import org.fuin.porelo.intellij.cache.ReviewCache;
import org.fuin.porelo.intellij.gate.Gate;
import org.fuin.porelo.intellij.gate.Waiting;
import org.fuin.porelo.intellij.machine.Connections;
import org.fuin.porelo.intellij.machine.Machine;
import org.fuin.porelo.intellij.machine.Machines;
import org.fuin.porelo.intellij.machine.MachinesException;
import org.jspecify.annotations.Nullable;

/**
 * Reads what waits at every machine's gates, once a minute and on demand, whatever project is open
 * or none, and says when something new waits.
 *
 * <p>Each tunnel it raises is kept while it forwards and closed with the IDE; a tunnel that dropped
 * is raised again at the next read.
 */
@Service(Service.Level.APP)
public final class PoreloWatch implements Disposable {

    /** The notification group, as {@code plugin.xml} declares it. */
    public static final String NOTIFICATIONS = "Porelo";

    private static final long EVERY_SECONDS = 60;

    /** How long a machine's stream that ended waits before it is opened again. */
    private static final long REOPEN_SECONDS = 10;

    /** The machines whose stream of projects is followed, by name, each on a thread of its own. */
    private final Map<String, Thread> streams = new java.util.concurrent.ConcurrentHashMap<>();

    /** The gates the streams hold open, closed with the IDE, which ends their streams. */
    private final java.util.Set<Gate> streaming =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private volatile boolean disposed;

    private final Connections connections =
            new Connections(PoreloWatch::runtimeDirectory, List.of("ssh"), List.of("wsl.exe"));

    private final List<Consumer<Snapshot>> listeners = new CopyOnWriteArrayList<>();

    private static final Logger LOG = Logger.getInstance(PoreloWatch.class);

    private volatile Snapshot last = Snapshot.NONE;

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

    /** Starts reading once a minute, from now; a second call changes nothing. */
    public synchronized void start() {
        if (schedule == null) {
            schedule =
                    AppExecutorUtil.getAppScheduledExecutorService()
                            .scheduleWithFixedDelay(this::read, 0, EVERY_SECONDS, TimeUnit.SECONDS);
        }
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
     * Fetches a waiting push and the branch it is read against into the plugin's own cache.
     *
     * <p>From where {@link Waiting.Entry#source} says.
     *
     * @param entry The push.
     * @return What it changes.
     */
    public Changes fetch(Waiting.Entry entry) {
        final String url = entry.source(mirror -> java.nio.file.Files.isDirectory(Path.of(mirror)));
        return cache().fetch(repository(entry), url, entry.push().name(), entry.push().commit());
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
        return cache().file(repository(entry), commit, path);
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
        return cache().files(repository(entry), commit, paths);
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
        cache().fetchInto(root, url, entry.push().name(), entry.push().commit());
    }

    /**
     * Returns a repository's remotes.
     *
     * @param root The git root of the person's repository.
     * @return Each remote's name and address.
     */
    Map<String, String> remotes(Path root) {
        return OpenProjects.remotes(cache().remoteUrls(root));
    }

    /**
     * Fetches one remote of a repository of the person's.
     *
     * @param root The git root.
     * @param remote The remote's name.
     */
    void fetchRemote(Path root, String remote) {
        cache().fetchRemote(root, remote);
    }

    /**
     * Deletes a decided push's ref from a repository of the person's, where it was fetched.
     *
     * @param root The git root.
     * @param name The push's name.
     */
    void forget(Path root, String name) {
        cache().forget(root, name);
    }

    private Path repository(Waiting.Entry entry) {
        return cache().repository(entry.machine().name(), entry.project(), entry.repository());
    }

    private static ReviewCache cache() {
        final String set = System.getenv("XDG_CACHE_HOME");
        final Path root =
                set == null || set.isEmpty() || !Path.of(set).isAbsolute()
                        ? Path.of(System.getProperty("user.home"), ".cache")
                        : Path.of(set);
        return new ReviewCache(root.resolve("porelo-intellij"), Map.of());
    }

    /**
     * Opens a gate to a machine, through a tunnel when it needs one.
     *
     * @param machine The machine.
     * @return The gate; the caller closes it.
     */
    public Gate gate(Machine machine) {
        return new Gate(connections.socketOf(machine));
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

    /** Reads one machine again, keeping what the others said. */
    private synchronized void read(Machine machine) {
        final Snapshot before = last;
        publish(
                before,
                Snapshot.after(
                        before,
                        () ->
                                before.waiting()
                                        .withMachine(
                                                machine.name(),
                                                Waiting.read(
                                                        List.of(machine),
                                                        this::gate,
                                                        before.waiting()))));
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
        for (final Machine machine : listed) {
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
                Thread.sleep(TimeUnit.SECONDS.toMillis(REOPEN_SECONDS));
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            streams.remove(machine.name());
        }
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
        return new Machines(
                System.getenv(),
                Path.of(System.getProperty("user.home")),
                System.getProperty("os.name", "").startsWith("Windows"));
    }
}
