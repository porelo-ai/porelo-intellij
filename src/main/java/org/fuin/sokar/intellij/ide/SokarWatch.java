package org.fuin.sokar.intellij.ide;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.fuin.sokar.intellij.cache.Changes;
import org.fuin.sokar.intellij.cache.ReviewCache;
import org.fuin.sokar.intellij.gate.Gate;
import org.fuin.sokar.intellij.gate.Waiting;
import org.fuin.sokar.intellij.machine.Machine;
import org.fuin.sokar.intellij.machine.Machines;
import org.fuin.sokar.intellij.machine.MachinesException;
import org.fuin.sokar.intellij.machine.Tunnel;
import org.fuin.sokar.intellij.machine.WslBridge;
import org.jspecify.annotations.Nullable;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.util.concurrency.AppExecutorUtil;

/**
 * Reads what waits at every machine's gates, once a minute and on demand, whatever project is open or none, and
 * says when something new waits.
 * <p>
 * Each tunnel it raises is kept while it forwards and closed with the IDE; a tunnel that dropped is raised again at
 * the next read.
 */
@Service(Service.Level.APP)
public final class SokarWatch implements Disposable {

    /** The notification group, as {@code plugin.xml} declares it. */
    public static final String NOTIFICATIONS = "Sokar";

    private static final long EVERY_SECONDS = 60;

    private final Map<String, Tunnel> tunnels = new ConcurrentHashMap<>();

    private final Map<String, WslBridge> bridges = new ConcurrentHashMap<>();

    private final List<Consumer<Snapshot>> listeners = new CopyOnWriteArrayList<>();

    private volatile Snapshot last = Snapshot.NONE;

    private @Nullable ScheduledFuture<?> schedule;

    /**
     * Returns the IDE's one watch.
     *
     * @return It.
     */
    public static SokarWatch get() {
        return ApplicationManager.getApplication().getService(SokarWatch.class);
    }

    /**
     * Starts reading once a minute, from now; a second call changes nothing.
     */
    public synchronized void start() {
        if (schedule == null) {
            schedule = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(this::read, 0,
                    EVERY_SECONDS, TimeUnit.SECONDS);
        }
    }

    /**
     * Reads again now, in the background.
     */
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
     * Tells a listener every new snapshot, on the thread that read it, until the given parent is disposed.
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
     * <p>
     * From where {@link Waiting.Entry#source} says.
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

    private Path repository(Waiting.Entry entry) {
        return cache().repository(entry.machine().name(), entry.project(), entry.repository());
    }

    private static ReviewCache cache() {
        final String set = System.getenv("XDG_CACHE_HOME");
        final Path root = set == null || set.isEmpty() || !Path.of(set).isAbsolute()
                ? Path.of(System.getProperty("user.home"), ".cache") : Path.of(set);
        return new ReviewCache(root.resolve("sokar-intellij"), Map.of());
    }

    /**
     * Opens a gate to a machine, through a tunnel when it needs one.
     *
     * @param machine The machine.
     * @return The gate; the caller closes it.
     */
    public Gate gate(Machine machine) {
        return new Gate(socketOf(machine));
    }

    @Override
    public synchronized void dispose() {
        if (schedule != null) {
            schedule.cancel(true);
        }
        tunnels.values().forEach(Tunnel::close);
        tunnels.clear();
        bridges.values().forEach(WslBridge::close);
        bridges.clear();
    }

    private synchronized void read() {
        final Snapshot before = last;
        Snapshot now;
        try {
            now = new Snapshot(Waiting.read(machines().read(), this::gate), null, Instant.now());
        } catch (MachinesException ex) {
            now = new Snapshot(before.waiting(), ex.getMessage(), Instant.now());
        }
        last = now;
        tell(before, now);
        for (final Consumer<Snapshot> listener : listeners) {
            listener.accept(now);
        }
    }

    private static void tell(Snapshot before, Snapshot now) {
        if (before.at() == null) {
            final int waiting = now.waiting().pushes().size();
            if (waiting > 0) {
                notification(waiting == 1 ? "One push waits for review" : waiting + " pushes wait for review", "")
                        .notify(null);
            }
            return;
        }
        for (final Waiting.Entry entry : now.waiting().newSince(before.waiting())) {
            final Notification notification = notification("Waits for review: " + entry.push().subject(),
                    entry.push().name() + " in " + entry.project() + " / " + entry.repository() + " on "
                            + entry.machine().name() + (entry.farReaching() > 0 ? " - " + entry.farReaching()
                                    + " far-reaching file(s)" : ""));
            notification.addAction(NotificationAction.create("Review", (event, shown) -> {
                // A review opens in a project's window; from the welcome screen the tool window is a project away.
                if (event.getProject() != null) {
                    ReviewTab.open(event.getProject(), entry);
                    shown.expire();
                }
            }));
            notification.notify(null);
        }
    }

    private static Notification notification(String title, String content) {
        return NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATIONS)
                .createNotification(title, content, NotificationType.INFORMATION);
    }

    private Path socketOf(Machine machine) {
        if (machine.inWsl()) {
            return bridges.compute(machine.name(), (name, existing) -> {
                if (existing != null && existing.up()) {
                    return existing;
                }
                if (existing != null) {
                    existing.close();
                }
                return WslBridge.open(machine, runtimeDirectory(), List.of("wsl.exe"));
            }).socket();
        }
        if (!machine.needsATunnel()) {
            return java.util.Objects.requireNonNull(machine.socket(), "a machine reached directly has a socket");
        }
        final Tunnel tunnel = tunnels.compute(machine.name(), (name, existing) -> {
            if (existing != null && existing.up()) {
                return existing;
            }
            if (existing != null) {
                existing.close();
            }
            return Tunnel.raise(machine, machines().runtimeDirectory(), List.of("ssh"));
        });
        return tunnel.socket();
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
        return new Machines(System.getenv(), Path.of(System.getProperty("user.home")),
                System.getProperty("os.name", "").startsWith("Windows"));
    }

}
