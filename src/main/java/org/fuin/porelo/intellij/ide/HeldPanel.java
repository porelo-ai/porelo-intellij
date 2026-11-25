package org.fuin.porelo.intellij.ide;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.content.Content;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.tree.TreeUtil;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import org.fuin.porelo.intellij.gate.HeldItem;
import org.jspecify.annotations.Nullable;

/**
 * The tool window's tab of held items: every machine's tasks with the items they hold for the owner
 * account's decision. The item chosen is shown with its exact text as plain text and decided alone,
 * released or refused when it goes out, passed or dismissed when it came in; nothing here starts a
 * run.
 */
final class HeldPanel extends SimpleToolWindowPanel {

    private static final long serialVersionUID = 1L;

    /** The tab's name, by which a notification selects it. */
    static final String TAB = "Held";

    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode();

    private final DefaultTreeModel model = new DefaultTreeModel(root);

    private final Tree tree = new Tree(model);

    // Plain text areas, never HTML: an item's text is shown exactly, never as markup or links.
    private final JBTextArea facts = plain();

    private final JBTextArea text = plain();

    private final JBTextArea state = plain();

    private final JBTextArea replaces = plain();

    private final JBTextArea suggestion = plain();

    private final JPanel proposed = new JPanel(new BorderLayout());

    private final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));

    private transient @Nullable Chosen chosen;

    private transient boolean deciding;

    /** What the last decision came to, kept above the machines' warnings until the next one. */
    private transient @Nullable String decided;

    /** Set while the tree is rebuilt, whose selection events are not the person's choice. */
    private transient boolean rebuilding;

    /**
     * One item as the tree holds it.
     *
     * @param machine The machine that holds it.
     * @param item The item as read.
     */
    record Chosen(String machine, HeldItem item) {}

    /**
     * One task as the tree holds it.
     *
     * @param task The task's name.
     * @param count How many items it holds.
     */
    private record TaskNode(String task, int count) {}

    /**
     * One machine as the tree holds it.
     *
     * @param machine The machine's name.
     * @param count How many items its tasks hold.
     */
    private record MachineNode(String machine, int count) {}

    HeldPanel(Disposable parent) {
        super(true, true);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.getEmptyText().setText("No item is held for your decision");
        tree.setCellRenderer(new Renderer());
        tree.addTreeSelectionListener(
                event -> {
                    if (!rebuilding) {
                        choose(chosenAt(tree.getSelectionPath()));
                    }
                });
        facts.setFont(JBUI.Fonts.label());
        text.setFont(JBUI.Fonts.create(java.awt.Font.MONOSPACED, JBUI.Fonts.label().getSize()));
        text.setOpaque(true);
        text.setBorder(JBUI.Borders.empty(6, 8));
        suggestion.setFont(text.getFont());
        suggestion.setOpaque(true);
        proposed.add(replaces, BorderLayout.NORTH);
        proposed.add(new JBScrollPane(suggestion), BorderLayout.CENTER);
        final JPanel below = new JPanel(new BorderLayout());
        below.add(proposed, BorderLayout.CENTER);
        below.add(buttons, BorderLayout.SOUTH);
        final JPanel detail = new JPanel(new BorderLayout());
        detail.add(facts, BorderLayout.NORTH);
        detail.add(new JBScrollPane(text), BorderLayout.CENTER);
        detail.add(below, BorderLayout.SOUTH);
        final JBSplitter split = new JBSplitter(true, 0.4f);
        split.setFirstComponent(new JBScrollPane(tree));
        split.setSecondComponent(detail);
        final JPanel content = new JPanel(new BorderLayout());
        content.add(split, BorderLayout.CENTER);
        content.add(state, BorderLayout.SOUTH);
        setContent(content);
        setToolbar(toolbar());
        choose(null);
        final PoreloWatch watch = PoreloWatch.get();
        // Each change shows what stands last, so two reads that finish out of order show the newer.
        watch.listenHeld(
                ignored ->
                        ApplicationManager.getApplication()
                                .invokeLater(() -> show(PoreloWatch.get().held())),
                parent);
        show(watch.held());
        watch.start();
    }

    /**
     * Shows the tab in a project's tool window.
     *
     * @param project The project whose window shows it.
     */
    static void show(Project project) {
        final @Nullable ToolWindow window =
                ToolWindowManager.getInstance(project).getToolWindow("Porelo");
        if (window == null) {
            return;
        }
        window.activate(
                () -> {
                    final @Nullable Content tab = window.getContentManager().findContent(TAB);
                    if (tab != null) {
                        window.getContentManager().setSelectedContent(tab);
                    }
                });
    }

    private JComponent toolbar() {
        final DefaultActionGroup actions = new DefaultActionGroup();
        actions.add(
                new DumbAwareAction(
                        "Read Again",
                        "Ask every machine again what its tasks hold",
                        AllIcons.Actions.Refresh) {
                    @Override
                    public void actionPerformed(AnActionEvent event) {
                        PoreloWatch.get().readHeldNow();
                    }

                    @Override
                    public ActionUpdateThread getActionUpdateThread() {
                        return ActionUpdateThread.BGT;
                    }
                });
        final var bar =
                ActionManager.getInstance().createActionToolbar("PoreloHeld", actions, true);
        bar.setTargetComponent(tree);
        return bar.getComponent();
    }

    private void show(Held held) {
        final @Nullable Chosen before = chosen;
        rebuilding = true;
        root.removeAllChildren();
        @Nullable TreePath again = null;
        @Nullable Chosen kept = null;
        for (final Map.Entry<String, List<HeldItem>> machine : held.items().entrySet()) {
            if (machine.getValue().isEmpty()) {
                continue;
            }
            final DefaultMutableTreeNode machineNode =
                    new DefaultMutableTreeNode(
                            new MachineNode(machine.getKey(), machine.getValue().size()));
            for (final Map.Entry<String, List<HeldItem>> task :
                    Held.byTask(machine.getValue()).entrySet()) {
                final DefaultMutableTreeNode taskNode =
                        new DefaultMutableTreeNode(
                                new TaskNode(task.getKey(), task.getValue().size()));
                for (final HeldItem item : task.getValue()) {
                    final Chosen one = new Chosen(machine.getKey(), item);
                    final DefaultMutableTreeNode itemNode = new DefaultMutableTreeNode(one);
                    taskNode.add(itemNode);
                    if (before != null && same(before, one)) {
                        again = new TreePath(itemNode.getPath());
                        kept = one;
                    }
                }
                machineNode.add(taskNode);
            }
            root.add(machineNode);
        }
        try {
            model.reload();
            TreeUtil.expandAll(tree);
            if (again != null) {
                tree.setSelectionPath(again);
            } else {
                tree.clearSelection();
            }
        } finally {
            rebuilding = false;
        }
        // The item still shown keeps its text's scroll position; another is shown from its start.
        if (kept == null) {
            choose(null);
        } else {
            chosen = kept;
        }
        final List<String> said = new ArrayList<>();
        if (decided != null) {
            said.add(decided);
        }
        held.cannotRead()
                .forEach(
                        (machine, tasks) ->
                                tasks.forEach(
                                        task ->
                                                said.add(
                                                        "⚠ The holds of "
                                                                + task
                                                                + " on "
                                                                + machine
                                                                + " cannot be read; its items are"
                                                                + " not listed")));
        held.unreadable()
                .forEach(
                        (machine, why) ->
                                said.add(
                                        "⚠ The items held on "
                                                + machine
                                                + " cannot be read: "
                                                + why));
        state.setText(String.join("\n", said));
        state.setVisible(!said.isEmpty());
    }

    // The same item as before, unchanged: a changed digest is a new text and is chosen anew.
    private static boolean same(Chosen before, Chosen now) {
        return before.machine().equals(now.machine())
                && before.item().task().equals(now.item().task())
                && before.item().id().equals(now.item().id())
                && before.item().digest().equals(now.item().digest());
    }

    private static @Nullable Chosen chosenAt(@Nullable TreePath path) {
        return path != null
                        && path.getLastPathComponent() instanceof DefaultMutableTreeNode node
                        && node.getUserObject() instanceof Chosen one
                ? one
                : null;
    }

    private void choose(@Nullable Chosen one) {
        chosen = one;
        buttons.removeAll();
        if (one == null) {
            facts.setText("Choose an item to read it and decide it.");
            text.setText("");
            proposed.setVisible(false);
        } else {
            facts.setText(String.join("\n", Held.facts(one.machine(), one.item())));
            text.setText(one.item().text());
            text.setCaretPosition(0);
            // A release sends the replacement too, so it is shown whole beside the text.
            final @Nullable String heading = Held.replaces(one.item());
            final @Nullable String replacement = one.item().suggestion();
            proposed.setVisible(heading != null && replacement != null);
            replaces.setText(heading == null ? "" : heading);
            suggestion.setText(replacement == null ? "" : replacement);
            suggestion.setCaretPosition(0);
            for (final HeldItem.Decision decision : one.item().direction().decisions()) {
                final JButton button = new JButton(decision.label());
                button.setEnabled(!deciding);
                button.addActionListener(event -> decide(one, decision));
                buttons.add(button);
            }
        }
        buttons.revalidate();
        buttons.repaint();
    }

    private void decide(Chosen one, HeldItem.Decision decision) {
        deciding = true;
        decided = null;
        choose(one);
        state.setText(decision.label() + " item " + one.item().id() + "...");
        state.setVisible(true);
        ApplicationManager.getApplication()
                .executeOnPooledThread(
                        () -> {
                            String said;
                            try {
                                PoreloWatch.get().decide(one.machine(), one.item(), decision);
                                said = done(decision) + " item " + one.item().id();
                            } catch (RuntimeException ex) {
                                // The daemon's refusal is said in its words; nothing was decided.
                                said =
                                        ex.getMessage() == null
                                                ? ex.getClass().getSimpleName()
                                                : ex.getMessage();
                            }
                            final String words = said;
                            ApplicationManager.getApplication()
                                    .invokeLater(
                                            () -> {
                                                deciding = false;
                                                decided = words;
                                                choose(chosen);
                                                show(PoreloWatch.get().held());
                                            },
                                            ModalityState.any());
                        });
    }

    private static String done(HeldItem.Decision decision) {
        return switch (decision) {
            case RELEASE -> "Released";
            case REFUSE -> "Refused";
            case PASS -> "Passed";
            case DISMISS -> "Dismissed";
        };
    }

    private static JBTextArea plain() {
        final JBTextArea area = new JBTextArea();
        area.setEditable(false);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(JBUI.Borders.empty(6, 8));
        return area;
    }

    /** A machine and a task as their name and count, an item as its direction and start. */
    private static final class Renderer extends ColoredTreeCellRenderer {

        private static final long serialVersionUID = 1L;

        @Override
        public void customizeCellRenderer(
                JTree tree,
                Object value,
                boolean selected,
                boolean expanded,
                boolean leaf,
                int row,
                boolean hasFocus) {
            if (!(value instanceof DefaultMutableTreeNode node)) {
                return;
            }
            if (node.getUserObject() instanceof MachineNode machine) {
                setIcon(AllIcons.Nodes.Folder);
                append(machine.machine(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                append("  " + machine.count(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
            } else if (node.getUserObject() instanceof TaskNode task) {
                setIcon(AllIcons.Nodes.Module);
                append(task.task(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                append("  " + task.count(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
            } else if (node.getUserObject() instanceof Chosen one) {
                final HeldItem item = one.item();
                setIcon(
                        item.direction() == HeldItem.Direction.OUTGOING
                                ? AllIcons.Actions.Upload
                                : AllIcons.Actions.Download);
                append(item.operation(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                append("  " + firstLine(item.text()), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                append("  " + item.at(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }
        }

        private static String firstLine(String text) {
            final String line = text.lines().findFirst().orElse("");
            // Cut between code points, never inside a surrogate pair.
            return line.codePointCount(0, line.length()) <= 80
                    ? line
                    : line.substring(0, line.offsetByCodePoints(0, 80)) + "...";
        }
    }
}
