// @effect-diagnostics nodeBuiltinImport:off -- Adapts Node's HTTP parser to private in-memory streams.
import * as Http from "node:http";
import { Duplex } from "node:stream";

export function virtualHttp() {
  const sockets = new Set<Duplex>();
  const server = Http.createServer({ requestTimeout: 0, headersTimeout: 0 });
  server.listen = (function (_options: unknown, callback: () => void) {
    queueMicrotask(callback);
    return server;
  }) as typeof server.listen;
  server.address = () => "jetbrains-stdio";
  const connect = () => {
    const [client, peer] = [new Endpoint(), new Endpoint()];
    client.peer = peer;
    peer.peer = client;
    for (const endpoint of [client, peer]) {
      sockets.add(endpoint);
      endpoint.on("error", () => {});
      endpoint.once("close", () => sockets.delete(endpoint));
    }
    server.emit("connection", peer);
    return client;
  };
  return {
    server,
    connect,
    close: () => { for (const socket of sockets) socket.destroy(); },
  };
}

class Endpoint extends Duplex {
  peer!: Endpoint;
  resumeWrite: (() => void) | undefined;
  override _read() {
    const resume = this.peer.resumeWrite;
    this.peer.resumeWrite = undefined;
    resume?.();
  }
  override _write(chunk: Buffer, _encoding: BufferEncoding, done: (error?: Error) => void) {
    if (this.peer.destroyed) return done(new Error("Virtual HTTP connection closed"));
    if (this.peer.push(chunk)) done();
    else this.resumeWrite = done;
  }
  override _final(done: () => void) { this.peer.push(null); done(); }
  override _destroy(error: Error | null, done: (error: Error | null) => void) {
    this.peer?.push(null);
    const resume = this.resumeWrite;
    this.resumeWrite = undefined;
    resume?.();
    done(error);
  }
  setTimeout() { return this; }
  setNoDelay() { return this; }
  setKeepAlive() { return this; }
}
