package dev.t3code.jetbrains;

import com.google.gson.JsonObject;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.jcef.*;
import org.cef.browser.*;
import org.cef.callback.CefCallback;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.*;
import org.cef.misc.*;
import org.cef.network.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.swing.JComponent;

final class EmbeddedPage implements Disposable {
    private static final long MAX_BODY = 100L * 1024 * 1024;
    private final JBCefBrowser browser;
    private final EmbeddedRuntime runtime;
    private final String prefix = UUID.randomUUID() + ":";
    private final Set<String> sockets = ConcurrentHashMap.newKeySet();
    private final String index;
    private volatile boolean disposed;

    EmbeddedPage(EmbeddedRuntime runtime, String surface, Consumer<String> openPage) throws IOException {
        this.runtime = runtime;
        var html = Files.readString(runtime.installed.resolve("client/index.html"));
        String bridge;
        try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("/embedded/bridge.js"))) {
            bridge = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        browser = JBCefBrowser.createBuilder().setEnableOpenDevToolsMenuItem(false).build();
        var cef = browser.getCefBrowser();
        var client = browser.getJBCefClient();
        var query = JBCefJSQuery.create((JBCefBrowserBase) browser);
        Disposer.register(browser, query);
        var fileRouter = CefMessageRouter.create(
            new CefMessageRouter.CefMessageRouterConfig("__t3OpenFile", "__t3CancelOpenFile"),
            new CefMessageRouterHandlerAdapter() {
                @Override public boolean onQuery(CefBrowser b, CefFrame frame, long id, String payload,
                        boolean persistent, CefQueryCallback callback) {
                    if (disposed || !frame.isMain() || !local(frame.getURL()) || persistent) return false;
                    IdeFileOpener.open(runtime.project(), payload, callback);
                    return true;
                }
            });
        client.getCefClient().addMessageRouter(fileRouter);
        Disposer.register(browser, () -> {
            client.getCefClient().removeMessageRouter(fileRouter);
            fileRouter.dispose();
        });
        var editorStyles = "editor".equals(surface) ? """
            <style>
              .composer-tiptap[contenteditable="true"]:focus {
                caret-color: currentColor;
                caret-animation: manual;
              }
            </style>
            """ : "";
        index = html.replace("<head>", "<head>" + editorStyles + "<script>" + bridge
            .replace("__T3_QUERY__", query.inject("JSON.stringify(message)"))
            .replace("__T3_SURFACE__", surface) + "</script>");
        query.addHandler(raw -> {
            if (!disposed && local(cef.getURL())) {
                try { receive(EmbeddedRuntime.JSON.fromJson(raw, JsonObject.class), openPage); }
                catch (RuntimeException ignored) { }
            }
            return null;
        });
        client.addRequestHandler(new CefRequestHandlerAdapter() {
            @Override public boolean onBeforeBrowse(CefBrowser b, CefFrame frame, CefRequest req, boolean gesture, boolean redirect) {
                if (frame.isMain() && local(req.getURL())) { closeSockets(); return false; }
                if (frame.isMain() && gesture) external(req.getURL());
                return true;
            }
            @Override public CefResourceRequestHandler getResourceRequestHandler(CefBrowser b, CefFrame frame,
                    CefRequest req, boolean nav, boolean download, String initiator, BoolRef disableDefault) {
                if (!local(req.getURL())) return null;
                disableDefault.set(true);
                return new CefResourceRequestHandlerAdapter() {
                    @Override public CefResourceHandler getResourceHandler(CefBrowser ignored, CefFrame f, CefRequest r) {
                        return new Resource();
                    }
                };
            }
        }, cef);
        client.addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
            @Override public boolean onBeforePopup(CefBrowser b, CefFrame f, String url, String name) {
                if (local(url)) {
                    var target = URI.create(url);
                    ApplicationManager.getApplication().invokeLater(() -> openPage.accept(relative(target)));
                } else external(url);
                return true;
            }
        }, cef);
    }

    JComponent component() { return browser.getComponent(); }
    void open(String path) { if (!disposed && validPath(path)) browser.loadURL(runtime.origin + path); }
    static boolean validPath(String path) { return path != null && path.startsWith("/") && !path.startsWith("//") && !path.contains("\\"); }
    private boolean local(String url) { return url != null && url.startsWith(runtime.origin + "/"); }
    private static String relative(URI url) { return url.getRawPath() + (url.getRawQuery() == null ? "" : "?" + url.getRawQuery()); }
    private static void external(String url) {
        if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) BrowserUtil.browse(url);
    }

    private void receive(JsonObject frame, Consumer<String> openPage) {
        var type = EmbeddedRuntime.string(frame, "type");
        if ("navigate".equals(type)) {
            var target = EmbeddedRuntime.string(frame, "path");
            if (validPath(target)) ApplicationManager.getApplication().invokeLater(() -> { if (!disposed) openPage.accept(target); });
            return;
        }
        var key = EmbeddedRuntime.string(frame, "id");
        if (key == null || !key.matches("[a-f0-9-]{36}")) return;
        var id = prefix + key;
        if ("open".equals(type)) {
            if (!sockets.add(id)) return;
            runtime.track(id, msg -> {
                var event = EmbeddedRuntime.string(msg, "type");
                if ("close".equals(event) || "failed".equals(event)) {
                    runtime.forget(id);
                    sockets.remove(id);
                }
                msg.addProperty("id", key);
                if (!disposed) browser.getCefBrowser().executeJavaScript(
                    "window.__t3IdeFrame?.(" + EmbeddedRuntime.JSON.toJson(msg) + ")", runtime.origin, 0);
            });
        } else if (!("send".equals(type) || "close".equals(type)) || !sockets.contains(id)) return;
        frame.addProperty("id", id);
        runtime.send(frame);
    }

    private void closeSockets() {
        for (var id : sockets) {
            runtime.forget(id);
            runtime.send(frame("close", id));
        }
        sockets.clear();
    }

    @Override public void dispose() {
        disposed = true;
        closeSockets();
        Disposer.dispose(browser);
    }

    private static JsonObject frame(String type, String id) {
        var message = new JsonObject();
        message.addProperty("type", type);
        message.addProperty("id", id);
        return message;
    }

    private final class Resource extends CefResourceHandlerAdapter {
        private final String id = prefix + UUID.randomUUID();
        private final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
        private Map<String, String> headers = Map.of();
        private InputStream file;
        private int status = 200;
        private int length = -1;
        private int offset;
        private boolean complete;
        private boolean remote;
        private CefCallback waitingHeaders;
        private CefCallback waitingRead;

        @Override public boolean processRequest(CefRequest req, CefCallback cb) {
            try {
                var uri = URI.create(req.getURL());
                var path = uri.getPath();
                if (path.startsWith("/api/") || path.startsWith("/oauth/") || path.startsWith("/.well-known/")) {
                    var message = frame("http", id);
                    message.addProperty("method", req.getMethod());
                    message.addProperty("path", relative(uri));
                    var raw = new HashMap<String, String>();
                    req.getHeaderMap(raw);
                    var forwarded = new HashMap<String, String>();
                    raw.forEach((key, val) -> forwarded.put(key.toLowerCase(Locale.ROOT), val));
                    message.add("headers", EmbeddedRuntime.JSON.toJsonTree(forwarded));
                    if (req.getPostData() != null) {
                        var elements = new Vector<CefPostDataElement>();
                        req.getPostData().getElements(elements);
                        var body = new ByteArrayOutputStream();
                        for (var part : elements) {
                            if (part.getType() != CefPostDataElement.Type.PDE_TYPE_BYTES ||
                                part.getBytesCount() + (long)body.size() > MAX_BODY) {
                                finishError(cb, 413, "Unsupported upload body"); return true;
                            }
                            var bytes = new byte[part.getBytesCount()];
                            body.write(bytes, 0, part.getBytes(bytes.length, bytes));
                        }
                        message.addProperty("body", Base64.getEncoder().encodeToString(body.toByteArray()));
                    }
                    synchronized (this) { waitingHeaders = cb; remote = true; }
                    runtime.track(id, this::accept);
                    runtime.send(message);
                } else {
                    var root = runtime.installed.resolve("client");
                    var asset = root.resolve(path.substring(1)).normalize();
                    if (!asset.startsWith(root)) { finishError(cb, 404, "Not found"); return true; }
                    if (Files.isRegularFile(asset) && !path.equals("/index.html")) {
                        file = Files.newInputStream(asset);
                        length = Math.toIntExact(Files.size(asset));
                        headers = Map.of("content-type", mime(asset));
                        cb.Continue();
                    } else if (path.startsWith("/assets/") || path.contains(".")) {
                        if (path.equals("/index.html")) finishError(cb, 200, index);
                        else finishError(cb, 404, "Not found");
                    } else finishError(cb, 200, index);
                }
            } catch (IOException | RuntimeException e) { finishError(cb, 500, "T3 Code could not load this resource."); }
            return true;
        }

        private void finishError(CefCallback cb, int code, String text) {
            var bytes = text.getBytes(StandardCharsets.UTF_8);
            file = new ByteArrayInputStream(bytes);
            length = bytes.length;
            status = code;
            headers = code == 200 ? Map.of("content-type", "text/html; charset=utf-8", "content-security-policy",
                "script-src 'self' 'unsafe-inline' 'wasm-unsafe-eval'; object-src 'none'; base-uri 'none'; worker-src 'self' blob:")
                : Map.of("content-type", "text/plain; charset=utf-8");
            cb.Continue();
        }

        private void accept(JsonObject msg) {
            CefCallback cb = null;
            synchronized (this) {
                if (complete) return;
                switch (Objects.toString(EmbeddedRuntime.string(msg, "type"), "")) {
                    case "headers" -> {
                        status = msg.get("status").getAsInt();
                        var meta = new HashMap<String, String>();
                        msg.getAsJsonObject("headers").entrySet().forEach(entry -> {
                            if (entry.getValue().isJsonPrimitive()) meta.put(entry.getKey(), entry.getValue().getAsString());
                        });
                        headers = meta;
                        cb = waitingHeaders;
                        waitingHeaders = null;
                    }
                    case "chunk" -> {
                        chunks.add(Base64.getDecoder().decode(msg.get("data").getAsString()));
                        cb = waitingRead;
                        waitingRead = null;
                    }
                    case "failed", "http-error", "end" -> {
                        complete = true;
                        runtime.forget(id);
                        if (waitingHeaders != null) {
                            status = 503;
                            headers = Map.of("content-type", "application/json");
                            chunks.add("{\"message\":\"T3 Code runtime did not start. See runtime.log in the IDE configuration directory.\"}".getBytes(StandardCharsets.UTF_8));
                            cb = waitingHeaders;
                            waitingHeaders = null;
                        } else { cb = waitingRead; waitingRead = null; }
                    }
                    default -> { }
                }
            }
            if (cb != null) cb.Continue();
        }

        @Override public synchronized void getResponseHeaders(CefResponse response, IntRef size, StringRef redirect) {
            response.setStatus(status);
            response.setHeaderMap(headers);
            if (headers.containsKey("content-type")) response.setMimeType(headers.get("content-type").split(";", 2)[0]);
            size.set(length);
        }

        @Override public synchronized boolean readResponse(byte[] output, int count, IntRef read, CefCallback cb) {
            if (file != null) {
                try {
                    var size = file.read(output, 0, count);
                    if (size > 0) { read.set(size); return true; }
                    file.close();
                    file = null;
                } catch (IOException ignored) { }
                return false;
            }
            if (chunks.isEmpty()) {
                if (complete || !remote) return false;
                waitingRead = cb;
                return true;
            }
            var chunk = chunks.element();
            int size = Math.min(count, chunk.length - offset);
            System.arraycopy(chunk, offset, output, 0, size);
            offset += size;
            if (offset == chunk.length) { chunks.remove(); offset = 0; }
            read.set(size);
            return true;
        }

        @Override public synchronized void cancel() {
            if (file != null) try { file.close(); } catch (IOException ignored) { }
            if (remote && !complete) { runtime.forget(id); runtime.send(frame("cancel", id)); }
            complete = true;
            chunks.clear();
            waitingRead = waitingHeaders = null;
        }
    }

    private static String mime(Path path) throws IOException {
        var name = path.getFileName().toString();
        if (name.endsWith(".js") || name.endsWith(".mjs")) return "text/javascript";
        if (name.endsWith(".css")) return "text/css";
        if (name.endsWith(".wasm")) return "application/wasm";
        if (name.endsWith(".woff2")) return "font/woff2";
        if (name.endsWith(".svg")) return "image/svg+xml";
        return Objects.toString(Files.probeContentType(path), "application/octet-stream");
    }
}
