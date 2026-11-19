(() => {
  const nativeSend = (message) => { __T3_QUERY__; };
  const sockets = new Map();
  const NativeSocket = window.WebSocket;
  const states = { CONNECTING: 0, OPEN: 1, CLOSING: 2, CLOSED: 3 };
  class LocalSocket extends EventTarget {
    constructor(url) {
      super();
      const parsed = new URL(url, location.href);
      if (parsed.origin !== location.origin.replace(/^http:/, 'ws:') || parsed.pathname !== '/ws') {
        return new NativeSocket(url, ...Array.from(arguments).slice(1));
      }
      this.url = parsed.href;
      this.protocol = '';
      this.extensions = '';
      this.bufferedAmount = 0;
      this.binaryType = 'blob';
      this.readyState = states.CONNECTING;
      this.id = crypto.randomUUID();
      sockets.set(this.id, this);
      nativeSend({ type: 'open', id: this.id, path: parsed.pathname + parsed.search });
    }
    send(data) {
      if (this.readyState === states.CONNECTING) throw new DOMException('Socket is connecting', 'InvalidStateError');
      if (this.readyState === states.OPEN) nativeSend({ type: 'send', id: this.id, data });
    }
    close() {
      if (this.readyState >= states.CLOSING) return;
      this.readyState = states.CLOSING;
      nativeSend({ type: 'close', id: this.id });
    }
    fire(event) { this.dispatchEvent(event); this['on' + event.type]?.(event); }
  }
  Object.assign(LocalSocket, states);
  Object.assign(LocalSocket.prototype, states);
  window.WebSocket = LocalSocket;
  window.t3JetBrains = Object.freeze({
    surface: '__T3_SURFACE__',
    openPage: (path) => nativeSend({ type: 'navigate', path }),
    openFile: (target) => new Promise((resolve, reject) => {
      window.__t3OpenFile({
        request: JSON.stringify(target),
        onSuccess: () => resolve(),
        onFailure: (_code, message) => reject(new Error(message)),
      });
    }),
  });
  window.__t3IdeFrame = (frame) => {
    const socket = sockets.get(frame.id);
    if (!socket) return;
    if (frame.type === 'open') {
      if (socket.readyState === states.CLOSING) return;
      socket.readyState = states.OPEN;
      socket.fire(new Event('open'));
    } else if (frame.type === 'message') {
      socket.fire(new MessageEvent('message', { data: frame.data }));
    } else if (frame.type === 'error') {
      socket.fire(new Event('error'));
    } else if (frame.type === 'close' || frame.type === 'failed') {
      sockets.delete(frame.id);
      socket.readyState = states.CLOSED;
      if (frame.type === 'failed') socket.fire(new Event('error'));
      socket.fire(new CloseEvent('close', {
        code: frame.code ?? 1006, reason: frame.reason ?? '', wasClean: frame.code === 1000,
      }));
    }
  };
})();
