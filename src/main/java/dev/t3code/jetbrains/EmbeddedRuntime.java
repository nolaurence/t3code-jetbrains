package dev.t3code.jetbrains;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.Project;
import com.intellij.util.EnvironmentUtil;
import com.intellij.util.concurrency.AppExecutorUtil;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@Service(Service.Level.PROJECT)
public final class EmbeddedRuntime implements Disposable {
    static final Gson JSON = new Gson();
    private static final Logger LOG = Logger.getInstance(EmbeddedRuntime.class);
    private static final String PREFIX = "@t3-ide ";
    private final Project project;
    final Path installed;
    final String origin;
    private final String workspaceId;
    private final Map<String, Consumer<JsonObject>> pending = new ConcurrentHashMap<>();
    private final ExecutorService writes = AppExecutorUtil.createBoundedApplicationPoolExecutor("T3 Code IPC", 1);
    private volatile Process process;
    private BufferedWriter stdin;
    private volatile boolean disposed;

    public EmbeddedRuntime(Project project) {
        this.project = project;
        var descriptor = PluginManagerCore.getPlugin(PluginId.getId("dev.t3code.jetbrains"));
        installed = java.util.Objects.requireNonNull(descriptor).getPluginPath().resolve("runtime");
        try {
            workspaceId = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(project.getLocationHash().getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        origin = "http://t3-" + workspaceId + ".localhost";
    }

    static EmbeddedRuntime of(Project project) { return project.getService(EmbeddedRuntime.class); }
    void track(String id, Consumer<JsonObject> client) { pending.put(id, client); }
    void forget(String id) { pending.remove(id); }

    void send(JsonObject frame) {
        if (disposed) return;
        var id = string(frame, "id");
        try {
            writes.execute(() -> {
                if (disposed) return;
                try {
                    ensureStarted();
                    stdin.write(JSON.toJson(frame));
                    stdin.newLine();
                    stdin.flush();
                } catch (IOException e) {
                    LOG.warn("Could not send to T3 Code runtime", e);
                    fail(id);
                }
            });
        } catch (RejectedExecutionException ignored) { fail(id); }
    }

    private void ensureStarted() throws IOException {
        if (process != null && process.isAlive()) return;
        var cwd = project.getBasePath();
        if (cwd == null) throw new IOException("Open a local project to use T3 Code.");
        var state = Path.of(PathManager.getConfigPath(), "t3-code", workspaceId);
        Files.createDirectories(state);
        var node = installed.resolve("node");
        if (!Files.isExecutable(node)) node.toFile().setExecutable(true);
        var builder = new ProcessBuilder(node.toString(), installed.resolve("bin.mjs").toString(), "__jetbrains",
            state.toString(), cwd, installed.resolve("client").toString(), origin).directory(new File(cwd));
        configureEnvironment(builder.environment(), EnvironmentUtil.getEnvironmentMap(), installed);
        builder.redirectError(ProcessBuilder.Redirect.appendTo(state.resolve("runtime.log").toFile()));
        var child = builder.start();
        process = child;
        stdin = new BufferedWriter(new OutputStreamWriter(child.getOutputStream(), StandardCharsets.UTF_8));
        AppExecutorUtil.getAppExecutorService().execute(() -> read(child));
    }

    static void configureEnvironment(Map<String, String> env, Map<String, String> ideEnv, Path installed) {
        if (File.separatorChar == '/') {
            var paths = new LinkedHashSet<String>();
            for (var source : new String[]{ideEnv.get("PATH"), env.get("PATH")}) {
                if (source == null) continue;
                for (var entry : source.split(Pattern.quote(File.pathSeparator))) {
                    if (entry.startsWith("/")) paths.add(entry);
                }
            }
            var home = env.get("HOME");
            if (home == null || home.isBlank()) home = System.getProperty("user.home");
            if (home != null && home.startsWith("/")) {
                paths.add(Path.of(home, ".local", "bin").toString());
                paths.add(Path.of(home, "bin").toString());
            }
            if (System.getProperty("os.name").equals("Mac OS X")) paths.add("/opt/homebrew/bin");
            paths.add("/usr/local/bin");
            // npm-installed provider CLIs use /usr/bin/env node even when the runtime itself uses an absolute path.
            paths.add(installed.toAbsolutePath().toString());
            env.put("PATH", String.join(File.pathSeparator, paths));
        }
        env.keySet().removeIf(key -> key.startsWith("T3CODE_") || key.startsWith("OTEL_") || key.equals("NODE_OPTIONS"));
        env.put("DO_NOT_TRACK", "1");
    }

    private void read(Process child) {
        try (var reader = child.inputReader(StandardCharsets.UTF_8)) {
            for (String line; (line = reader.readLine()) != null; ) {
                if (!line.startsWith(PREFIX)) { LOG.debug(line); continue; }
                try {
                    var msg = JSON.fromJson(line.substring(PREFIX.length()), JsonObject.class);
                    var id = string(msg, "id");
                    var callback = id == null ? null : pending.get(id);
                    if (callback != null) callback.accept(msg);
                    if ("fatal".equals(string(msg, "type"))) LOG.warn(string(msg, "message"));
                } catch (RuntimeException e) { LOG.warn("Invalid T3 Code runtime frame", e); }
            }
        } catch (IOException e) { if (!disposed) LOG.warn("T3 Code runtime channel closed", e); }
        finally { if (process == child) pending.keySet().forEach(this::fail); }
    }

    private void fail(String id) {
        var callback = id == null ? null : pending.remove(id);
        if (callback != null) callback.accept(JSON.toJsonTree(Map.of("id", id, "type", "failed")).getAsJsonObject());
    }

    static String string(JsonObject msg, String key) {
        var value = msg.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }

    @Override public void dispose() {
        disposed = true;
        writes.shutdownNow();
        var child = process;
        if (child != null) child.destroy();
        pending.keySet().forEach(this::fail);
    }
}
