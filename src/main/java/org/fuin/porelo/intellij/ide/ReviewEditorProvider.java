package org.fuin.porelo.intellij.ide;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

/**
 * Shows a {@link ReviewFile} as a review, and nothing else.
 */
public final class ReviewEditorProvider implements FileEditorProvider, DumbAware {

    @Override
    public boolean accept(Project project, VirtualFile file) {
        return file instanceof ReviewFile;
    }

    @Override
    public boolean acceptRequiresReadAction() {
        return false;
    }

    @Override
    public FileEditor createEditor(Project project, VirtualFile file) {
        return new ReviewEditor(project, (ReviewFile) file);
    }

    @Override
    public String getEditorTypeId() {
        return "porelo-review";
    }

    @Override
    public FileEditorPolicy getPolicy() {
        return FileEditorPolicy.HIDE_DEFAULT_EDITOR;
    }

}
