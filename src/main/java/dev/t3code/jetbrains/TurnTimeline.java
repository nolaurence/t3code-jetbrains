package dev.t3code.jetbrains;

import java.util.*;

final class TurnTimeline {
    record Patch(String id, String role, String text, boolean replace, SessionArchive.Work work) {}
    private static final int MAX_TOOL_TEXT = 8192;
    private final String prefix = UUID.randomUUID() + ":";
    private final String role;
    private final Map<String, Row> rows = new LinkedHashMap<>();
    private final List<Patch> patches = new ArrayList<>();

    TurnTimeline(String role) { this.role = role; }

    private static final class Row {
        final StringBuilder text = new StringBuilder();
        SessionArchive.Work work;
    }

    synchronized void assistant(String id, String text, boolean replace) {
        if (id == null || text == null || text.isEmpty()) return;
        update(id, role, text, replace, null);
    }

    synchronized void work(String id, String kind, String label, String text, boolean replace, String state) {
        if (id == null) return;
        var row = rows.get(id);
        var previous = row == null ? null : row.work;
        var work = new SessionArchive.Work(kind != null ? kind : previous == null ? "tool" : previous.kind(),
            label != null ? label : previous == null ? "Tool call" : previous.label(),
            state != null ? state : previous == null ? "running" : previous.state());
        if (text != null) {
            int room = MAX_TOOL_TEXT - (replace || row == null ? 0 : row.text.length());
            if (text.length() > room) text = text.substring(0, Math.max(0, room));
        }
        update(id, "Work", text, replace, work);
    }

    private void update(String id, String role, String text, boolean replace, SessionArchive.Work work) {
        var row = rows.computeIfAbsent(id, ignored -> new Row());
        boolean changed = text != null && (replace ? !row.text.toString().equals(text) : !text.isEmpty());
        if (!changed && Objects.equals(row.work, work)) return;
        if (changed) {
            if (replace) row.text.setLength(0);
            row.text.append(text);
        }
        row.work = work;
        patches.add(new Patch(prefix + id, role, changed ? text : null, replace && changed, work));
    }

    synchronized List<Patch> after(int offset) { return List.copyOf(patches.subList(offset, patches.size())); }

    synchronized List<SessionArchive.Message> finish(boolean interrupted) {
        return rows.entrySet().stream().filter(entry -> !entry.getValue().text.isEmpty() || entry.getValue().work != null)
            .map(entry -> {
                var row = entry.getValue();
                var work = row.work;
                if (work != null && "running".equals(work.state())) {
                    work = new SessionArchive.Work(work.kind(), work.label(), interrupted ? "interrupted" : "completed");
                }
                return new SessionArchive.Message(work == null ? role : "Work", row.text.toString(), prefix + entry.getKey(), work);
            }).toList();
    }
}
