# Network script API

The script is JavaScript (ES6 subset) running inside the app. All handlers are optional:

- `onRequest(req)` — before a request is sent.
- `onResponse(res, req)` — before the app receives a response.
- `onWsSend(msg, conn)` / `onWsReceive(msg, conn)` — WebSocket messages out / in.

| Object | Fields | Methods |
|---|---|---|
| `req` | `url`, `method`, `headers` (object), `library`, `id` | `text()`, `bytes()`, `json()` |
| `res` | `status`, `headers` | `text()`, `bytes()`, `json()` |
| `msg` | `text` (text frames), `binary` (bool) | `bytes()` |
| `conn` | `url`, `library`, `id` | — |

Return values:
- `undefined` or the unchanged object: no change.
- The mutated object: its changed fields apply (`req.url`, `req.headers`, `req.body = "text" | bytes`, `res.status`, `res.body`, `msg.text`, `msg.bytes`).
- `respond({status, headers, body})` (in `onRequest` only): answer without the network.
- `drop()`: the app sees a network failure (or the message disappears).
- `null`: an error is logged, traffic unchanged.

Globals: `sleep(ms)`, `throttle(bytesPerSecond)` (in `onResponse` only), `state` (`get`, `set`, `delete`, `clear` — kept between calls), `log(...)` (read with `network_script_logs`).

## Recipes

```js
// Delay everything by 2 s
function onRequest(req) { sleep(2000); }

// Fail matching requests with a status
function onRequest(req) { if (req.url.includes("/login")) return respond({status: 503, body: "down"}); }

// Mock a JSON response
function onRequest(req) {
  if (req.url.endsWith("/config")) return respond({status: 200, headers: {"Content-Type": "application/json"}, body: JSON.stringify({beta: true})});
}

// Drop a request
function onRequest(req) { if (req.url.includes("/analytics")) return drop(); }

// Throttle downloads to 50 KB/s
function onResponse(res, req) { throttle(50 * 1024); }

// Rewrite or drop socket messages
function onWsSend(msg, conn) { if (msg.text) { msg.text = msg.text.replace("v1", "v2"); return msg; } }
function onWsReceive(msg, conn) { if (msg.text && msg.text.includes("\"ping\"")) return drop(); }

// Fail every third request
function onRequest(req) {
  const n = (state.get("n") || 0) + 1; state.set("n", n);
  if (n % 3 === 0) return drop();
}
```

Entries the script touched carry `intervention` (what changed, delay); filter them with `network_list` `intervened: true`.
