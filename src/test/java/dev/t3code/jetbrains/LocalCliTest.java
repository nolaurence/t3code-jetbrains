package dev.t3code.jetbrains;

import com.google.gson.JsonParser;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class LocalCliTest {
    public static void main(String[] args) throws Exception {
        var workspace = Files.createTempDirectory("t3-jetbrains-local-test-");
        try {
            codexFirstMessageStartsATurn(workspace);
            codexResumeStreamsWithoutReplay(workspace);
            claudeStreamsWithoutRepeatingFinalMessage();
            unexpectedEofIsNotSuccess();
            archiveSurvivesRestart(workspace);
            catalogAndClaudeArguments();
        } finally {
            try (var files = Files.walk(workspace)) {
                for (var path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("CLI streaming, resume, failure and local history checks passed");
    }


    private static void codexFirstMessageStartsATurn(Path workspace) throws Exception {
        var executable = Files.writeString(workspace.resolve("codex-fixture"), """
            #!/bin/sh
            set -eu
            IFS= read -r request
            printf '%s\\n' '{"id":1,"result":{}}'
            IFS= read -r notification
            IFS= read -r request
            case "$request" in *'"method":"thread/start"'*) ;; *) exit 1;; esac
            printf '%s\\n' '{"id":2,"result":{"thread":{"id":"fresh"}}}'
            IFS= read -r request
            case "$request" in *'"method":"turn/start"'*) ;; *) exit 1;; esac
            case "$request" in *'"text":"First message"'*) ;; *) exit 1;; esac
            printf '%s\\n' \
              '{"id":3,"result":{"turn":{"id":"first-turn"}}}' \
              '{"method":"item/agentMessage/delta","params":{"threadId":"fresh","itemId":"response","delta":"Hello"}}' \
              '{"method":"turn/completed","params":{"threadId":"fresh","turn":{"status":"completed"}}}'
            """);
        expect(executable.toFile().setExecutable(true), true);
        var output = new Captured();
        new LocalCli.Run().execute(ModelPicker.Selection.read("CODEX", null, null), executable, workspace,
            Map.of(), null, TurnSettings.read(LocalCli.Provider.CODEX, null, null), "First message", output);
        expect(output.session, "fresh");
        expect(output.activities, List.of("Waiting for response...", "Responding"));
        expect(output.timeline.finish(false).stream().map(SessionArchive.Message::text).toList(), List.of("Hello"));
    }

    private static void codexResumeStreamsWithoutReplay(Path workspace) throws Exception {
        var requests = new StringWriter();
        var output = new Captured();
        LocalCli.codex(reader("""
            {"id":1,"result":{}}
            {"id":2,"result":{"thread":{"id":"codex-fixture"}}}
            {"id":3,"result":{"turn":{"id":"turn-1"}}}
            {"method":"item/agentMessage/delta","params":{"threadId":"other-thread","itemId":"a","delta":"wrong"}}
            {"method":"item/agentMessage/delta","params":{"threadId":"codex-fixture","itemId":"a","delta":"Hello"}}
            {"method":"item/agentMessage/delta","params":{"threadId":"codex-fixture","itemId":"a","delta":" world"}}
            {"method":"item/completed","params":{"threadId":"codex-fixture","item":{"id":"a","type":"agentMessage","text":"Hello world"}}}
            {"id":"allow-command","method":"item/commandExecution/requestApproval","params":{"threadId":"codex-fixture","command":"rg --files"}}
            {"method":"item/started","params":{"threadId":"codex-fixture","item":{"id":"cmd","type":"commandExecution","command":"rg --files"}}}
            {"method":"item/commandExecution/outputDelta","params":{"threadId":"codex-fixture","itemId":"cmd","delta":"src/main"}}
            {"method":"item/completed","params":{"threadId":"codex-fixture","item":{"id":"cmd","type":"commandExecution","command":"rg --files","exitCode":1}}}
            {"method":"item/completed","params":{"threadId":"codex-fixture","item":{"id":"b","type":"agentMessage","text":"Done"}}}
            {"method":"turn/completed","params":{"threadId":"codex-fixture","turn":{"status":"completed"}}}
            """), new BufferedWriter(requests), workspace, "codex-fixture", TurnSettings.read(LocalCli.Provider.CODEX, "auto-accept-edits", "fast"), "Continue\nfrom here",
                ModelPicker.Selection.read("CODEX", "fixture-model", "high"), output);
        expect(output.session, "codex-fixture");
        var rows = output.timeline.finish(false);
        expect(rows.stream().map(SessionArchive.Message::text).toList(), List.of("Hello world", "src/main", "Done"));
        expect(rows.get(1).work(), new SessionArchive.Work("command", "rg --files", "failed"));
        expect(output.timeline.after(0).size(), 6);
        var messages = requests.toString().lines().map(l -> JsonParser.parseString(l).getAsJsonObject()).toList();
        expect(messages.get(0).get("method").getAsString(), "initialize");
        expect(messages.get(2).get("method").getAsString(), "thread/resume");
        var config = messages.get(2).getAsJsonObject("params");
        expect(config.get("threadId").getAsString(), "codex-fixture");
        expect(config.get("approvalPolicy").getAsString(), "on-request");
        expect(config.get("sandbox").getAsString(), "workspace-write");
        expect(config.get("model").getAsString(), "fixture-model");
        var turn = messages.get(3).getAsJsonObject("params");
        expect(turn.get("model").getAsString(), "fixture-model");
        expect(turn.get("effort").getAsString(), "high");
        expect(turn.get("serviceTier").getAsString(), "fast");
        expect(turn.getAsJsonObject("sandboxPolicy").get("type").getAsString(), "workspaceWrite");
        var approval = messages.stream().filter(m -> m.has("id") && m.get("id").getAsString().equals("allow-command")).findFirst().orElseThrow();
        expect(approval.getAsJsonObject("result").get("decision").getAsString(), "accept");
        var prompt = turn.getAsJsonArray("input").get(0).getAsJsonObject();
        expect(prompt.get("text").getAsString(), "Continue\nfrom here");
    }

    private static void claudeStreamsWithoutRepeatingFinalMessage() throws Exception {
        var output = new Captured();
        LocalCli.claude(reader("""
            {"type":"system","subtype":"init","session_id":"claude-fixture"}
            {"type":"stream_event","event":{"type":"message_start"}}
            {"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"Checking"}}}
            {"type":"assistant","message":{"content":[{"type":"text","text":"Checking"}]}}
            {"type":"stream_event","event":{"type":"content_block_start","content_block":{"type":"tool_use","id":"tool1","name":"Read"}}}
            {"type":"assistant","message":{"content":[{"type":"tool_use","id":"tool1","name":"Read","input":{"file_path":"README.md"}}]}}
            {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"tool1","content":"contents"}]}}
            {"type":"assistant","message":{"content":[{"type":"text","text":"Found it"}]}}
            {"type":"result","subtype":"success","result":"Found it"}
            """), output);
        expect(output.session, "claude-fixture");
        var rows = output.timeline.finish(false);
        expect(rows.stream().map(SessionArchive.Message::text).toList(), List.of("Checking", "contents", "Found it"));
        expect(rows.get(1).work(), new SessionArchive.Work("explore", "Read README.md", "completed"));
    }

    private static void unexpectedEofIsNotSuccess() throws Exception {
        try {
            LocalCli.claude(reader("{\"type\":\"system\",\"subtype\":\"init\"}"), new Captured());
            throw new AssertionError("Unexpected EOF must fail the turn");
        } catch (IOException e) {
            if (!e.getMessage().contains("before completing")) throw e;
        }
    }

    private static void archiveSurvivesRestart(Path workspace) throws Exception {
        var threads = List.of(new SessionArchive.Thread("local-fixture", "Example", LocalCli.Provider.CODEX,
            "codex-fixture", List.of(new SessionArchive.Message("You", "line one\nline two"),
                new SessionArchive.Message("Work", "src/index.ts", "row-a", new SessionArchive.Work("command", "find src", "completed"))), "fixture-model", "high"));
        var first = new SessionArchive(workspace, "/workspace/one");
        expect(first.read(), List.of());
        first.write(threads);
        expect(new SessionArchive(workspace, "/workspace/one").read(), threads);
        expect(new SessionArchive(workspace, "/workspace/two").read(), List.of());
        first.write(List.of());
        expect(new SessionArchive(workspace, "/workspace/one").read(), List.of());
    }

    private static void catalogAndClaudeArguments() {
        var response = JsonParser.parseString("""
            {"data":[{"model":"hidden-model","hidden":true},{"id":"slug","model":"actual-model","displayName":"Model A",
                      "serviceTiers":[{"id":"fast"}],"supportedReasoningEfforts":[{"reasoningEffort":"high"},{"reasoningEffort":"max"}],"defaultReasoningEffort":"high"}]}
            """).getAsJsonObject();
        expect(ModelPicker.codexModels(response).stream().skip(1).toList(),
            List.of(new ModelPicker.Model("actual-model", "Model A", List.of("high", "max"), "high", true)));
        expect(LocalCli.claudeModelArgs(ModelPicker.Selection.read("CLAUDE", "opus", "max")),
            List.of("--model", "opus", "--effort", "max"));
    }

    private static BufferedReader reader(String text) { return new BufferedReader(new StringReader(text)); }
    private static void expect(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + " but got " + actual);
    }
    private static final class Captured implements LocalCli.Output {
        String session;
        final List<String> activities = new ArrayList<>();
        final TurnTimeline timeline = new TurnTimeline("Agent");
        @Override public void session(String id) { session = id; }
        @Override public void assistant(String id, String text, boolean replace) { timeline.assistant(id, text, replace); }
        @Override public void work(String id, String kind, String label, String text, boolean replace, String state) {
            timeline.work(id, kind, label, text, replace, state);
        }
        @Override public void activity(String description) { activities.add(description); }
        @Override public boolean approve(String action, String detail) { return "rg --files".equals(detail); }
    }
}
