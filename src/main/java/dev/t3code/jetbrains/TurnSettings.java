package dev.t3code.jetbrains;

import java.util.Map;

record TurnSettings(String mode, String speed) {
    static final String STANDARD = "default";
    static final String FAST = "fast";

    static TurnSettings read(LocalCli.Provider provider, String mode, String speed) {
        var resolved = mode == null ? (provider == LocalCli.Provider.CODEX ? "approval-required" : "read-only") : mode;
        var tier = speed == null ? STANDARD : speed;
        if (!STANDARD.equals(tier) && !(FAST.equals(tier) && provider == LocalCli.Provider.CODEX)) throw new IllegalArgumentException();
        if (provider == LocalCli.Provider.CLAUDE && !("read-only".equals(resolved) || "auto-accept-edits".equals(resolved))) {
            throw new IllegalArgumentException();
        }
        var settings = new TurnSettings(resolved, tier);
        if (provider == LocalCli.Provider.CODEX) settings.codexPolicy();
        return settings;
    }

    boolean claudeEdits() { return "auto-accept-edits".equals(mode); }

    record Policy(String approval, String reviewer, String sandbox, String turnSandbox) {
        void applyThread(Map<String, Object> params) {
            params.put("approvalPolicy", approval);
            params.put("approvalsReviewer", reviewer);
            params.put("sandbox", sandbox);
        }
        void applyTurn(Map<String, Object> params, String speed) {
            params.put("approvalPolicy", approval);
            params.put("approvalsReviewer", reviewer);
            params.put("sandboxPolicy", Map.of("type", turnSandbox));
            params.put("serviceTier", speed);
        }
    }

    Policy codexPolicy() {
        return switch (mode) {
            case "approval-required" -> new Policy("untrusted", "user", "read-only", "readOnly");
            case "auto-accept-edits" -> new Policy("on-request", "user", "workspace-write", "workspaceWrite");
            case "auto" -> new Policy("on-request", "auto_review", "workspace-write", "workspaceWrite");
            case "full-access" -> new Policy("never", "user", "danger-full-access", "dangerFullAccess");
            default -> throw new IllegalArgumentException();
        };
    }
}
