---
name: dta
description: Inspect a running Android app with DTA — read its screen as a View/Compose layout tree, list and read its HTTP and WebSocket traffic (bodies, protobuf decoded to JSON, redaction), change traffic with a network script, and add protobuf schemas. Use when debugging an Android app's UI or network behaviour on a device or emulator, when the user asks what the app shows, what requests it made, or wants to delay, mock, rewrite or drop traffic.
---

# Using DTA

DTA runs inside a debug build of the app and reports what the app is doing. The `dta` MCP tools talk to a shared DTA daemon on this machine; it starts by itself. The same tools may also come from Android Studio's DTA plugin over HTTP (`http://127.0.0.1:12321/mcp`): they behave identically, and both share the one daemon.

Use adb directly for screenshots, taps, swipes, text input and logcat. Use DTA for what adb cannot see: the app's own view of its screen (Compose names, test tags, source lines) and its network traffic.

## Device and app

- Omit `device` when one device is connected; omit `app` to use the foreground app. Every result's `selection` says what was chosen (`only-device`, `foreground`). Check it before drawing conclusions.
- Several devices: the error lists them; pass `device`.
- "has no DTA inside": the app was not built with DTA, or is not running. `apps_list` shows which running apps have DTA.

## Read the statements

Results say how each value was obtained. Never ignore them:
- `layout_tree` with `source: uiautomator` and `fallbackReason`: the system fallback captured the screen (no Compose names, no source lines). Tell the user.
- `degraded` markers (`compose-tooling-absent`, `network-body-incomplete`, …): something is partial; the reason says what.
- Bodies with `completeness` other than `complete` (`truncated`, `partially-observed`, `omitted`, `dropped`) are not the whole body.
- `intervention` on an entry: the network script changed it; `appRequest`/`appResponse` show what the app saw.
- A result with `truncated` was cut to fit; it says what was left out and how to get it.

## Layout

- `layout_tree` returns windows with nodes (`kind`: view, compose, fallback), bounds in screen pixels, text, interactions.
- Prefer filters to whole trees: `text`, `type` (simple class or composable name), `resourceId`, `rootId` (a subtree). Matches come with their ancestors.
- `detail`: `semantic` (default), `layout` (adds layout containers), `composition` (adds the call chain per node). Ask for more only when you need it.
- Bounds are in screen pixels: the same coordinates `adb shell input tap` uses.

## Network

- `network_list` is one chronological list of HTTP exchanges, WebSocket connections and messages. Filters: `url`, `method`, `status` (`404`, `4xx`, `failed`, `pending`), `kinds`, `sinceMs`/`untilMs`, `bodyContains` (also inside binary bodies), `runs` (`current` = this process only), `intervened`.
- To follow traffic over time: note the time, act, then call `network_list` with `sinceMs`.
- `network_entry` gives headers, timing, the initiator stack (first app frame marked) and, for a WebSocket connection, its messages.
- `network_body`: text for text bodies and decoded protobuf; for other binary bodies pass `output` (a file path) and read the file. Large bodies come back as a file automatically.
- `redact: true` masks tokens, cookies, passwords and keys (in decoded protobuf too); bodies that cannot be redacted are withheld unless `allowUnredacted`.
- `network_clear` empties the history (all runs).

## Scripts

`network_script_set` installs JavaScript that runs inside the app for every request, response and WebSocket message. It persists across app restarts until `network_script_clear`. Check `network_script_status` and `network_script_logs` after installing. See [scripting.md](scripting.md) for the API and recipes.

## Protobuf

OTLP (OpenTelemetry) exports decode automatically. For the app's own protobuf, add the `.proto` files with `network_schema_add` and map a message type to traffic with `network_mapping_add`. See [protobuf.md](protobuf.md).

## Troubleshooting

- "newer than this dta": a newer DTA daemon runs; the user upgrades the CLI or runs `dta daemon stop`.
- Device or connection errors: `daemon_status` shows adb, devices and open app connections.
