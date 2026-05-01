# Network Interceptor Scripts

## Goal

Replace the regex-mocks-only model with a stateful, scriptable interceptor
that runs inside sidekick on the device. Each app has at most one active
script. The agent observes traffic with the existing `network_requests` /
`websocket_messages` tools, then installs a JS script that mutates whatever
it wants — full power, no caps.

## Where it runs

On-device, inside sidekick's existing JVMTI hooks for OkHttp,
URLConnection, `okhttp3.WebSocket`, Java-WebSocket, and
nv-websocket-client. The script is invoked synchronously inside each hook
before the request leaves / response is delivered / WS frame is dispatched.
No daemon round-trip — actual bytes never leave the device.

## Engine

Mozilla **Rhino** (`org.mozilla:rhino`). Pure JVM, runs unmodified on
Android, ~1 MB. JS is what every coding model writes fluently. Full
language, **no sandboxing** (this is a developer tool).

## Script contract

A script is a JS source string. When loaded, sidekick evaluates it once
in a fresh `Scriptable` scope, then invokes top-level functions per
event. Missing handlers = pass-through.

```js
// All hooks are sync; return values replace the original
// (or null = drop the call entirely).
function onRequest(req)      { return req; }
function onResponse(resp)    { return resp; }
function onWsSend(frame)     { return frame; }
function onWsReceive(frame)  { return frame; }

// Helpers always available in scope:
log(...args)              // captured into a ring buffer the agent reads
state.get(key)            // persistent across invocations within an install
state.set(key, value)
sleep(ms)                 // synchronous; blocks the calling app thread
```

Object shapes (initial cut):

```js
req = {
  url:      "https://api.example.com/path?q=1",
  method:   "GET",
  headers:  { "Content-Type": "application/json", … },
  body:     Uint8Array,            // null when body absent
  tag:      "okhttp" | "urlconn",  // adapter that produced this
}

resp = {
  status:  200,
  headers: { … },
  body:    Uint8Array,
  request: <req>,
}

frame = {
  dir:           "send" | "recv",
  text:          "…",       // null for binary frames
  binary:        Uint8Array, // null for text frames
  connectionId:  "<sidekick-side WS id>",
}
```

Errors thrown from the script are caught, written to the ring buffer
with the JS stack, and the original object passes through unchanged.
**The app never crashes because of a bad script.** That is the only
correctness-driven safety net we add — everything else (timeouts,
sandboxing, body-size caps, `eval` restrictions, etc.) is intentionally
omitted; see [`feedback_dev_tool_no_kid_gloves`](../../.claude/projects/-Users-yamsergey-work-projects-dta/memory/feedback_dev_tool_no_kid_gloves.md).

## MCP surface

Three new tools (mirrored as REST endpoints in the daemon):

| Tool | Purpose |
| --- | --- |
| `interceptor_set(package, device?, script)` | Install or replace. Hot-swap: in-flight calls finish on the old script, new calls hit the new one. |
| `interceptor_clear(package, device?)`       | Uninstall. |
| `interceptor_logs(package, device?, since?)` | Return the ring buffer (script `log()` output + caught errors), so the agent can iterate. |

(`interceptor_get(package, device?)` is trivial to add later for
round-trip; not strictly required for v1.)

## Mock interaction

Existing regex mocks stay. **Script wins** — when an interceptor is
installed, mocks are bypassed for that app. Later we may compile mocks
down to interceptor scripts, but that is optimisation, not in scope.

## Phasing

### Phase 1 — Foundation

- Add `org.mozilla:rhino` to sidekick (`dta-sidekick/build.gradle`).
- New module `dta-sidekick/.../interceptor/`:
  - `InterceptorRuntime` — singleton, holds compiled script + state map +
    ring buffer; exposes `onRequest(req)` / `onResponse(resp)` /
    `onWsSend(frame)` / `onWsReceive(frame)` that delegate to JS.
  - `ScriptScope` — sets up the JS scope with `log` / `state` / `sleep`
    helpers and the request/response/frame conversions.
  - `ScriptLog` — bounded ring buffer (e.g. 1000 lines × 4 KB) for
    `log()` output + caught errors.
- New endpoints on `InspectorServer`: `POST /interceptor`, `DELETE
  /interceptor`, `GET /interceptor/logs`.
- New MCP tools that proxy to those endpoints.

### Phase 2 — HTTP integration

- `OkHttpAdapter` and `URLConnectionAdapter`: at the existing
  pre-request capture point, call `InterceptorRuntime.onRequest(req)`
  and replace the underlying request payload with the returned object's
  `body`/`headers`/`url`/`method` (or short-circuit to a synthetic
  drop response when null).
- Same for `onResponse(resp)` after capture but before the app sees
  the response stream — replace headers/status/body bytes.
- Marshal `body` as `Uint8Array` / null without copying when the script
  doesn't read it (laziness via `getBody()` indirection).

### Phase 3 — WebSocket integration

- `OkHttpWebSocketAdapter`, `JavaWebSocketAdapter`,
  `NvWebSocketAdapter`: hook send + receive. `onWsSend` modifies the
  frame before it goes on the wire; `onWsReceive` modifies before the
  app's listener sees it. Drop = swallow the frame entirely.

### Phase 4 — Polish

- Surface script errors back through the MCP tool result so the agent
  doesn't have to poll `interceptor_logs` to notice it's broken.
- Examples library (`docs/interceptor-examples.md`):
  modify auth header, latency injection, fail every Nth, pin a date,
  rewrite WS subscriptions, etc.
- Hot-reload UX: `interceptor_set` returns a compile-time error
  immediately if the JS doesn't parse, so the agent knows before
  any traffic hits it.

## Out of scope (for v1)

- Multi-script composition.
- Higher-level rule DSL on top of JS.
- Mock → script auto-compilation.
- Cross-app shared state.
- Persisting scripts across app restarts (sidekick init wipes them).

## Open follow-ups

- **Body bytes vs streaming.** OkHttp/URLConnection bodies are typically
  buffered for capture anyway, so passing them as `Uint8Array` is fine.
  If we ever want to support large streamed bodies without buffering,
  we'd need a chunked callback API. Defer until someone hits it.
- **Ring buffer sizing.** 1000 × 4 KB is a guess; tune in the field.
- **Threading.** Interceptor calls run on whatever thread invoked the
  hook. That's correct (sync semantics) and matches the user's intent
  ("how else will it wait for the script to finish?"). No thread pool,
  no executor.
