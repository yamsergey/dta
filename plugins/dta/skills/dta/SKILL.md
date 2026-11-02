---
name: dta
description: Inspect a running Android app with DTA — read its screen as a View/Compose layout tree, list and read its HTTP and WebSocket traffic (bodies, protobuf decoded to JSON, redaction), change traffic with a network script, and add protobuf schemas. Use when debugging an Android app's UI or network behaviour on a device or emulator, when the user asks what the app shows, what requests it made, or wants to delay, mock, rewrite or drop traffic.
---

# Using DTA

DTA runs inside a debug build of the app and reports what the app is doing. The `dta` MCP tools talk to a shared DTA daemon on this machine; it starts by itself. The same tools may also come from Android Studio's DTA plugin over HTTP (`http://127.0.0.1:12321/mcp`): they behave identically, and both share the one daemon.

Use adb directly for screenshots, taps, swipes, text input and logcat. Use DTA for what adb cannot see: the app's own view of its screen (Compose names, test tags, source lines) and its network traffic.

## Run the app

After changing the app's code, `app_run` builds, installs and launches it with DTA injected (no project changes) and waits until DTA is connected; then use `network_list` or `layout_tree`. Pass `project` when the server was not started in the project's folder. A build takes minutes. Read the result: `outcome` is `connected`, or `failure` names the step, the cause and the next step (a build error with its file and line, an install refused for its signature — `reinstall` deletes the app's data, so ask the user first — no launcher activity, a crash at start). `ran-without-dta` means the app runs but DTA could not be added; the cause says why.

## Device and app

- Omit `device` when one device is connected; omit `app` to use the foreground app. Every result's `selection` says what was chosen (`only-device`, `foreground`). Check it before drawing conclusions.
- Several devices: the error lists them; pass `device`.
- "has no DTA inside": the app was not built with DTA, or is not running. `apps_list` shows which running apps have DTA; `app_run` builds it with DTA.

## Read the statements

Results say how each value was obtained. Never ignore them:
- `layout_tree` with `source: uiautomator` and `fallbackReason`: the system fallback captured the screen (no Compose names, no source lines). Tell the user.
- `degraded` markers (`compose-tooling-absent`, `network-body-incomplete`, …): something is partial; the reason says what.
- `capability_report`: a network library `degraded` is captured in part — its `notCaptured` events (for example handshake headers, failures) are missing and its `captured` ones are there, so read its traffic and say what is missing. Only `unavailable` means none of it is captured.
- Bodies with `completeness` other than `complete` (`truncated`, `partially-observed`, `omitted`, `dropped`) are not the whole body.
- `intervention` on an entry: the network script changed it; `appRequest`/`appResponse` show what the app saw.
- A result with `truncated` was cut to fit; it says what was left out and how to get it.

## History and archive

- `network_list` answers from this computer's copy of the app's history. Coverage may say entries or bodies are not read or copied yet: ask again.
- When the device cleared or lost its history, this computer kept it: the live list states `archivedEpochs`. Pass `archive` (`all`, `version:<name>`, `<fromMs>..<toMs>`, or an epoch id from `archive_list`) to look back; every entry then carries its epoch and why it was archived. The app does not need to run for that.
- `archive_delete` only says what it would remove unless `confirm` is true; deleting is the user's call, so ask first.

## Protobuf bodies

When a body is binary protobuf and not decoded, the schemas or the mapping are missing. In the project folder, `network_schema_sync` with the folder takes its `.proto` files (the stdio server does it once by itself, and says so). Then read the code: find the endpoint that sends or returns the body (a Retrofit interface, an OkHttp call, a WebSocket handler), and add the mapping with `network_mapping_add` (URL pattern, part, the schema set from the sync result, the message type). Fetch the body again; the result names the type and whose schemas decoded it.

## Layout

- `layout_tree` returns windows with nodes (`kind`: view, compose, fallback), bounds in screen pixels, text, interactions.
- Prefer filters to whole trees: `text`, `type` (simple class or composable name), `resourceId`, `rootId` (a subtree). Matches come with their ancestors.
- `detail`: `semantic` (default), `layout` (adds layout containers), `composition` (adds the call chain per node). Ask for more only when you need it.
- A user composable's `parameters` appear once per call, on its first node; the call's other nodes carry `sameCallAs: <id>` instead. Values read as what they are: `#AARRGGBB`, `16.0.dp`, `22.0.sp`, `Unspecified`; `<lambda>` and `<ClassName>` mean there is no readable value; `null` parameters are left out; `cut: true` marks a value cut at 160 characters. Below `composition`, parameters left at their defaults are not listed — `defaultParameters` counts them; `composition` lists them with `default: true`. An inline class DTA does not decode says `(packed, not decoded)` in its type.
- Bounds are in screen pixels: the same coordinates `adb shell input tap` uses. A rotated or scaled view's bounds are its box on the screen; `renderOutline` has its four corners.
- `screen: true` adds the screen image taken with the tree (one image item) and `screenWindows`: every window on screen, the app's or not (`kind`: toast, keyboard, system, other-app…). A toast or a system dialog is on the picture but not in the app's tree; `screenWindows` names it. `screenOutput: <file>` writes the PNG instead. Read the statements: `screen-changed-during-capture` (the picture and the tree may differ in the area it names), `screen-blocked` (a secure window, no picture), `screen-locked`, `screen-not-aligned`, `screen-scaled` (multiply bounds by `screenImage.scale`). Ask for the screen only when you need to see it; the tree alone is cheaper.

## Timeline, causes and state

- Every tool answer may end with "Since your last call: N events — …": clicks, dialogs, toasts, requests, navigation, the app's own events since your previous call. Read it before deciding what happened after a tap.
- `timeline_events` gives the details, oldest first, from every source: `input` (clicks, back), `ui` (windows, toasts, snackbars, permission prompts — structure and text, never pictures), `screen`, `network`, `nav`, `app`. Pass `since` (the `next` of the previous read) for newer events only; `sources` and `kinds` (e.g. `ui/toast`, `network/request`) narrow it.
- Each event lists its known `causes`, each with a `basis`: `exact` (recorded data: issued while a click or back was handled, a response to its request, a window's closing to its opening), `declared` (the app said so). Neighbouring events are never linked silently.
- `timeline_causes` walks the chain from an event back to its roots, or forward with `direction: effects`. An event without a known cause says so and how many inferred candidates exist; ask with `inferred: true` to get them, marked `inferred`, with the rule (`after-response`, `while-in-flight`, `text-match`). Treat inferred links as leads, not facts.
- `state_current`: what the app is in now — every facet (`ui.windows`, `screen.fingerprint`, `nav.destination`, `app.*`…) with its source and when it changed, and each source's status (on, off or degraded, with why). A stale facet says why (the process ended, its source went off).
- `state_graph` groups this run into states by the `facets` you choose (fewer facets, coarser states) with transitions, their triggers and causes; transitions without a known cause are marked. `state_path` gives the shortest path the app was seen to take to a state.
- A gap event (`timeline/gap`) means events were lost there; `timeline/process-ended` marks a crash or kill — what came before it is kept, and no link crosses it.
- Windows that are not the app's are sampled every 500 ms while the timeline is read; shorter ones may be missed. Work the app moves to another thread has no exact cause unless the app declares one with `dta-state`.

## Network

- `network_list` is one chronological list of HTTP exchanges, WebSocket connections and messages. Filters: `url`, `method`, `status` (`404`, `4xx`, `failed`, `pending`), `kinds`, `sinceMs`/`untilMs`, `bodyContains` (also inside binary bodies), `runs` (`current` = this process only), `intervened`.
- To follow traffic over time: note the time, act, then call `network_list` with `sinceMs`.
- `network_entry` gives headers, timing, the initiator stack (first app frame marked) and, for a WebSocket connection, its messages.
- `network_body`: text for text bodies and decoded protobuf; for other binary bodies pass `output` (a file path) and read the file. Large bodies come back as a file automatically.
- `redact: true` masks tokens, cookies, passwords and keys (in decoded protobuf too); bodies that cannot be redacted are withheld unless `allowUnredacted`.
- `network_clear` empties the history (all runs).

## Links

- `links_list` lists the links (deep links, App Links, any intent with a URI) that reached an activity of the app: URI, `delivery` (`cold-start`, `created`, `new-intent`, `from-recents`), activity, referrer. Filters: `uri`, `activity`, `delivery`, `runs`, `archive`. `links_entry` gives the query, flags and every extra (or why it could not be read).
- `links_open` opens a URI on the device and says who received it: the app (with the link's id), another app, the chooser, or nothing. Pass `package` to wait for the app's record and, for an https link it did not get, to see its App Links state; `only: true` sends it to the app alone.
- `links_verification` says, per declared https host, whether Android verified it and whether the user allowed it: why a link opens in the browser instead of the app.
- Links the app hands to other apps (browser, store, mail) are not recorded; links it opens in a WebView or a Custom Tab are not either.

## Scripts

`network_script_set` installs JavaScript that runs inside the app for every request, response and WebSocket message. It persists across app restarts until `network_script_clear`. Check `network_script_status` and `network_script_logs` after installing. See [scripting.md](scripting.md) for the API and recipes.

## Protobuf

OTLP (OpenTelemetry) exports decode automatically. For the app's own protobuf, add the `.proto` files with `network_schema_add` and map a message type to traffic with `network_mapping_add`. See [protobuf.md](protobuf.md).

## Troubleshooting

- "newer than this dta": a newer DTA daemon runs; the user upgrades the CLI or runs `dta daemon stop`.
- Device or connection errors: `daemon_status` shows adb, devices and open app connections.
