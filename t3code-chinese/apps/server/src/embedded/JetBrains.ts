// @effect-diagnostics nodeBuiltinImport:off -- The IDE owns an authenticated stdio channel, not a network socket.
import * as Http from "node:http";
import { randomBytes } from "node:crypto";
import { createInterface } from "node:readline";
import * as NodeCrypto from "@effect/platform-node/NodeCrypto";

import * as NodeHttpServer from "@effect/platform-node/NodeHttpServer";
import * as NodeRuntime from "@effect/platform-node/NodeRuntime";
import * as NodeServices from "@effect/platform-node/NodeServices";
import * as NodeWS from "@effect/platform-node/NodeWS";
import * as Net from "@t3tools/shared/Net";
import * as Effect from "effect/Effect";
import * as Layer from "effect/Layer";
import * as Option from "effect/Option";
import * as ServerConfig from "../config.ts";
import { resolveServerConfig } from "../cli/config.ts";
import { runServerWithHttp } from "../server.ts";
import { virtualHttp } from "./VirtualHttp.ts";

const [baseDir, cwd, staticDir, origin] = process.argv.slice(3);
if (!baseDir || !cwd || !staticDir || !origin || !/^http:\/\/t3-[a-f0-9]+\.localhost$/.test(origin)) {
  throw new Error("Invalid IDE runtime arguments");
}
const desktopBootstrapToken = randomBytes(32).toString("hex");
const transport = virtualHttp();
const agent = new Http.Agent({ keepAlive: false });
agent.createConnection = transport.connect as typeof agent.createConnection;
const sockets = new Map<string, NodeWS.WebSocket>();
const requests = new Map<string, Http.ClientRequest>();
const output = (value: object) => process.stdout.write(`@t3-ide ${JSON.stringify(value)}\n`);

function request(method: string, path: string, headers: Http.OutgoingHttpHeaders) {
  return Http.request(new URL(path, origin), { agent, method, headers });
}

const ready = new Promise<void>((resolve) => {
  transport.server.on("newListener", function detect(event) {
    if (event !== "request") return;
    transport.server.off("newListener", detect);
    queueMicrotask(resolve);
  });
}).then(() => new Promise<string>((resolve, reject) => {
  const req = request("POST", "/api/auth/browser-session", { "content-type": "application/json", origin });
  req.on("response", (res) => {
    const cookie = res.headers["set-cookie"]?.map((value) => value.split(";", 1)[0]).join("; ");
    res.resume();
    if (res.statusCode === 200 && cookie) resolve(cookie);
    else reject(new Error(`IDE session initialization failed (${res.statusCode})`));
  });
  req.on("error", reject);
  req.end(JSON.stringify({ credential: desktopBootstrapToken }));
}));
void ready.then(() => output({ type: "ready" }), (error) => {
  output({ type: "fatal", message: String(error) });
  process.emit("SIGTERM");
});

const lines = createInterface({ input: process.stdin });
lines.on("line", (line) => {
  let frame;
  try { frame = JSON.parse(line); } catch { return; }
  if (typeof frame.id !== "string") return;
  const id = frame.id;
  if (frame.type === "send") { if (typeof frame.data === "string") sockets.get(id)?.send(frame.data); return; }
  if (frame.type === "close") { sockets.get(id)?.close(); return; }
  if (frame.type === "cancel") { requests.get(id)?.destroy(); return; }
  void ready.then((cookie) => {
    if (typeof frame.path !== "string" || !/^\/(?![\\/])/.test(frame.path)) throw new Error("Invalid path");
    if (frame.type === "open") {
      const url = new URL(frame.path, origin);
      if (url.origin !== origin || url.pathname !== "/ws") throw new Error("Invalid socket route");
      const socket = new NodeWS.WebSocket(url.href.replace(/^http:/, "ws:"), {
        agent, origin, headers: { cookie }, perMessageDeflate: false,
      });
      sockets.set(id, socket);
      socket.on("open", () => output({ id, type: "open" }));
      socket.on("message", (data) => output({ id, type: "message", data: data.toString() }));
      socket.on("error", () => output({ id, type: "error" }));
      socket.on("close", (code, reason) => {
        sockets.delete(id);
        output({ id, type: "close", code, reason: reason.toString() });
      });
    } else if (frame.type === "http") {
      if (new URL(frame.path, origin).origin !== origin) throw new Error("Invalid HTTP origin");
      const headers = { ...frame.headers, cookie } as Http.OutgoingHttpHeaders;
      for (const key of ["host", "content-length", "transfer-encoding", "connection", "accept-encoding"]) delete headers[key];
      if (typeof frame.body === "string") headers["content-length"] = Buffer.byteLength(frame.body, "base64");
      const req = request(frame.method, frame.path, headers);
      requests.set(id, req);
      req.once("close", () => requests.delete(id));
      req.once("error", () => output({ id, type: "http-error" }));
      req.on("response", (res) => {
        const headers = { ...res.headers };
        delete headers["set-cookie"];
        delete headers["transfer-encoding"];
        output({ id, type: "headers", status: res.statusCode, headers });
        res.on("data", (chunk: Buffer) => {
          if (!output({ id, type: "chunk", data: chunk.toString("base64") })) {
            res.pause();
            process.stdout.once("drain", () => res.resume());
          }
        });
        res.once("end", () => output({ id, type: "end" }));
        res.once("error", () => output({ id, type: "http-error" }));
      });
      req.end(typeof frame.body === "string" ? Buffer.from(frame.body, "base64") : undefined);
    }
  }).catch(() => output({ id, type: frame.type === "http" ? "http-error" : "close", code: 1006 }));
});
lines.once("close", () => process.emit("SIGTERM"));
process.once("SIGTERM", () => { transport.close(); lines.close(); });

const none = Option.none();
const boot = Effect.gen(function* () {
  const config = yield* resolveServerConfig({
    mode: Option.some("desktop"), port: Option.some(0), host: Option.some("127.0.0.1"),
    baseDir: Option.some(baseDir), cwd: Option.some(cwd), devUrl: none, noBrowser: Option.some(true),
    bootstrapFd: none, autoBootstrapProjectFromCwd: Option.some(true), logWebSocketEvents: none,
    tailscaleServeEnabled: Option.some(false), tailscaleServePort: none, lan: Option.some(false),
  }, Option.some("Warn"));
  return yield* runServerWithHttp(NodeHttpServer.layer(() => transport.server, {})).pipe(
    Effect.provideService(ServerConfig.ServerConfig, { ...config, desktopBootstrapToken, staticDir }),
  );
});
NodeRuntime.runMain(boot.pipe(Effect.provide(Layer.mergeAll(NodeServices.layer, NodeCrypto.layer, Net.layer))));
