package dev.t3code.jetbrains;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

final class SessionArchive {
    record Work(String kind, String label, String state) {}
    record Message(String role, String text, String id, Work work) {
        Message(String role, String text) { this(role, text, null, null); }
    }
    record Thread(String id, String title, LocalCli.Provider provider, String sessionId, List<Message> messages, String model, String effort) {}
    private static final Gson JSON = new Gson();
    private final Path file;

    SessionArchive(Path ideConfig, String workspace) {
        this.file = ideConfig.resolve("t3-code-local").resolve(key(workspace) + ".json");
    }

    List<Thread> read() throws IOException {
        try (var reader = Files.newBufferedReader(file)) {
            var threads = JSON.fromJson(reader, new TypeToken<List<Thread>>() {});
            if (threads == null || threads.stream().anyMatch(t -> t == null || t.id() == null || t.title() == null ||
                t.provider() == null || t.messages() == null || t.messages().stream().anyMatch(m -> m == null || m.role() == null || m.text() == null))) {
                throw new IOException("Invalid local T3 session history.");
            }
            return threads;
        } catch (NoSuchFileException e) {
            return List.of();
        } catch (JsonParseException e) {
            throw new IOException("Could not read local T3 session history.", e);
        }
    }

    void write(List<Thread> snapshot) throws IOException {
        Files.createDirectories(file.getParent());
        var temp = Files.createTempFile(file.getParent(), "session-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temp)) { JSON.toJson(snapshot, writer); }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String key(String workspace) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(workspace.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
