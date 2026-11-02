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
| `conn` | `url`, `library`, `id` | `push(message, {delayMs})` |

Return values:
- `undefined` or the unchanged object: no change.
- The mutated object: its changed fields apply (`req.url`, `req.headers`, `req.body = "text" | bytes`, `res.status`, `res.body`, `msg.text`, `msg.bytes`).
- `respond({status, headers, body})` (in `onRequest` only): answer without the network.
- `drop()`: the app sees a network failure (or the message disappears).
- `null`: an error is logged, traffic unchanged.

Globals: `sleep(ms)`, `throttle(bytesPerSecond)` (in `onResponse` only), `description(text)`, `state` (`get`, `set`, `delete`, `clear` — kept between calls), `log(...)` (read with `network_script_logs`).

Server messages the server never sent:
- `conn.push(message, {delayMs})` / `push(connOrId, message, {delayMs})`: the app receives `message` on that WebSocket as if the server sent it. A string is a text message; bytes (`msg.bytes()`) or an array of numbers a binary one; any other value is sent as JSON text. Returns whether the push started; failures go to the script log.
- `connections()`: the app's open WebSocket connections (`id`, `url`, `library`, `push`).
- `every(ms, fn)`, `after(ms, fn)` → a timer id; `cancel(id)`. Timers run until the script is cleared or replaced, and start again with the app. A script may consist of timers only.
- Pushed messages never pass through `onWsReceive`. They are recorded as incoming messages with `injected` (`by: script`, the script id, the handler or `timer`, and how they were delivered).

`description(text)` says in a few words what the handler did, e.g. `description("simulated checkout outage")`. It is stored on the entry (`intervention.description`, up to 200 characters; several calls join with "; ") and shown next to what DTA recorded. Only what a handler actually does changes traffic: a description of a call that changed nothing goes to the script log with the entry id instead. Unknown fields in a returned object are ignored, so `return {delayMs: 10}` does nothing — use `sleep(10)`.

## Recipes

```js
// Delay everything by 2 s
function onRequest(req) { sleep(2000); }

// Fail matching requests with a status, saying why
function onRequest(req) {
  if (req.url.includes("/login")) { description("login backend down"); return respond({status: 503, body: "down"}); }
}

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

// Answer a subscribe the backend does not serve yet: the snapshot carries the subscription id
function onWsSend(msg, conn) {
  if (msg.text && msg.text.startsWith("sub:")) conn.push({topic: msg.text.substring(4), items: []}, {delayMs: 200});
}

// Answer locally only: the server never sees the subscribe
function onWsSend(msg, conn) { if (msg.text === "sub:prices") { conn.push("snapshot:prices"); return drop(); } }

// A live price every second on every open socket
let price = 100;
every(1000, () => { price++; connections().forEach(c => push(c, {price: price})); });

// Fail every third request
function onRequest(req) {
  const n = (state.get("n") || 0) + 1; state.set("n", n);
  if (n % 3 === 0) return drop();
}
```

Entries the script touched carry `intervention` (what changed, delay); filter them with `network_list` `intervened: true`.
