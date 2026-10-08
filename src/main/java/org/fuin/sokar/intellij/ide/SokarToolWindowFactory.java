package org.fuin.sokar.intellij.ide;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;

/**
 * The tool window "Sokar": what waits, by project and repository, the same whatever project the window shows.
 */
public final class SokarToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(Project project, ToolWindow toolWindow) {
        final Content content = ContentFactory.getInstance().createContent(null, "", false);
        content.setComponent(new WaitingTree(project, content));
        toolWindow.getContentManager().addContent(content);
    }

}
