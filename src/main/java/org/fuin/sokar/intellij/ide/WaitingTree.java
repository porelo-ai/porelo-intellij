package org.fuin.sokar.intellij.ide;

import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.stream.Collectors;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

import org.fuin.sokar.intellij.gate.Waiting;
import org.jspecify.annotations.Nullable;

import com.intellij.icons.AllIcons;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.tree.TreeUtil;

/**
 * The tool window's content: the projects and repositories where something waits, each with its pushes; a click
 * opens a push for review. The machines that did not answer are said below the list.
 */
final class WaitingTree extends SimpleToolWindowPanel {

    private static final long serialVersionUID = 1L;

    private final transient Project project;

    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode();

    private final DefaultTreeModel model = new DefaultTreeModel(root);

    private final Tree tree = new Tree(model);

    private final JBLabel state = new JBLabel();

    private transient Snapshot shown = Snapshot.NONE;

    /** Where the choice to group by machine is kept, across windows and restarts. */
    private static final String BY_MACHINE = "org.fuin.sokar.groupByMachine";

    WaitingTree(Project project, Disposable parent) {
        super(true, true);
        this.project = project;
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.getEmptyText().setText("Nothing waits for review");
        tree.setCellRenderer(new Renderer());
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                final TreePath path = tree.getPathForLocation(event.getX(), event.getY());
                if (path != null && push(path) != null) {
                    tree.setSelectionPath(path);
                    openSelected();
                }
            }
        });
        state.setBorder(JBUI.Borders.empty(6, 8));
        final JPanel content = new JPanel(new BorderLayout());
        content.add(new JBScrollPane(tree), BorderLayout.CENTER);
        content.add(state, BorderLayout.SOUTH);
        setContent(content);
        setToolbar(toolbar());
        final SokarWatch watch = SokarWatch.get();
        watch.listen(snapshot -> ApplicationManager.getApplication().invokeLater(() -> show(snapshot)), parent);
        show(watch.last());
        watch.start();
    }

    private JComponent toolbar() {
        final DefaultActionGroup actions = new DefaultActionGroup();
        actions.add(new DumbAwareAction("Read Again", "Ask every machine again what waits", AllIcons.Actions.Refresh) {
            @Override
            public void actionPerformed(AnActionEvent event) {
                SokarWatch.get().readNow();
            }

            @Override
            public ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }
        });
        actions.add(new DumbAwareToggleAction("Group by Machine", "Show what waits under the machine it waits on",
                AllIcons.Actions.GroupBy) {
            @Override
            public boolean isSelected(AnActionEvent event) {
                return byMachine();
            }

            @Override
            public void setSelected(AnActionEvent event, boolean selected) {
                PropertiesComponent.getInstance().setValue(BY_MACHINE, selected);
                show(shown);
            }

            @Override
            public ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });
        final var bar = ActionManager.getInstance().createActionToolbar("SokarWaiting", actions, true);
        bar.setTargetComponent(tree);
        return bar.getComponent();
    }

    private void openSelected() {
        final TreePath path = tree.getSelectionPath();
        final Waiting.Entry entry = path == null ? null : push(path);
        if (entry != null) {
            ReviewTab.open(project, entry);
        }
    }

    private static Waiting.@Nullable Entry push(TreePath path) {
        return path.getLastPathComponent() instanceof DefaultMutableTreeNode node
                && node.getUserObject() instanceof Waiting.Entry entry ? entry : null;
    }

    private static boolean byMachine() {
        return PropertiesComponent.getInstance().getBoolean(BY_MACHINE, false);
    }

    private void show(Snapshot snapshot) {
        shown = snapshot;
        root.removeAllChildren();
        if (byMachine()) {
            for (final Waiting.MachineGroup machine : snapshot.waiting().byMachine()) {
                final DefaultMutableTreeNode node = new DefaultMutableTreeNode(machine);
                machine.groups().forEach(group -> node.add(groupNode(group)));
                root.add(node);
            }
        } else {
            snapshot.waiting().groups().forEach(group -> root.add(groupNode(group)));
        }
        model.reload();
        TreeUtil.expandAll(tree);
        final StringBuilder said = new StringBuilder("<html>");
        if (snapshot.at() == null) {
            said.append("Reading what waits...");
        } else if (snapshot.unreadable() != null) {
            said.append("The machines cannot be read: ").append(escape(snapshot.unreadable()));
        }
        if (!snapshot.waiting().silent().isEmpty()) {
            said.append(said.length() > "<html>".length() ? "<br>" : "").append(snapshot.waiting().silent().stream()
                    .map(silent -> "⚠ <b>" + escape(silent.machine()) + "</b> " + escape(silent.why()))
                    .collect(Collectors.joining("<br>")));
        }
        state.setText(said.append("</html>").toString());
        state.setVisible(said.length() > "<html></html>".length());
    }

    private static DefaultMutableTreeNode groupNode(Waiting.Group group) {
        final DefaultMutableTreeNode node = new DefaultMutableTreeNode(group);
        group.pushes().forEach(entry -> node.add(new DefaultMutableTreeNode(entry)));
        return node;
    }

    private static String escape(@Nullable String text) {
        return StringUtil.escapeXmlEntities(String.valueOf(text));
    }

    /** A group as its label and count, a push as its task, change, age and far-reaching files. */
    private static final class Renderer extends ColoredTreeCellRenderer {

        private static final long serialVersionUID = 1L;

        @Override
        public void customizeCellRenderer(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf,
                int row, boolean hasFocus) {
            if (!(value instanceof DefaultMutableTreeNode node)) {
                return;
            }
            if (node.getUserObject() instanceof Waiting.MachineGroup machine) {
                setIcon(AllIcons.Nodes.Folder);
                append(machine.machine(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                append("  " + machine.groups().stream().mapToInt(group -> group.pushes().size()).sum(),
                        SimpleTextAttributes.GRAYED_ATTRIBUTES);
            } else if (node.getUserObject() instanceof Waiting.Group group) {
                setIcon(AllIcons.Nodes.Module);
                append(group.label(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                append("  " + group.pushes().size(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
            } else if (node.getUserObject() instanceof Waiting.Entry entry) {
                setIcon(AllIcons.Vcs.Branch);
                append(entry.push().name(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                append("  " + entry.push().subject(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                append("  " + entry.push().waiting(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                if (entry.farReaching() > 0) {
                    append("  ⚠ " + entry.farReaching(), SimpleTextAttributes.ERROR_ATTRIBUTES);
                    setToolTipText(entry.farReaching() + " far-reaching file(s): build, CI, hooks or IDE settings");
                } else {
                    setToolTipText(null);
                }
            }
        }

    }

}
