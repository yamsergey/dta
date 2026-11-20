import base64, hashlib, socket, struct, threading, time, json
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
class H(BaseHTTPRequestHandler):
    def _ok(self):
        b = json.dumps({"type": "tick", "path": self.path}).encode()
        self.send_response(200); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_GET(self): self._ok()
    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0)); self.rfile.read(n); self._ok()
    def log_message(self, *a): pass
threading.Thread(target=lambda: ThreadingHTTPServer(("127.0.0.1", 8099), H).serve_forever(), daemon=True).start()
def frame(t):
    d = t.encode(); n = len(d)
    h = bytes([0x81, n]) if n < 126 else bytes([0x81, 126]) + struct.pack(">H", n)
    return h + d
def client(c):
    req = b""
    while b"\r\n\r\n" not in req: req += c.recv(4096)
    key = [l.split(b":", 1)[1].strip() for l in req.split(b"\r\n") if l.lower().startswith(b"sec-websocket-key")][0]
    acc = base64.b64encode(hashlib.sha1(key + b"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").digest())
    c.sendall(b"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + acc + b"\r\n\r\n")
    i = 0
    try:
        while True:
            i += 1; c.sendall(frame(json.dumps({"type": "tick", "symbol": "FAST", "n": i}))); time.sleep(float(__import__("os").environ.get("WS_SLEEP","0.2")))
    except Exception: pass
s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); s.bind(("127.0.0.1", 8098)); s.listen()
while True:
    c, _ = s.accept(); threading.Thread(target=client, args=(c,), daemon=True).start()
