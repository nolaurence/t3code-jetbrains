package dev.t3code.jetbrains;

import com.google.gson.JsonObject;
import com.intellij.ide.impl.TrustedProjects;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileTypes.INativeFileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.cef.callback.CefQueryCallback;

import java.nio.file.Files;
import java.nio.file.Path;

final class IdeFileOpener {
    record Target(Path path, int line, int column) {
        static Target parse(String payload) {
            var request = EmbeddedRuntime.JSON.fromJson(payload, JsonObject.class);
            var name = EmbeddedRuntime.string(request, "path");
            if (name == null || name.replace('\\', '/').startsWith("//")) {
                throw new IllegalArgumentException("Expected a local file path.");
            }
            var path = name.startsWith("~/") ? Path.of(System.getProperty("user.home")).resolve(name.substring(2))
                : Path.of(name);
            if (!path.isAbsolute()) throw new IllegalArgumentException("Expected an absolute path.");
            return new Target(path.normalize(), index(request, "line", -1), index(request, "column", 0));
        }

        private static int index(JsonObject request, String key, int fallback) {
            var value = request.get(key);
            if (value == null) return fallback;
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException("Expected a numeric file position.");
            }
            int position = value.getAsBigDecimal().intValueExact();
            if (position < 1) throw new IllegalArgumentException("Expected a positive file position.");
            return position - 1;
        }
    }

    static void open(Project project, String payload, CefQueryCallback callback) {
        final Target target;
        try { target = Target.parse(payload); }
        catch (RuntimeException e) { callback.failure(400, "Invalid local file path or position."); return; }

        AppExecutorUtil.getAppExecutorService().execute(() -> {
            if (project.isDisposed() || !TrustedProjects.isTrusted(project)) {
                callback.failure(403, "Trust this IDE project before opening files from chat.");
                return;
            }
            final VirtualFile file;
            try {
                file = Files.isRegularFile(target.path()) ? LocalFileSystem.getInstance()
                    .refreshAndFindFileByPath(FileUtil.toSystemIndependentName(target.path().toString())) : null;
            } catch (RuntimeException e) { callback.failure(500, "Could not find the local file."); return; }

            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed() || !TrustedProjects.isTrusted(project)) {
                    callback.failure(403, "The IDE project is closed or not trusted.");
                } else if (file == null || !file.isValid()) {
                    callback.failure(404, "File not found: " + target.path());
                } else {
                    try {
                        var manager = FileEditorManager.getInstance(project);
                        if (file.getFileType() instanceof INativeFileType || !manager.canOpenFile(file)) {
                            callback.failure(415, "This file type has no editor in the current IDE.");
                            return;
                        }
                        var descriptor = target.line() < 0 ? new OpenFileDescriptor(project, file)
                            : new OpenFileDescriptor(project, file, target.line(), target.column());
                        if (manager.openEditor(descriptor, true).isEmpty()) {
                            callback.failure(415, "This file type has no editor in the current IDE.");
                        } else callback.success("");
                    } catch (RuntimeException e) { callback.failure(500, "The IDE could not open this file."); }
                }
            });
        });
    }
}
