package dev.t3code.jetbrains;

import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.jcef.JBCefApp;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import javax.swing.JComponent;
import org.jetbrains.annotations.NotNull;

public final class T3EditorProvider implements FileEditorProvider, DumbAware {
    private static final class ChatFile extends LightVirtualFile {
        String path;
        ChatFile(String path) {
            super("T3 Code", PlainTextFileType.INSTANCE, "");
            this.path = path;
            setWritable(false);
        }
    }

    static void open(Project project, String path) {
        if (project.isDisposed() || !EmbeddedPage.validPath(path)) return;
        var manager = FileEditorManager.getInstance(project);
        for (var file : manager.getOpenFiles()) {
            if (file instanceof ChatFile chat) {
                chat.path = path;
                for (var editor : manager.openFile(chat, true)) {
                    if (editor instanceof ChatEditor view) view.open(path);
                }
                return;
            }
        }
        manager.openFile(new ChatFile(path), true);
    }

    @Override public boolean accept(@NotNull Project project, @NotNull VirtualFile file) { return file instanceof ChatFile; }
    @Override public @NotNull FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
        return new ChatEditor(project, (ChatFile) file);
    }
    @Override public @NotNull String getEditorTypeId() { return "t3code.chat"; }
    @Override public @NotNull FileEditorPolicy getPolicy() { return FileEditorPolicy.HIDE_DEFAULT_EDITOR; }

    private static final class ChatEditor extends UserDataHolderBase implements FileEditor {
        private final ChatFile file;
        private final EmbeddedPage page;
        private final JComponent component;
        ChatEditor(Project project, ChatFile file) {
            this.file = file;
            EmbeddedPage created = null;
            String error = "T3 Code requires JetBrains Runtime with JCEF.";
            if (JBCefApp.isSupported()) {
                try { created = new EmbeddedPage(EmbeddedRuntime.of(project), "editor", this::open); }
                catch (IOException e) { error = "T3 Code Web assets are missing. Reinstall the plugin."; }
            }
            page = created;
            component = page == null ? new JBLabel(error) : page.component();
            open(file.path);
        }
        void open(String path) { if (page != null) page.open(path); }
        @Override public @NotNull JComponent getComponent() { return component; }
        @Override public JComponent getPreferredFocusedComponent() { return component; }
        @Override public @NotNull String getName() { return "T3 Code"; }
        @Override public void setState(@NotNull FileEditorState state) { }
        @Override public boolean isModified() { return false; }
        @Override public boolean isValid() { return true; }
        @Override public @NotNull VirtualFile getFile() { return file; }
        @Override public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) { }
        @Override public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) { }
        @Override public void dispose() { if (page != null) Disposer.dispose(page); }
    }
}
