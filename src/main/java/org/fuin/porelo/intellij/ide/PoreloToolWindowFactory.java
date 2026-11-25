package org.fuin.porelo.intellij.ide;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;

/**
 * The tool window "Porelo": what waits, by project and repository, and what tasks hold for the
 * owner account's decision, the same whatever project the window shows.
 */
public final class PoreloToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(Project project, ToolWindow toolWindow) {
        final Content waiting = ContentFactory.getInstance().createContent(null, "Waiting", false);
        waiting.setComponent(new WaitingTree(project, waiting));
        toolWindow.getContentManager().addContent(waiting);
        final Content held = ContentFactory.getInstance().createContent(null, HeldPanel.TAB, false);
        held.setComponent(new HeldPanel(held));
        toolWindow.getContentManager().addContent(held);
    }
}
