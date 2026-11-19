package dev.t3code.jetbrains;

import java.nio.file.Path;
import java.util.Map;

public final class IdeFileOpenerTest {
    public static void main(String[] args) {
        var path = Path.of(System.getProperty("java.io.tmpdir"), "repo", "test file.txt").toAbsolutePath();
        var target = IdeFileOpener.Target.parse(EmbeddedRuntime.JSON.toJson(Map.of(
            "path", path.toString(), "line", 3, "column", 4)));
        if (!target.path().equals(path.normalize()) || target.line() != 2 || target.column() != 3) {
            throw new AssertionError("Incorrect IDE target: " + target);
        }
        for (var invalid : new String[]{"https://example.test/x", "//host/share/x", "relative.txt"}) {
            reject(Map.of("path", invalid));
        }
        reject(Map.of("path", path.toString(), "line", 0));
        reject(Map.of("path", path.toString(), "column", 1.5));
        System.out.println("IDE file navigation target checks passed");
    }

    private static void reject(Map<String, Object> input) {
        try { IdeFileOpener.Target.parse(EmbeddedRuntime.JSON.toJson(input)); }
        catch (IllegalArgumentException | ArithmeticException e) { return; }
        throw new AssertionError("Accepted an invalid IDE target: " + input);
    }
}
