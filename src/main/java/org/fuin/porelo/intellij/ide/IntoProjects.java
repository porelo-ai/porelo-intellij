package org.fuin.porelo.intellij.ide;

import com.intellij.ide.impl.ProjectUtil;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vcs.AbstractVcs;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsRoot;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.vcs.log.impl.HashImpl;
import com.intellij.vcs.log.impl.VcsProjectLog;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.intellij.gate.Waiting;

/**
 * A waiting push offered in the open projects that hold its repository: fetched there as {@code
 * refs/remotes/porelo/<push>} when the person chooses it, and that ref deleted and the project's
 * remote fetched once the push is decided.
 *
 * <p>The one thing the plugin writes into a repository of the person's is that ref; never a branch,
 * the index or the working tree.
 */
final class IntoProjects {

    private static final Logger LOG = Logger.getInstance(IntoProjects.class);

    /** What IntelliJ's git plugin calls itself, so roots are found without depending on it. */
    private static final String GIT = "Git";

    private IntoProjects() {}

    /**
     * An open project's git root that holds a push's upstream.
     *
     * @param project The project.
     * @param match The root and the remote that names the upstream there.
     */
    record Target(Project project, OpenProjects.Match match) {

        /**
         * Returns what the offer says.
         *
         * @return {@code Open in <project>}.
         */
        String label() {
            return "Open in " + project.getName();
        }
    }

    /**
     * Returns the open projects that hold the push's repository. Runs git in every git root of
     * every open project, so never on the UI thread.
     *
     * @param entry The push.
     * @return One target for each root that holds it; none for work that stays on the machine.
     */
    static List<Target> holding(Waiting.Entry entry) {
        final List<Target> targets = new ArrayList<>();
        if (entry.upstream().isBlank()) {
            return targets;
        }
        for (final Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (project.isDisposed()) {
                continue;
            }
            final List<OpenProjects.Root> roots = new ArrayList<>();
            for (final VcsRoot root :
                    ProjectLevelVcsManager.getInstance(project).getAllVcsRoots()) {
                final AbstractVcs vcs = root.getVcs();
                if (vcs == null || !GIT.equals(vcs.getName())) {
                    continue;
                }
                try {
                    final Path path = root.getPath().toNioPath();
                    roots.add(
                            new OpenProjects.Root(
                                    project.getName(), path, PoreloWatch.get().remotes(path)));
                } catch (RuntimeException ex) {
                    // A root git cannot read, or one not on this computer's disk, offers nothing.
                    LOG.info("Cannot read the remotes of " + root.getPath(), ex);
                }
            }
            OpenProjects.holding(entry.upstream(), roots)
                    .forEach(match -> targets.add(new Target(project, match)));
        }
        return targets;
    }

    /**
     * Brings the project's window forward and fetches the push there in the background, then says
     * where it is, with a way to show it in the Git log.
     *
     * @param target Where.
     * @param entry The push.
     */
    static void open(Target target, Waiting.Entry entry) {
        final Project project = target.project();
        ProjectUtil.focusProjectWindow(project, true);
        final String ref = "porelo/" + entry.push().name();
        ProgressManager.getInstance()
                .run(
                        new Task.Backgroundable(
                                project, "Fetching " + entry.push().name() + " as " + ref, true) {
                            @Override
                            public void run(ProgressIndicator indicator) {
                                final Path root = target.match().root();
                                try {
                                    PoreloWatch.get().fetchInto(entry, root);
                                } catch (RuntimeException ex) {
                                    tell(
                                            project,
                                            "Cannot fetch "
                                                    + entry.push().name()
                                                    + " into "
                                                    + project.getName()
                                                    + ": "
                                                    + ex.getMessage(),
                                            NotificationType.WARNING,
                                            null);
                                    return;
                                }
                                // git wrote the ref behind the IDE's back; the log reads it once
                                // the IDE has seen it.
                                final VirtualFile file =
                                        LocalFileSystem.getInstance()
                                                .refreshAndFindFileByNioFile(root);
                                tell(
                                        project,
                                        Notices.escaped(entry.push().name())
                                                + " is in "
                                                + Notices.escaped(project.getName())
                                                + " as "
                                                + Notices.escaped(ref)
                                                + "; nothing checked out changed",
                                        NotificationType.INFORMATION,
                                        file == null
                                                ? null
                                                : NotificationAction.createSimpleExpiring(
                                                        "Show in Git Log",
                                                        () ->
                                                                VcsProjectLog.getInstance(project)
                                                                        .showRevisionInMainLog(
                                                                                file,
                                                                                HashImpl.build(
                                                                                        entry.push()
                                                                                                .commit()))));
                            }
                        });
    }

    /**
     * Once a push is decided, deletes its ref where it was fetched and, after an approval, fetches
     * the remote that holds its upstream in each open project, so the forwarded commit is there
     * without a fetch of the person's. Runs git, so never on the UI thread; a failure is a warning
     * in git's words, and the decision stands.
     *
     * @param project The window that decided, where warnings are said.
     * @param entry The push.
     * @param approved Whether it was approved.
     */
    static void decided(Project project, Waiting.Entry entry, boolean approved) {
        for (final Target target : holding(entry)) {
            final Path root = target.match().root();
            try {
                if (approved) {
                    PoreloWatch.get().fetchRemote(root, target.match().remote());
                }
                PoreloWatch.get().forget(root, entry.push().name());
            } catch (RuntimeException ex) {
                tell(
                        project,
                        "Cannot update "
                                + Notices.escaped(target.project().getName())
                                + " after the decision: "
                                + Notices.escaped(String.valueOf(ex.getMessage())),
                        NotificationType.WARNING,
                        null);
            }
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root);
        }
    }

    private static void tell(
            Project project,
            String text,
            NotificationType type,
            @org.jspecify.annotations.Nullable NotificationAction action) {
        final var notification =
                NotificationGroupManager.getInstance()
                        .getNotificationGroup(PoreloWatch.NOTIFICATIONS)
                        .createNotification(text, type);
        if (action != null) {
            notification.addAction(action);
        }
        notification.notify(project);
    }
}
