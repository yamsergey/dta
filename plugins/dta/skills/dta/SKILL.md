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
- Bodies with `completeness` other than `complete` (`truncated`, `partially-observed`, `omitted`, `dropped`, `not-read`) are not the whole body. `not-read`: the app closed it without reading it (the status code was enough); not a capture problem, so nothing marks it, and what the close let DTA take is stored.
- `intervention` on an entry: the network script changed it; `appRequest`/`appResponse` show what the app saw.
- A result with `truncated` was cut to fit; it says what was left out and how to get it.

## History and archive

- `network_list` answers from this computer's copy of the app's history. Coverage may say entries or bodies are not read or copied yet: ask again.
- `network_export` writes what `network_list` selects (same filters, all pages) to a HAR file for browsers' tools or the user; prefer `network_list`/`network_body` to read traffic yourself.
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
- `timeline_events` gives the details, oldest first, from every source: `input` (clicks, back), `ui` (windows, toasts, snackbars, permission prompts — structure and text, never pictures), `screen`, `network`, `nav`, `app`, `dta` (what DTA itself wrote into the app's data). Pass `since` (the `next` of the previous read) for newer events only; `sources` and `kinds` (e.g. `ui/toast`, `network/request`) narrow it.
- Each event lists its known `causes`, each with a `basis`: `exact` (recorded data: issued while a click or back was handled, a response to its request, a window's closing to its opening), `declared` (the app said so). Neighbouring events are never linked silently.
- `timeline_causes` walks the chain from an event back to its roots, or forward with `direction: effects`. An event without a known cause says so and how many inferred candidates exist; ask with `inferred: true` to get them, marked `inferred`, with the rule (`after-response`, `while-in-flight`, `text-match`). Treat inferred links as leads, not facts.
- `state_current`: what the app is in now — every facet (`ui.windows`, `screen.fingerprint`, `nav.destination`, `app.*`…) with its source and when it changed, and each source's status (on, off or degraded, with why). A stale facet says why (the process ended, its source went off).
- `state_graph` groups this run into states by the `facets` you choose (fewer facets, coarser states) with transitions, their triggers and causes; transitions without a known cause are marked. `state_path` gives the shortest path the app was seen to take to a state.
- A gap event (`timeline/gap`) means events were lost there; `timeline/process-ended` marks a crash or kill — what came before it is kept, and no link crosses it.
- Windows that are not the app's are sampled every 500 ms while the timeline is read; shorter ones may be missed. Work the app moves to another thread has no exact cause unless the app declares one with `dta-state`.

## App data

- `data_list` lists the app's stores: databases (`sqlite`, `room`, `sqlcipher`, `driver`), SharedPreferences (`plain`, `encrypted`), DataStore files (`preferences`, `proto`) and the file roots `data:/` and `external:/`. Each says how DTA reaches it: `live` (through the app's own open object — no key or passphrase needed, the app sees writes at once), `file` (DTA opens the file), `unreadable` (why, and what to do).
- Databases: `data_db_schema` (tables, columns, keys, row counts), `data_db_query` (SELECT/PRAGMA/WITH; `offset`/`limit` pages with the total; blobs as size and first bytes unless `blobs: full`), `data_db_exec` (INSERT/UPDATE/DELETE/DDL; says how many rows changed; on the app's open Room database Room's observers are told, so screens follow).
- An encrypted (SQLCipher) database the app has not opened in this process is `unreadable`: open the screen that uses it and ask again, or pass `passphrase`.
- `data_prefs_read` / `data_prefs_set` / `data_prefs_remove` work on SharedPreferences (encrypted ones decrypted with the app's own keys) and Preferences DataStore. `set` keeps the key's type unless `type` is given (`string`, `int`, `long`, `float`, `boolean`, `stringSet` as a JSON array; `double` and `bytes` for DataStore). EncryptedSharedPreferences the app has not opened are tried with the default master key; when the app uses its own key alias the answer says to open the screen that uses them first.
- Proto DataStore: `data_proto_read` decodes by field number, or by `type` from the app's protobuf schemas (`network_schema_add`/`sync`); `data_proto_write` replaces the message from JSON with that type.
- Files: `data_files_list`, `data_files_read` (text inline up to 1 MiB, else `output`), `data_files_write` (`text`, `base64` or `input` file), `data_files_mkdir`, `data_files_delete` (folders with everything in them).
- Every write appears on the timeline as source `dta` (`data/write`), so a cause chain can show that a screen changed because DTA wrote something.

## App runtime

- `runtime_activities`: activities with lifecycle state, task, focus, intent and fragments. `runtime_memory` (heap, native, PSS, GCs; `gc: true` runs one first), `runtime_threads` (with stacks; `filter` by name), `runtime_di` (every DI graph — Hilt components, Metro graphs, Dagger components, Koin — with each binding's type, kind singleton/factory/scoped/instance, qualifier and the runtime class of its instance or "not created yet"; for Hilt also what it injected into each live activity and `@HiltViewModel`, `assisted` marking what the caller passed; reading never creates anything). Each binding has an `id` and `dependsOn` (ids of the bindings its instance holds; for injected/assisted, the binding that provided it) — who uses a binding is the reverse.
- `runtime_navigation`: every NavController (Compose NavHost too) with its back stack (top last; route, arguments, lifecycle) and graph (routes, arguments, deep links, start), and Navigation 3 back stacks. `runtime_navigate` (`route` or `uri`) and `runtime_back` move the app; with several hosts pass `host`.
- `runtime_viewmodels` lists live ViewModels with their owner (activity, back-stack entry, Navigation 3 entry) and properties — StateFlow, LiveData, Compose State, fields — as JSON, and SavedStateHandle entries; a public `state` exposed with `asStateFlow()` is one property with its private `_state` (set it as `state`); objects the ViewModel uses (repositories, clients, contexts) are kind `reference`, given by class, not serialized; `editable` ones can be set. `runtime_vm_set` takes the ViewModel `id`, a `path` (`state`, or a field inside a data-class value like `state.coupon`, rebuilt with copy) and a JSON `value` (`"text"`, `3`, `true`, `null`, `{…}`). `runtime_saved_set` sets a SavedStateHandle key. The app's observers see the change.
- `runtime_functions` lists functions the app registered with `DtaState.function(name, description) { args -> … }` (invocable) and AndroidX AppFunctions in the APK (listed only); `runtime_invoke` runs one with string `args` and returns its result.
- Every change appears on the timeline as source `dta`, kind `runtime/write`.

## Network

- `network_list` is one chronological list of HTTP exchanges, WebSocket connections and messages. Filters: `url`, `method`, `status` (`404`, `4xx`, `failed`, `pending`), `kinds`, `sinceMs`/`untilMs`, `bodyContains` (also inside binary bodies), `runs` (`current` = this process only), `intervened`.
- Or give a query line `q`, as a person types it in the plugin: `POST status:4xx host:api.example.test -host:telemetry.example.test token "expired soon"`. `key:value` words filter (`method status host url kind has:intervention body run since:5m until`, bare `GET`/`POST`…, `http`, `ws`; `-key:value` leaves matches out); the other words are searched (case and separators ignored, one typo in words of 5+ letters, numbers exact) leave only the entries holding every word, with `hits` saying where (`allRows: true` keeps every row, hits marked).
- To find where a value appeared, use `network_search` with `q`: the entries holding every word, each hit with its place (url, request/response header, request/response body, payload), span and excerpt — no need to read every body. `notSearched` says what could not be read.
- To follow traffic over time: note the time, act, then call `network_list` with `sinceMs`.
- `network_entry` gives headers, timing, the initiator stack (first app frame marked) and, for a WebSocket connection, its messages.
- `network_body`: text for text bodies and decoded protobuf; for other binary bodies pass `output` (a file path) and read the file. Large bodies come back as a file automatically.
- `redact: true` masks tokens, cookies, passwords and keys (in decoded protobuf too); bodies that cannot be redacted are withheld unless `allowUnredacted`.
- `network_clear` empties the history (all runs).

## WebSocket message formats

Every text message in `network_list` and `network_entry` (and a message body) carries an `envelope`: its `format` (json, json-rpc, graphql-ws, prefix+json, socket.io, phoenix, stomp, signalr), `kind`, `id`, `topic` and `payload`. A reply has the same `id` as the request it answers. When the app has its own protocol and the envelope is missing or wrong, propose a rule from the messages you see:
- `network_format_test` with `urlPattern` (glob over the socket's URL) and a `pattern` (regex with named groups `kind`, `id`, `topic`, `payload`) and/or field paths (`kind`, `idPath`, `topic`, `payload`, e.g. `h.op`) — it says how many of the connection's latest messages it reads, with samples both ways. Nothing is kept.
- When it reads them all and the user agrees, `network_format_add` with the same arguments. `network_format_list` / `network_format_remove` manage rules; they are kept per app across restarts and apply in the plugin and the CLI too.

## Links

- `links_list` lists the links (deep links, App Links, any intent with a URI) that reached an activity of the app: URI, `delivery` (`cold-start`, `created`, `new-intent`, `from-recents`), activity, referrer. Filters: `uri`, `activity`, `delivery`, `runs`, `archive`. `links_entry` gives the query, flags and every extra (or why it could not be read).
- `links_open` opens a URI on the device and says who received it: the app (with the link's id), another app, the chooser, or nothing. Pass `package` to wait for the app's record and, for an https link it did not get, to see its App Links state; `only: true` sends it to the app alone.
- `links_verification` says, per declared https host, whether Android verified it and whether the user allowed it: why a link opens in the browser instead of the app.
- Links the app hands to other apps (browser, store, mail) are not recorded; links it opens in a WebView or a Custom Tab are not either.

## Scripts

`network_script_set` installs JavaScript that runs inside the app for every request, response and WebSocket message. It persists across app restarts until `network_script_clear`. Check `network_script_status` and `network_script_logs` after installing. See [scripting.md](scripting.md) for the API and recipes.

## Pushing WebSocket messages

`network_ws_push` makes the app receive a message on an open WebSocket connection as if the server sent it (`text`, or `base64` for binary; `connection` from `network_list`, optional when only one is open). It is delivered on the library's reading thread when a frame arrives (DTA pings the server for one where the library allows), otherwise from DTA's thread after `waitMs` (default 1000); the result says which. The history shows it as an incoming message marked `injected` — never mistake it for a server message. To answer the app's own messages, or push on a schedule, use `conn.push`, `push` and `every` in the network script.

## Protobuf

OTLP (OpenTelemetry) exports decode automatically. For the app's own protobuf, add the `.proto` files with `network_schema_add` and map a message type to traffic with `network_mapping_add`. See [protobuf.md](protobuf.md).

## Troubleshooting

- "newer than this dta": a newer DTA daemon runs; the user upgrades the CLI or runs `dta daemon stop`.
- Device or connection errors: `daemon_status` shows adb, devices and open app connections.
