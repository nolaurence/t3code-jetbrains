package dev.t3code.jetbrains;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class EmbeddedRuntimeEnvironmentTest {
    public static void main(String[] args) throws Exception {
        if (!Files.isExecutable(Path.of("/usr/bin/env"))) return;
        var runtime = Path.of(args[0]);
        var home = Files.createTempDirectory("t3 test home ");
        try {
            var ideBin = Files.createDirectories(home.resolve("ide-bin"));
            var localBin = Files.createDirectories(home.resolve(".local/bin"));
            executable(ideBin.resolve("codex"), "#!/usr/bin/env node\nconsole.log(process.execPath);\n");
            executable(localBin.resolve("codex"), "#!/bin/sh\necho wrong-codex\n");
            executable(localBin.resolve("claude"), "#!/bin/sh\nprintf '%s\\n' \"$HOME\"\n");

            var ideEnv = Map.of("PATH", ideBin.toString());
            var explicitCodex = run(runtime, home, Map.of(), ideBin.resolve("codex").toString());
            if (!Files.isSameFile(Path.of(explicitCodex), runtime.resolve("node"))) {
                throw new AssertionError("Codex at an explicit path did not find bundled Node: " + explicitCodex);
            }
            var nodePath = run(runtime, home, ideEnv, "codex");
            if (!Files.isSameFile(Path.of(nodePath), runtime.resolve("node"))) {
                throw new AssertionError("Codex did not run using bundled Node: " + nodePath);
            }
            if (!home.toString().equals(run(runtime, home, ideEnv, "claude"))) {
                throw new AssertionError("Claude did not keep the original HOME");
            }
            System.out.println("Packaged provider startup with a Finder-like PATH passed");
        } finally {
            try (var files = Files.walk(home)) {
                for (var path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void executable(Path path, String script) throws Exception {
        if (!Files.writeString(path, script).toFile().setExecutable(true)) throw new AssertionError(path);
    }

    private static String run(Path runtime, Path home, Map<String, String> ideEnv, String command) throws Exception {
        var builder = new ProcessBuilder("/usr/bin/env", command).redirectErrorStream(true);
        var env = builder.environment();
        env.clear();
        env.put("HOME", home.toString());
        env.put("PATH", "/usr/bin:/bin");
        EmbeddedRuntime.configureEnvironment(env, ideEnv, runtime);
        var child = builder.start();
        if (!child.waitFor(10, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            throw new AssertionError(command + " did not exit");
        }
        try (var output = child.inputReader()) {
            var line = output.readLine();
            if (child.exitValue() != 0) throw new AssertionError(command + " failed: " + line);
            return line;
        }
    }
}
