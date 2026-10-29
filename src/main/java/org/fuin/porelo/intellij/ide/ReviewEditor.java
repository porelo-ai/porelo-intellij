package org.fuin.porelo.intellij.ide;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.DiffRequestPanel;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.changes.ByteBackedContentRevision;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ui.ChangeNodeDecorator;
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNodeRenderer;
import com.intellij.openapi.vcs.changes.ui.SimpleAsyncChangesBrowser;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.vcsUtil.VcsUtil;
import java.awt.BorderLayout;
import java.beans.PropertyChangeListener;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.fuin.porelo.intellij.gate.Waiting;
import org.jspecify.annotations.Nullable;

/**
 * A review as IntelliJ shows a comparison with a branch: the tree of the changed files on the left,
 * the far-reaching ones marked; the diff of the selected file on the right; Approve, Reject and
 * what the push is, above.
 */
final class ReviewEditor extends UserDataHolderBase implements FileEditor {

    private static final com.intellij.openapi.diagnostic.Logger LOG =
            com.intellij.openapi.diagnostic.Logger.getInstance(ReviewEditor.class);

    private final Project project;

    private final ReviewFile file;

    private final JPanel component = new JPanel(new BorderLayout());

    private final SimpleAsyncChangesBrowser tree;

    private final DiffRequestPanel diff;

    private final Map<Change, ReviewTab.Side> sides = new IdentityHashMap<>();

    ReviewEditor(Project project, ReviewFile file) {
        this.project = project;
        this.file = file;
        final Waiting.Entry entry = file.entry();
        final ReviewTab.Read read = file.read();
        final List<Change> changes = new ArrayList<>();
        @Nullable Change first = null;
        for (final ReviewTab.Side side : read.files()) {
            final Change change = change(entry, read, side);
            sides.put(change, side);
            changes.add(change);
            if (first == null) {
                first = change;
            }
        }
        tree = new SimpleAsyncChangesBrowser(project, false, false);
        tree.setChangeNodeDecorator(new FarReaching());
        tree.setChangesToDisplay(changes);
        diff = DiffManager.getInstance().createRequestPanel(project, this, null);
        tree.getViewer().addSelectionListener(this::showSelected, this);
        final JBSplitter split = new JBSplitter(false, "porelo.review.split", 0.3f);
        split.setFirstComponent(tree);
        split.setSecondComponent(diff.getComponent());
        component.add(header(entry, read), BorderLayout.NORTH);
        component.add(split, BorderLayout.CENTER);
        // The tree is built in the background; the first file - the most far-reaching - is selected
        // once it is.
        final Change selectFirst = first;
        if (selectFirst != null) {
            tree.getViewer()
                    .invokeAfterRefresh(
                            () -> tree.getViewer().setSelectedChanges(List.of(selectFirst)));
        }
        showSelected();
    }

    private JComponent header(Waiting.Entry entry, ReviewTab.Read read) {
        final DefaultActionGroup actions = new DefaultActionGroup();
        actions.add(
                namedInWords(
                        new Act(
                                "Approve",
                                "Forward exactly "
                                        + shortId(entry.push().commit())
                                        + ", the commit shown",
                                AllIcons.Actions.Checked,
                                () -> Decisions.approve(project, entry, this::close))));
        actions.add(
                namedInWords(
                        new Act(
                                "Reject",
                                "Drop this push and tell its task why",
                                AllIcons.Actions.Cancel,
                                () -> Decisions.reject(project, entry, this::close))));
        for (final IntoProjects.Target target : read.into()) {
            actions.add(
                    new Act(
                            target.label(),
                            "Fetch this push into "
                                    + target.project().getName()
                                    + " as porelo/"
                                    + entry.push().name()
                                    + ", leaving what is checked out as it is",
                            AllIcons.Actions.Download,
                            () -> IntoProjects.open(target, entry)));
        }
        actions.add(
                new Act(
                        "Read Again",
                        "Read this push again from its machine",
                        AllIcons.Actions.Refresh,
                        () -> {
                            closeEvery(project, entry);
                            ReviewTab.open(project, entry);
                        }));
        final var bar =
                ActionManager.getInstance().createActionToolbar("PoreloReview", actions, true);
        bar.setTargetComponent(component);
        final String asked = read.review().asked();
        final JBLabel what =
                new JBLabel(
                        "<html><b>"
                                + escape(entry.push().subject())
                                + "</b> &nbsp; "
                                + escape(entry.push().name())
                                + " "
                                + shortId(entry.push().commit())
                                + " against "
                                + escape(read.changes().branch())
                                + " "
                                + shortId(read.changes().base())
                                + " &nbsp;·&nbsp; "
                                + escape(entry.project() + " / " + entry.repository())
                                + " on "
                                + escape(entry.machine().name())
                                + ", waiting "
                                + escape(entry.push().waiting())
                                + (asked == null
                                        ? "<br>Its task is gone from the machine."
                                        : asked.isEmpty() ? "" : "<br>Asked: " + escape(asked))
                                + "</html>");
        what.setBorder(JBUI.Borders.empty(4, 8));
        final JPanel header = new JPanel(new BorderLayout());
        header.add(bar.getComponent(), BorderLayout.WEST);
        header.add(what, BorderLayout.CENTER);
        header.setBorder(JBUI.Borders.customLineBottom(JBUI.CurrentTheme.ToolWindow.borderColor()));
        return header;
    }

    private void showSelected() {
        final List<Change> selected = tree.getSelectedChanges();
        final ReviewTab.Side side = selected.isEmpty() ? null : sides.get(selected.getFirst());
        if (side == null) {
            diff.setRequest(null);
            return;
        }
        final String path = side.change().path();
        final ReviewTab.Read read = file.read();
        diff.setRequest(
                new SimpleDiffRequest(
                        path,
                        content(side.base(), path),
                        content(side.pushed(), path),
                        read.changes().branch() + " " + shortId(read.changes().base()),
                        file.entry().push().name() + " " + shortId(read.changes().commit())));
    }

    private DiffContent content(byte @Nullable [] bytes, String path) {
        if (bytes == null) {
            return DiffContentFactory.getInstance().createEmpty();
        }
        final String name = path.substring(path.lastIndexOf('/') + 1);
        try {
            return DiffContentFactory.getInstance()
                    .createFromBytes(
                            project,
                            bytes,
                            FileTypeManager.getInstance().getFileTypeByFileName(name),
                            name);
        } catch (java.io.IOException ex) {
            return DiffContentFactory.getInstance()
                    .create("Cannot show " + path + ": " + ex.getMessage());
        }
    }

    private static Change change(Waiting.Entry entry, ReviewTab.Read read, ReviewTab.Side side) {
        // Under the repository's name, so the tree's root says whose files these are.
        final FilePath path =
                VcsUtil.getFilePath("/" + entry.repository() + "/" + side.change().path(), false);
        return new Change(
                side.base() == null ? null : new Revision(path, side.base(), read.changes().base()),
                side.pushed() == null
                        ? null
                        : new Revision(path, side.pushed(), read.changes().commit()));
    }

    private void close() {
        closeEvery(project, file.entry());
        PoreloWatch.get().readNow();
    }

    /**
     * Closes every review of a push that is open in a project's window: the one a decision was
     * taken in, and any other of the same push, which shows something that waits no more.
     *
     * @param project The project.
     * @param entry The push.
     */
    static void closeEvery(Project project, Waiting.Entry entry) {
        final FileEditorManager editors = FileEditorManager.getInstance(project);
        final List<VirtualFile> open = List.of(editors.getOpenFiles());
        final List<VirtualFile> reviews =
                open.stream()
                        .filter(
                                candidate ->
                                        candidate instanceof ReviewFile review
                                                && review.entry()
                                                        .machine()
                                                        .name()
                                                        .equals(entry.machine().name())
                                                && review.entry().project().equals(entry.project())
                                                && review.entry()
                                                        .repository()
                                                        .equals(entry.repository())
                                                && review.entry()
                                                        .push()
                                                        .name()
                                                        .equals(entry.push().name()))
                        .toList();
        if (reviews.isEmpty()) {
            LOG.warn("No open review of " + entry.push().name() + " to close among " + open);
        }
        reviews.forEach(editors::closeFile);
    }

    private static String shortId(String commit) {
        return commit.length() > 12 ? commit.substring(0, 12) : commit;
    }

    private static String escape(String text) {
        return StringUtil.escapeXmlEntities(text);
    }

    @Override
    public JComponent getComponent() {
        return component;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return tree.getViewer();
    }

    @Override
    public String getName() {
        return "Review";
    }

    @Override
    public void setState(FileEditorState state) {
        // A review has no state to restore: it is read again from its machine.
    }

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public void addPropertyChangeListener(PropertyChangeListener listener) {
        // Nothing here changes in a way another component listens for.
    }

    @Override
    public void removePropertyChangeListener(PropertyChangeListener listener) {
        // Nothing was added.
    }

    @Override
    public VirtualFile getFile() {
        return file;
    }

    @Override
    public void dispose() {
        // The diff panel and the tree's listener were registered with this editor, and go with it.
        tree.getViewer().shutdown();
    }

    /** Marks a far-reaching file in the tree with why it reaches far. */
    private final class FarReaching implements ChangeNodeDecorator {

        @Override
        public void decorate(Change change, SimpleColoredComponent component, boolean flatten) {
            final ReviewTab.Side side = sides.get(change);
            if (side != null && side.farReaching() && side.ranked() != null) {
                component.append(
                        "  ⚠ " + side.ranked().reason(), SimpleTextAttributes.ERROR_ATTRIBUTES);
            }
        }

        @Override
        public void preDecorate(
                Change change, ChangesBrowserNodeRenderer renderer, boolean flatten) {
            // Only the text after the name is the plugin's.
        }
    }

    /** One side of a file, from the cache. */
    private record Revision(FilePath path, byte[] bytes, String commit)
            implements ByteBackedContentRevision {

        @Override
        public byte[] getContentAsBytes() {
            return bytes;
        }

        @Override
        public String getContent() {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        @Override
        public FilePath getFile() {
            return path;
        }

        @Override
        public VcsRevisionNumber getRevisionNumber() {
            return new Commit(commit);
        }
    }

    /** A commit id as the change tree shows it. */
    private record Commit(String id) implements VcsRevisionNumber {

        @Override
        public String asString() {
            return id;
        }

        @Override
        public int compareTo(VcsRevisionNumber other) {
            return id.compareTo(other.asString());
        }
    }

    /** The two decisions read beside their icons; the other actions keep theirs as tooltips. */
    private static Act namedInWords(Act act) {
        act.getTemplatePresentation().putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true);
        return act;
    }

    /** One toolbar button of the review. */
    private static final class Act extends DumbAwareAction {

        private final Runnable run;

        Act(String text, String description, javax.swing.Icon icon, Runnable run) {
            super(text, description, icon);
            this.run = run;
        }

        @Override
        public void actionPerformed(AnActionEvent event) {
            run.run();
        }

        @Override
        public ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }
    }
}
