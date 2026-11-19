package dev.t3code.jetbrains;

import com.intellij.ide.impl.TrustedProjects;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.EnvironmentUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import javax.swing.SwingUtilities;

@Service(Service.Level.PROJECT)
public final class LocalSessions implements Disposable {
    private static final Logger LOG = Logger.getInstance(LocalSessions.class);
    static LocalSessions of(Project project) { return project.getService(LocalSessions.class); }
    private final Project project;
    private final SessionArchive archive;
    private final List<Session> recent = new ArrayList<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private final java.util.concurrent.ExecutorService writer = Executors.newSingleThreadExecutor(
        Thread.ofPlatform().name("T3 local session storage").daemon().factory());
    private boolean disposed;
    private boolean archiveReadable = true;
    private String archiveError;
    private List<ModelPicker.Model> codexModels = ModelPicker.defaults(LocalCli.Provider.CODEX);
    private boolean loadingModels;
    private boolean loadedModels;

    static final class Session {
        final String id;
        LocalCli.Provider provider;
        String model;
        String effort;
        final List<SessionArchive.Message> messages;
        volatile String title;
        String cliId;
        String draft = "";
        Running running;
        String access;

        Session(SessionArchive.Thread saved) {
            id = saved.id(); provider = saved.provider(); title = saved.title(); cliId = saved.sessionId();
            messages = new ArrayList<>(saved.messages());
            model = saved.model(); effort = saved.effort();
        }
        SessionArchive.Thread snapshot() { return new SessionArchive.Thread(id, title, provider, cliId, List.copyOf(messages), model, effort); }
    }

    static final class Running implements LocalCli.Output {
        final LocalCli.Run cli = new LocalCli.Run();
        final TurnTimeline timeline;
        volatile String activity = "Sending message...";
        final java.util.function.Consumer<String> sessionId;
        record Approval(String id, String action, String detail) {}
        private Approval approval;
        private java.util.concurrent.CompletableFuture<Boolean> decision;
        synchronized Approval pendingApproval() { return approval; }
        @Override public boolean approve(String action, String detail) {
            final java.util.concurrent.CompletableFuture<Boolean> waiting;
            synchronized (this) {
                if (cli.stopped()) return false;
                approval = new Approval(UUID.randomUUID().toString(), action, detail.substring(0, Math.min(detail.length(), 4096)));
                waiting = decision = new java.util.concurrent.CompletableFuture<>();
            }
            try { return waiting.join(); }
            finally { synchronized (this) { approval = null; decision = null; } }
        }
        synchronized void decide(String id, boolean allow) {
            if (approval != null && approval.id().equals(id)) decision.complete(allow);
        }
        Running(String role, java.util.function.Consumer<String> sessionId) { this.sessionId = sessionId; timeline = new TurnTimeline(role); }
        @Override public void session(String id) { sessionId.accept(id); }
        @Override public void assistant(String id, String text, boolean replace) { timeline.assistant(id, text, replace); }
        @Override public void work(String id, String kind, String label, String text, boolean replace, String state) {
            timeline.work(id, kind, label, text, replace, state);
        }
        @Override public void activity(String description) { if (!cli.stopped()) activity = description; }
        synchronized void stop() {
            activity = "Stopping";
            cli.stop();
            if (decision != null) decision.complete(false);
        }
    }

    public LocalSessions(Project project) {
        this.project = project;
        var workspace = project.getBasePath();
        archive = new SessionArchive(PathManager.getConfigDir(), workspace == null ? project.getLocationHash() : workspace);
        try { archive.read().forEach(t -> recent.add(new Session(t))); }
        catch (IOException e) {
            archiveReadable = false;
            archiveError = "Local session history could not be read. New messages will not overwrite it.";
        }
    }

    List<Session> sessions() { return List.copyOf(recent); }
    String archiveError() { return archiveError; }
    void addListener(Runnable listener) { listeners.add(listener); }
    void removeListener(Runnable listener) { listeners.remove(listener); }

    Session create() {
        var session = new Session(new SessionArchive.Thread(UUID.randomUUID().toString(), "New Chat",
            LocalCli.Provider.CODEX, null, List.of(), null, null));
        recent.addFirst(session);
        save();
        changed();
        return session;
    }

    String select(Session session, ModelPicker.Selection choice) {
        if (session.running != null) return "Wait for the current turn to finish.";
        if (session.cliId != null && session.provider != choice.provider()) return "Start a new chat to switch providers.";
        if (session.provider != choice.provider() || !Objects.equals(session.model, choice.model()) || !Objects.equals(session.effort, choice.effort())) {
            session.provider = choice.provider();
            session.model = choice.model();
            session.effort = choice.effort();
            save();
            changed();
        }
        return "";
    }

    List<ModelPicker.Model> codexModels() { return codexModels; }

    void loadModels() {
        var base = project.getBasePath();
        if (loadingModels || loadedModels || base == null || !TrustedProjects.isTrusted(project)) return;
        loadingModels = true;
        var override = PropertiesComponent.getInstance().getValue(cliKey(LocalCli.Provider.CODEX));
        java.lang.Thread.ofVirtual().start(() -> {
            List<ModelPicker.Model> models = ModelPicker.defaults(LocalCli.Provider.CODEX);
            try {
                var env = EnvironmentUtil.getEnvironmentMap();
                var command = LocalCli.executable(LocalCli.Provider.CODEX, override, env, Path.of(System.getProperty("user.home")));
                if (command != null) models = LocalCli.listCodexModels(command, Path.of(base), env);
            } catch (InterruptedException e) { java.lang.Thread.currentThread().interrupt(); }
            catch (Exception ignored) { }
            var result = models;
            SwingUtilities.invokeLater(() -> {
                if (disposed) return;
                loadingModels = false;
                loadedModels = true;
                codexModels = result;
                changed();
            });
        });
    }

    String start(Session session, String prompt, TurnSettings settings) {
        if (session.running != null || prompt.isBlank()) return "";
        if (!TrustedProjects.isTrusted(project)) return "Trust this IDE project before running a local CLI.";
        var workspace = project.getBasePath();
        if (workspace == null || !Files.isDirectory(Path.of(workspace))) return "Open a local project folder first.";
        if (TurnSettings.FAST.equals(settings.speed()) && !codexModels.stream()
            .anyMatch(model -> Objects.equals(model.id(), session.model) && model.fast())) return "Fast is not available for this model.";
        var previous = session.cliId;
        var effort = session.effort;
        if (effort == null && session.provider == LocalCli.Provider.CODEX && session.model != null) {
            effort = codexModels.stream().filter(model -> session.model.equals(model.id()))
                .map(ModelPicker.Model::defaultEffort).filter(Objects::nonNull).findFirst().orElse(null);
        }
        var selection = new ModelPicker.Selection(session.provider, session.model, effort);
        var provider = selection.provider();
        var override = PropertiesComponent.getInstance().getValue(cliKey(provider));
        if (session.messages.isEmpty()) {
            var title = prompt.strip().lines().findFirst().orElse(session.title);
            session.title = title.codePointCount(0, title.length()) > 54 ? title.substring(0, title.offsetByCodePoints(0, 53)) + "..." : title;
        }
        session.messages.add(new SessionArchive.Message("You", prompt));
        session.draft = "";
        recent.remove(session);
        recent.addFirst(session);
        var run = new Running(provider.label, id -> SwingUtilities.invokeLater(() -> {
            if (!disposed && !Objects.equals(session.cliId, id)) { session.cliId = id; save(); changed(); }
        }));
        session.running = run;
        save();
        java.lang.Thread.ofVirtual().start(() -> {
            String error = null;
            try {
                var env = EnvironmentUtil.getEnvironmentMap();
                var command = LocalCli.executable(provider, override, env, Path.of(System.getProperty("user.home")));
                if (command == null) error = provider.command + " was not found. Install the CLI or set its path from the T3 Code tool window.";
                else run.cli.execute(selection, command, Path.of(workspace), env, previous, settings, prompt, run);
            } catch (IOException | RuntimeException e) {
                error = Objects.toString(e.getMessage(), "Could not run the local CLI.");
            } catch (InterruptedException e) {
                java.lang.Thread.currentThread().interrupt();
                error = "CLI interrupted.";
            }
            var outcome = error;
            SwingUtilities.invokeLater(() -> { if (!disposed) complete(session, outcome); });
        });
        changed();
        return "";
    }

    private void complete(Session session, String error) {
        var run = session.running;
        if (run == null) return;
        var rows = run.timeline.finish(run.cli.stopped() || error != null);
        session.messages.addAll(rows);
        if (run.cli.stopped()) session.messages.add(new SessionArchive.Message("Status", "Stopped"));
        else if (error != null) session.messages.add(new SessionArchive.Message("Error", error));
        else if (rows.stream().noneMatch(row -> row.work() == null)) session.messages.add(new SessionArchive.Message("Status", "The CLI did not return any text."));
        session.running = null;
        save();
        if (!disposed) changed();
    }

    static String cliKey(LocalCli.Provider provider) { return "t3code.local.cli." + provider.command; }

    private void changed() {
        for (var listener : List.copyOf(listeners)) {
            try { listener.run(); }
            catch (RuntimeException e) { LOG.warn("Could not refresh the chat view", e); }
        }
    }
    private void save() {
        if (!archiveReadable) return;
        var snapshot = recent.stream().map(Session::snapshot).toList();
        writer.execute(() -> {
            try { archive.write(snapshot); }
            catch (IOException e) { SwingUtilities.invokeLater(() -> {
                if (!disposed) {
                    archiveError = "Could not save local session history.";
                    changed();
                }
            }); }
        });
    }

    @Override public void dispose() {
        disposed = true;
        recent.stream().filter(session -> session.running != null).forEach(session -> {
            session.running.stop();
            complete(session, null);
        });
        listeners.clear();
        writer.shutdown();
    }
}
