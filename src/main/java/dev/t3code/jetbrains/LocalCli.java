package dev.t3code.jetbrains;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.FutureTask;

final class LocalCli {
    enum Provider {
        CODEX("Codex", "codex"), CLAUDE("Claude Code", "claude");
        final String label;
        final String command;
        Provider(String label, String command) { this.label = label; this.command = command; }
    }

    interface Output {
        void session(String id);
        void assistant(String id, String text, boolean replace);
        void work(String id, String kind, String label, String text, boolean replace, String state);
        void activity(String description);
        default boolean approve(String action, String detail) { return false; }
    }

    static final class Run {
        private Process process;
        private volatile boolean stopped;

        synchronized void stop() {
            stopped = true;
            if (process != null) terminate(process);
        }

        boolean stopped() { return stopped; }

        void execute(ModelPicker.Selection selection, Path executable, Path workspace, Map<String, String> environment,
                     String session, TurnSettings settings, String prompt, Output output) throws IOException, InterruptedException {
            var provider = selection.provider();
            List<String> args = new ArrayList<>();
            args.add(executable.toString());
            if (provider == Provider.CODEX) {
                args.addAll(List.of("app-server", "--listen", "stdio://"));
            } else {
                args.addAll(List.of("--print", "--verbose", "--output-format", "stream-json", "--include-partial-messages",
                    "--disable-slash-commands", "--strict-mcp-config", "--mcp-config", "{\"mcpServers\":{}}",
                    "--settings", "{\"disableAllHooks\":true}", "--permission-mode", settings.claudeEdits() ? "acceptEdits" : "dontAsk",
                    "--tools", settings.claudeEdits() ? "Read,Grep,Glob,Edit,Write" : "Read,Grep,Glob"));
                args.addAll(claudeModelArgs(selection));
                if (session != null) args.addAll(List.of("--resume", session));
            }
            var builder = new ProcessBuilder(args).directory(workspace.toFile());
            builder.environment().putAll(environment);
            if (provider == Provider.CLAUDE) {
                builder.environment().remove("CLAUDECODE");
                builder.environment().remove("CLAUDE_CODE_ENTRYPOINT");
            }
            final Process child;
            synchronized (this) {
                if (stopped) return;
                child = process = builder.start();
            }
            var stderr = new StringBuilder();
            var errors = Thread.ofVirtual().start(() -> {
                try (var reader = new InputStreamReader(child.getErrorStream(), StandardCharsets.UTF_8)) {
                    char[] data = new char[512];
                    for (int n; (n = reader.read(data)) != -1;) {
                        synchronized (stderr) {
                            stderr.append(data, 0, n);
                            if (stderr.length() > 4000) stderr.delete(0, stderr.length() - 4000);
                        }
                    }
                } catch (IOException ignored) {}
            });
            try (var input = child.inputReader(StandardCharsets.UTF_8);
                 var outputPipe = child.outputWriter(StandardCharsets.UTF_8)) {
                if (provider == Provider.CODEX) {
                    codex(input, outputPipe, workspace, session, settings, prompt, selection, output);
                } else {
                    outputPipe.write(prompt);
                    outputPipe.close();
                    claude(input, output);
                }
            } catch (IOException e) {
                if (stopped) return;
                synchronized (stderr) {
                    var detail = stderr.toString().strip();
                    if (!detail.isEmpty() && e instanceof ProtocolClosed) {
                        throw new IOException(detail.substring(Math.max(0, detail.length() - 1200)));
                    }
                }
                throw e;
            } finally {
                try {
                    if (!child.waitFor(1500, TimeUnit.MILLISECONDS)) terminate(child);
                    errors.join(500);
                } finally {
                    if (child.isAlive()) terminate(child);
                    synchronized (this) { process = null; }
                }
            }
        }
    }

    static Path executable(Provider provider, String override, Map<String, String> environment, Path home) {
        if (override != null && !override.isBlank()) {
            var path = Path.of(override);
            return path.isAbsolute() && usable(path) ? path : null;
        }
        var dirs = new ArrayList<String>();
        dirs.addAll(Arrays.asList(environment.getOrDefault("PATH", "").split(java.util.regex.Pattern.quote(File.pathSeparator))));
        dirs.addAll(List.of(home.resolve(".local/bin").toString(), home.resolve("bin").toString(), "/opt/homebrew/bin", "/usr/local/bin"));
        boolean windows = File.separatorChar == '\\';
        var command = provider.command + (windows ? ".exe" : "");
        for (var dir : dirs) {
            if (dir.isBlank()) continue;
            var base = Path.of(dir);
            if (!base.isAbsolute()) continue;
            var path = base.resolve(command);
            if (usable(path)) return path;
        }
        return null;
    }

    private static boolean usable(Path path) { return Files.isRegularFile(path) && Files.isExecutable(path); }

    private static void terminate(Process process) {
        process.descendants().forEach(handle -> handle.destroyForcibly());
        process.destroyForcibly();
    }

    static List<String> claudeModelArgs(ModelPicker.Selection selection) {
        var args = new ArrayList<String>();
        if (selection.model() != null) args.addAll(List.of("--model", selection.model()));
        if (selection.effort() != null) args.addAll(List.of("--effort", selection.effort()));
        return args;
    }

    private static void initialize(BufferedWriter pipe) throws IOException {
        send(pipe, Map.of("id", 1, "method", "initialize", "params", Map.of("clientInfo",
            Map.of("name", "t3code_jetbrains", "title", "T3 Code for JetBrains", "version", "0.6.0"))));
    }

    static List<ModelPicker.Model> listCodexModels(Path executable, Path workspace, Map<String, String> environment) throws Exception {
        var builder = new ProcessBuilder(executable.toString(), "app-server", "--listen", "stdio://")
            .directory(workspace.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().putAll(environment);
        var process = builder.start();
        var task = new FutureTask<>(() -> {
            try (var input = process.inputReader(StandardCharsets.UTF_8); var pipe = process.outputWriter(StandardCharsets.UTF_8)) {
                initialize(pipe);
                for (String line; (line = input.readLine()) != null;) {
                    var event = parse(line);
                    var id = str(event, "id");
                    if ("1".equals(id) && obj(event, "result") != null) {
                        send(pipe, Map.of("method", "initialized"));
                        send(pipe, Map.of("id", 2, "method", "model/list", "params", Map.of("limit", 100, "includeHidden", false)));
                    } else if ("2".equals(id)) return ModelPicker.codexModels(obj(event, "result"));
                }
                return ModelPicker.defaults(Provider.CODEX);
            }
        });
        Thread.ofVirtual().start(task);
        try { return task.get(8, TimeUnit.SECONDS); }
        finally { task.cancel(true); if (process.isAlive()) terminate(process); }
    }

    static void codex(BufferedReader input, BufferedWriter pipe, Path cwd, String session, TurnSettings turnSettings,
                      String prompt, ModelPicker.Selection selection, Output output) throws IOException {
        initialize(pipe);
        var policy = turnSettings.codexPolicy();
        String thread = null;
        for (String line; (line = input.readLine()) != null;) {
            var event = parse(line);
            if (event == null) continue;
            var method = str(event, "method");
            var params = obj(event, "params");
            if (method == null && event.has("id")) {
                var id = str(event, "id");
                var error = obj(event, "error");
                if (error != null) throw failure(str(error, "message"));
                if ("1".equals(id)) {
                    send(pipe, Map.of("method", "initialized"));
                    var settings = new HashMap<String, Object>(Map.of("cwd", cwd.toString()));
                    policy.applyThread(settings);
                    if (selection.model() != null) settings.put("model", selection.model());
                    if (session != null) {
                        settings.put("threadId", session);
                        settings.put("excludeTurns", true);
                    }
                    send(pipe, Map.of("id", 2, "method", session == null ? "thread/start" : "thread/resume", "params", settings));
                } else if ("2".equals(id)) {
                    thread = str(obj(obj(event, "result"), "thread"), "id");
                    if (thread == null || thread.isBlank()) throw failure("Codex did not return a session ID.");
                    output.session(thread);
                    var paramsForTurn = new HashMap<String, Object>(Map.of("threadId", thread,
                        "input", List.of(Map.of("type", "text", "text", prompt, "text_elements", List.of()))));
                    policy.applyTurn(paramsForTurn, turnSettings.speed());
                    if (selection.model() != null) paramsForTurn.put("model", selection.model());
                    if (selection.effort() != null) paramsForTurn.put("effort", selection.effort());
                    send(pipe, Map.of("id", 3, "method", "turn/start", "params", paramsForTurn));
                } else if ("3".equals(id) && obj(event, "result") != null) {
                    output.activity("Waiting for response...");
                }
                continue;
            }
            if (method == null) continue;
            if (event.has("id")) {
                if (thread != null && thread.equals(str(params, "threadId")) &&
                    ("item/commandExecution/requestApproval".equals(method) || "item/fileChange/requestApproval".equals(method))) {
                    var command = str(params, "command");
                    var reason = str(params, "reason");
                    var action = method.startsWith("item/command") ? "Approve command" : "Approve file changes";
                    var accepted = output.approve(action, String.join("\n", Objects.toString(command, ""), Objects.toString(reason, "")).strip());
                    send(pipe, Map.of("id", event.get("id"), "result", Map.of("decision", accepted ? "accept" : "decline")));
                } else if ("item/permissions/requestApproval".equals(method)) {
                    send(pipe, Map.of("id", event.get("id"), "result", Map.of("permissions", Map.of(), "scope", "turn")));
                } else {
                    send(pipe, Map.of("id", event.get("id"), "error", Map.of("code", -32601,
                        "message", "This request is not supported by this client.")));
                }
                continue;
            }
            if (thread == null || !thread.equals(str(params, "threadId"))) continue;
            switch (method) {
                case "item/agentMessage/delta" -> {
                    output.assistant(str(params, "itemId"), str(params, "delta"), false);
                    output.activity("Responding");
                }
                case "item/commandExecution/outputDelta", "item/fileChange/outputDelta" ->
                    output.work(str(params, "itemId"), null, null, str(params, "delta"), false, null);
                case "item/reasoning/summaryTextDelta" ->
                    output.work(str(params, "itemId"), "reasoning", "Thinking", str(params, "delta"), false, null);
                case "item/plan/delta" ->
                    output.work(str(params, "itemId"), "plan", "Plan", str(params, "delta"), false, null);
                case "item/started", "item/completed" ->
                    codexItem(obj(params, "item"), "item/completed".equals(method), output);
                case "turn/completed" -> {
                    var turn = obj(params, "turn");
                    if (!"completed".equals(str(turn, "status"))) {
                        var detail = str(obj(turn, "error"), "message");
                        throw failure(detail == null ? "Codex turn did not complete." : detail);
                    }
                    return;
                }
            }
        }
        throw new ProtocolClosed();
    }

    private static void codexItem(JsonObject item, boolean completed, Output output) {
        var id = str(item, "id");
        var state = !completed ? "running" : Set.of("failed", "declined").contains(Objects.toString(str(item, "status"), ""))
            ? "failed" : "completed";
        switch (Objects.toString(str(item, "type"), "")) {
            case "agentMessage" -> { if (completed && str(item, "text") != null) output.assistant(id, str(item, "text"), true); }
            case "reasoning" -> {
                var summary = stringList(item, "summary");
                output.work(id, "reasoning", completed ? "Reasoning" : "Thinking", summary, true, state);
            }
            case "plan" -> output.work(id, "plan", "Plan", str(item, "text"), true, state);
            case "commandExecution" -> {
                var kind = "command";
                var label = str(item, "command");
                var actions = item.get("commandActions");
                if (actions != null && actions.isJsonArray() && !actions.getAsJsonArray().isEmpty()) {
                    boolean exploring = true;
                    for (var action : actions.getAsJsonArray()) {
                        if (!action.isJsonObject() || !Set.of("read", "listFiles", "search").contains(
                            Objects.toString(str(action.getAsJsonObject(), "type"), ""))) { exploring = false; break; }
                    }
                    if (exploring) kind = "explore";
                }
                var exit = str(item, "exitCode");
                if (completed && exit != null && !"0".equals(exit)) state = "failed";
                output.work(id, kind, label, completed ? str(item, "aggregatedOutput") : null, true, state);
            }
            case "fileChange" -> {
                var paths = new ArrayList<String>();
                var changes = item.get("changes");
                if (changes != null && changes.isJsonArray()) for (var change : changes.getAsJsonArray()) {
                    if (change.isJsonObject() && str(change.getAsJsonObject(), "path") != null) paths.add(str(change.getAsJsonObject(), "path"));
                }
                output.work(id, "edit", paths.isEmpty() ? "File changes" : String.join(", ", paths), null, false, state);
            }
            case "webSearch" -> output.work(id, "explore", str(item, "query"), null, false, state);
            case "mcpToolCall", "dynamicToolCall", "collabAgentToolCall" ->
                output.work(id, "tool", str(item, "tool"), null, false, state);
            default -> { }
        }
    }

    private static String stringList(JsonObject item, String key) {
        var parts = new ArrayList<String>();
        var array = item.get(key);
        if (array != null && array.isJsonArray()) for (var part : array.getAsJsonArray()) {
            if (part.isJsonPrimitive() && part.getAsJsonPrimitive().isString() && !part.getAsString().isBlank()) parts.add(part.getAsString());
        }
        return parts.isEmpty() ? null : String.join("\n\n", parts);
    }

    static void claude(BufferedReader input, Output output) throws IOException {
        int sequence = 0;
        String current = "fallback-0";
        var started = new HashMap<String, Map<Integer, String>>();
        var streamedText = new HashMap<String, StringBuilder>();
        var previousEnvelopes = new HashMap<String, Set<String>>();
        for (String line; (line = input.readLine()) != null;) {
            var e = parse(line);
            if (e == null) continue;
            var session = str(e, "session_id");
            if (session != null && !session.isBlank() && "system".equals(str(e, "type")) && "init".equals(str(e, "subtype"))) {
                output.session(session);
                output.activity("Waiting for response...");
            }
            switch (Objects.toString(str(e, "type"), "")) {
                case "stream_event" -> {
                    var event = obj(e, "event");
                    var kind = Objects.toString(str(event, "type"), "");
                    if ("message_start".equals(kind)) {
                        var id = str(obj(event, "message"), "id");
                        current = id != null ? id : "fallback-" + (++sequence);
                    }
                    var index = blockIndex(event);
                    var block = obj(event, "content_block");
                    if ("content_block_start".equals(kind) && "tool_use".equals(str(block, "type"))) claudeTool(block, output);
                    var delta = obj(event, "delta");
                    if ("content_block_delta".equals(kind) && "text_delta".equals(str(delta, "type"))) {
                        var text = str(delta, "text");
                        if (text == null || text.isEmpty()) continue;
                        var id = current + ":text:" + index;
                        started.computeIfAbsent(current, ignored -> new LinkedHashMap<>()).put(index, id);
                        streamedText.computeIfAbsent(id, ignored -> new StringBuilder()).append(text);
                        output.assistant(id, text, false);
                        output.activity("Responding");
                    }
                }
                case "assistant" -> {
                    var message = obj(e, "message");
                    var content = message == null ? null : message.get("content");
                    if (content == null || !content.isJsonArray()) continue;
                    var id = Objects.toString(str(message, "id"), current);
                    var known = started.getOrDefault(id, Map.of());
                    var emitted = previousEnvelopes.computeIfAbsent(id, ignored -> new HashSet<>());
                    for (int i = 0; i < content.getAsJsonArray().size(); i++) {
                        var part = content.getAsJsonArray().get(i);
                        if (!part.isJsonObject()) continue;
                        var block = part.getAsJsonObject();
                        if ("tool_use".equals(str(block, "type"))) { claudeTool(block, output); continue; }
                        var text = str(block, "text");
                        if (!"text".equals(str(block, "type")) || text == null || text.isEmpty()) continue;
                        var row = known.values().stream().filter(key -> text.contentEquals(streamedText.get(key))).findFirst()
                            .orElse(content.getAsJsonArray().size() > 1 ? known.get(i) : null);
                        if (row == null) {
                            if (emitted.contains(text)) continue;
                            row = id + ":envelope:" + emitted.size();
                        }
                        output.assistant(row, text, true);
                        emitted.add(text);
                    }
                    if (str(message, "id") == null) current = "fallback-" + (++sequence);
                }
                case "user" -> {
                    var message = obj(e, "message");
                    var content = message == null ? null : message.get("content");
                    if (content != null && content.isJsonArray()) for (var part : content.getAsJsonArray()) {
                        if (!part.isJsonObject()) continue;
                        var block = part.getAsJsonObject();
                        if (!"tool_result".equals(str(block, "type"))) continue;
                        var id = str(block, "tool_use_id");
                        if (id != null) {
                            var details = claudeToolResult(block.get("content"));
                            output.work("tool:" + id, null, null, details, true,
                                "true".equals(str(block, "is_error")) ? "failed" : "completed");
                        }
                    }
                }
                case "result" -> {
                    var result = str(e, "result");
                    if (!"success".equals(str(e, "subtype")) || "true".equals(str(e, "is_error"))) {
                        var errors = e.get("errors");
                        throw failure(result != null ? result : errors != null ? errors.toString() : "Claude Code turn failed.");
                    }
                    if (streamedText.isEmpty() && previousEnvelopes.values().stream().allMatch(Set::isEmpty)) {
                        output.assistant("claude-result", result, true);
                    }
                    return;
                }
            }
        }
        throw new ProtocolClosed();
    }

    private static void claudeTool(JsonObject block, Output output) {
        var id = str(block, "id");
        if (id == null) return;
        var name = Objects.toString(str(block, "name"), "Tool call");
        var input = obj(block, "input");
        var hint = switch (name) {
            case "Read", "Edit", "Write" -> str(input, "file_path");
            case "Glob", "Grep" -> str(input, "pattern");
            case "Bash" -> str(input, "command");
            default -> null;
        };
        var kind = switch (name) {
            case "Read", "Glob", "Grep" -> "explore";
            case "Edit", "Write" -> "edit";
            case "Bash" -> "command";
            default -> "tool";
        };
        output.work("tool:" + id, kind, hint == null ? name : name + " " + hint, null, false, null);
    }

    private static String claudeToolResult(JsonElement content) {
        if (content == null || content.isJsonNull()) return null;
        if (content.isJsonPrimitive()) return content.getAsString();
        if (!content.isJsonArray()) return null;
        var texts = new ArrayList<String>();
        for (var part : content.getAsJsonArray()) {
            if (!part.isJsonObject()) continue;
            var text = str(part.getAsJsonObject(), "text");
            if (text != null) texts.add(text);
        }
        return texts.isEmpty() ? null : String.join("\n", texts);
    }

    private static int blockIndex(JsonObject event) {
        try { return Integer.parseInt(str(event, "index")); }
        catch (NumberFormatException e) { return 0; }
    }

    private static final class ProtocolClosed extends IOException {
        ProtocolClosed() { super("The CLI exited before completing the turn. Check that it is up to date and logged in."); }
    }

    private static IOException failure(String message) {
        if (message == null || message.isBlank()) return new IOException("The CLI reported an error.");
        return new IOException(message.substring(0, Math.min(message.length(), 1200)));
    }

    private static final Gson JSON = new Gson();
    private static void send(BufferedWriter pipe, Map<String, ?> message) throws IOException {
        pipe.write(JSON.toJson(message));
        pipe.newLine();
        pipe.flush();
    }
    private static JsonObject parse(String line) {
        try {
            var data = JsonParser.parseString(line);
            return data.isJsonObject() ? data.getAsJsonObject() : null;
        } catch (RuntimeException ignored) { return null; }
    }
    private static JsonObject obj(JsonObject object, String key) {
        JsonElement data = object == null ? null : object.get(key);
        return data != null && data.isJsonObject() ? data.getAsJsonObject() : null;
    }
    static String str(JsonObject object, String key) {
        JsonElement data = object == null ? null : object.get(key);
        return data != null && data.isJsonPrimitive() ? data.getAsString() : null;
    }
}
