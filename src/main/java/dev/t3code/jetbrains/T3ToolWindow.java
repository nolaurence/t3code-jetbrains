package dev.t3code.jetbrains;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.jcef.JBCefApp;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;

public final class T3ToolWindow implements ToolWindowFactory, DumbAware {
    @Override public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        var manager = toolWindow.getContentManager();
        var factory = ContentFactory.getInstance();
        if (!JBCefApp.isSupported()) {
            manager.addContent(factory.createContent(new JBLabel("T3 Code requires JetBrains Runtime with JCEF."), "", false));
            return;
        }
        try {
            var page = new EmbeddedPage(EmbeddedRuntime.of(project), "sidebar", path -> T3EditorProvider.open(project, path));
            var content = factory.createContent(page.component(), "", false);
            content.setDisposer(page);
            manager.addContent(content);
            page.open("/");
        } catch (IOException e) {
            manager.addContent(factory.createContent(new JBLabel("T3 Code Web assets are missing. Reinstall the plugin."), "", false));
        }
    }
}
