package org.fuin.porelo.intellij.ide;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.fuin.porelo.intellij.gate.Approved;
import org.fuin.porelo.intellij.gate.BranchExistsException;
import org.fuin.porelo.intellij.gate.Gate;
import org.fuin.porelo.intellij.gate.MovedSinceReviewException;
import org.fuin.porelo.intellij.gate.Rejected;
import org.fuin.porelo.intellij.gate.Waiting;
import org.jspecify.annotations.Nullable;

/** Approving and rejecting a push, asked of the person and done in the background. */
final class Decisions {

    private Decisions() {}

    /**
     * Asks for the branch, offering {@code porelo/<name>} - a branch of its own, never the one
     * checked out - and forwards exactly the commit that was shown. A branch that already holds
     * earlier work is said, and the person is asked again with the next name offered.
     *
     * @param project The project whose window asks.
     * @param entry The push.
     * @param done Runs on the UI thread once the push went, or moved and has to be read again.
     */
    static void approve(Project project, Waiting.Entry entry, Runnable done) {
        approve(project, entry, done, "", "porelo/" + entry.push().name());
    }

    private static void approve(
            Project project, Waiting.Entry entry, Runnable done, String why, String offered) {
        final String branch =
                Messages.showInputDialog(
                        project,
                        why
                                + "Forward "
                                + entry.push().subject()
                                + "\n(commit "
                                + entry.push().commit()
                                + ")\nfrom "
                                + entry.project()
                                + " / "
                                + entry.repository()
                                + " on "
                                + entry.machine().name()
                                + " onto the branch:",
                        "Approve",
                        null,
                        offered,
                        null);
        if (branch == null || branch.isBlank()) {
            return;
        }
        run(
                project,
                "Approving " + entry.push().name(),
                gate -> {
                    try {
                        final Approved forwarded =
                                gate.approve(
                                        entry.project(),
                                        entry.repository(),
                                        entry.push().name(),
                                        branch.strip(),
                                        entry.push().commit());
                        IntoProjects.decided(project, entry, true);
                        return new Said(
                                "Forwarded " + forwarded.forwarded(), forwarded.endUnkept());
                    } catch (MovedSinceReviewException ex) {
                        // What it holds now has to be read before it can go: the review is closed,
                        // the list reads it again.
                        ApplicationManager.getApplication()
                                .invokeLater(done, project.getDisposed());
                        throw ex;
                    } catch (BranchExistsException ex) {
                        // The person chooses again, with the next name offered; an existing branch
                        // is never forced.
                        ApplicationManager.getApplication()
                                .invokeLater(
                                        () ->
                                                approve(
                                                        project,
                                                        entry,
                                                        done,
                                                        ex.getMessage() + "\n\n",
                                                        ex.next()),
                                        project.getDisposed());
                        return null;
                    }
                },
                entry,
                done);
    }

    /**
     * Asks for the reason, which reaches the task, and drops the push.
     *
     * @param project The project whose window asks.
     * @param entry The push.
     * @param done Runs on the UI thread once the push was dropped.
     */
    static void reject(Project project, Waiting.Entry entry, Runnable done) {
        final String reason =
                Messages.showInputDialog(
                        project,
                        "Why is "
                                + entry.push().subject()
                                + " rejected? The task that pushed it is told.",
                        "Reject",
                        null);
        if (reason == null || reason.isBlank()) {
            return;
        }
        run(
                project,
                "Rejecting " + entry.push().name(),
                gate -> {
                    final Rejected rejected =
                            gate.reject(
                                    entry.project(),
                                    entry.repository(),
                                    entry.push().name(),
                                    reason.strip());
                    IntoProjects.decided(project, entry, false);
                    return new Said(
                            rejected.told()
                                    ? "Rejected; its task was told"
                                    : "Rejected; its task is gone from the machine and was not told",
                            rejected.endUnkept());
                },
                entry,
                done);
    }

    private interface Call {
        /**
         * Returns what was done, or {@code null} when nothing was and the person is asked again.
         */
        @Nullable Said on(Gate gate);
    }

    /**
     * What a person is told once a decision stood.
     *
     * @param done What was done.
     * @param endUnkept The daemon's sentence that the push's end could not be kept; {@code null}
     *     when it was.
     */
    record Said(String done, @Nullable String endUnkept) {

        /**
         * Returns the notification's text in IntelliJ's markup: what was done, and on a line of its
         * own why its end was not kept, both as text.
         *
         * @return The text.
         */
        String text() {
            return endUnkept == null
                    ? Notices.escaped(done)
                    : Notices.escaped(done) + "<br>" + Notices.escaped(endUnkept);
        }

        /**
         * Returns how the notification is marked: a warning when the end was not kept, since the
         * task still waits on something that will not come.
         *
         * @return Its type.
         */
        NotificationType type() {
            return endUnkept == null ? NotificationType.INFORMATION : NotificationType.WARNING;
        }
    }

    private static void run(
            Project project, String title, Call call, Waiting.Entry entry, Runnable done) {
        ProgressManager.getInstance()
                .run(
                        new Task.Backgroundable(project, title, false) {
                            @Override
                            public void run(ProgressIndicator indicator) {
                                final @Nullable Said said;
                                Gate gate = null;
                                try {
                                    gate = PoreloWatch.get().gate(entry.machine());
                                    said = call.on(gate);
                                } catch (RuntimeException ex) {
                                    tell(
                                            project,
                                            Notices.escaped(title + " failed: " + ex.getMessage()),
                                            NotificationType.ERROR);
                                    PoreloWatch.get().readNow();
                                    return;
                                } finally {
                                    if (gate != null) {
                                        closeQuietly(gate);
                                    }
                                }
                                if (said != null) {
                                    tell(project, said.text(), said.type());
                                    ApplicationManager.getApplication()
                                            .invokeLater(done, project.getDisposed());
                                }
                                PoreloWatch.get().readNow();
                            }
                        });
    }

    // What was decided stands, even if closing the connection then fails.
    private static void closeQuietly(Gate gate) {
        try {
            gate.close();
        } catch (RuntimeException ex) {
            // See above.
        }
    }

    private static void tell(Project project, String text, NotificationType type) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup(PoreloWatch.NOTIFICATIONS)
                .createNotification(text, type)
                .notify(project);
    }
}
