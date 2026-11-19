package dev.t3code.jetbrains;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

final class ModelPicker {
    private static final Pattern MODEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,179}");
    private static final Pattern EFFORT = Pattern.compile("[a-z][a-z0-9_-]{0,31}");
    private static final List<String> CLAUDE_EFFORTS = List.of("low", "medium", "high", "xhigh", "max");

    record Selection(LocalCli.Provider provider, String model, String effort) {
        static Selection read(String provider, String model, String effort) {
            var selected = LocalCli.Provider.valueOf(Objects.requireNonNull(provider));
            if (model != null && !MODEL.matcher(model).matches()) throw new IllegalArgumentException();
            if (effort != null && (!EFFORT.matcher(effort).matches() ||
                selected == LocalCli.Provider.CLAUDE && !CLAUDE_EFFORTS.contains(effort))) throw new IllegalArgumentException();
            return new Selection(selected, model, effort);
        }
    }

    record Model(String id, String label, List<String> efforts, String defaultEffort, boolean fast) {
        Model(String id, String label, List<String> efforts, String defaultEffort) { this(id, label, efforts, defaultEffort, false); }
    }

    static List<Model> defaults(LocalCli.Provider provider) {
        var automatic = new Model(null, "CLI default", List.of(), null);
        if (provider == LocalCli.Provider.CODEX) return List.of(automatic);
        return List.of(automatic, new Model("sonnet", "Claude Sonnet", List.of("low", "medium", "high"), null),
            new Model("opus", "Claude Opus", CLAUDE_EFFORTS, null),
            new Model("haiku", "Claude Haiku", List.of(), null));
    }

    static List<Model> codexModels(JsonObject result) {
        var choices = new ArrayList<>(defaults(LocalCli.Provider.CODEX));
        var data = result == null ? null : result.get("data");
        if (data == null || !data.isJsonArray()) return choices;
        for (var element : data.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            var row = element.getAsJsonObject();
            var id = LocalCli.str(row, "model");
            if (id == null || !MODEL.matcher(id).matches() || "true".equals(LocalCli.str(row, "hidden")) ||
                choices.stream().anyMatch(m -> id.equals(m.id()))) continue;
            var efforts = new ArrayList<String>();
            var supported = row.get("supportedReasoningEfforts");
            if (supported != null && supported.isJsonArray()) for (var item : supported.getAsJsonArray()) {
                var effort = item.isJsonObject() ? LocalCli.str(item.getAsJsonObject(), "reasoningEffort") : null;
                if (effort != null && EFFORT.matcher(effort).matches() && !efforts.contains(effort)) efforts.add(effort);
            }
            var tier = row.get("serviceTiers");
            var legacy = row.get("additionalSpeedTiers");
            var supportsFast = tier != null && tier.isJsonArray() && !tier.getAsJsonArray().isEmpty()
                ? tier.getAsJsonArray().asList().stream().anyMatch(t -> t.isJsonObject() && "fast".equals(LocalCli.str(t.getAsJsonObject(), "id")))
                : legacy != null && legacy.isJsonArray() && legacy.getAsJsonArray().asList().stream()
                    .anyMatch(t -> t.isJsonPrimitive() && "fast".equals(t.getAsString()));
            if (supportsFast && "true".equals(LocalCli.str(row, "isDefault"))) choices.set(0, new Model(null, "CLI default", List.of(), null, true));
            choices.add(new Model(id, Objects.toString(LocalCli.str(row, "displayName"), id), efforts,
                LocalCli.str(row, "defaultReasoningEffort"), supportsFast));
        }
        return choices;
    }
}
