# DTA — Development Tools for Android (rewrite)

Ground-up rewrite of [DTA](https://github.com/yamsergey/dta): a toolkit that
gives AI assistants and developers direct access to Android devices. This tree
is being rebuilt feature by feature under a spec-driven process; the proven
prototype lives on the `pre-refined` branch.

## Modules

| Module | Role |
|---|---|
| `dta-protocol` | Pure-JVM wire core: hand-rolled RFC 6455 server half, JSON message envelope, hello-gated RPC sessions, diagnostic methods, and the shared layout tree model + filter. No Android, no third-party transport deps. |
| `dta-daemon` | Host side (library): ADB discovery (`/proc/net/unix` scan), port-forward ownership with startup sweep, `SidekickConnectionManager` (the single connection-lifecycle owner), `SidekickConnection` (JDK WebSocket client), and `LayoutService` (sidekick-vs-uiautomator-fallback decision ladder with honest labeling). |
| `dta-sidekick` | Android AAR embedded in debug builds: `LocalServerSocket` accept loop, AndroidX Startup init, capability registry with live statuses, layout capture (View walk + two-tier Compose: semantics always, ui-tooling-data enrichment when present), and network capture through a JVMTI agent (`libdta-agent.so`, dex rewriting on vendored AOSP slicer). Zero third-party runtime deps — Compose and OkHttp are `compileOnly` (constitution I). |
| `dta-sidekick-rhino` | Rhino 1.7.15 (MPL 2.0) relocated to `io.yamsergey.dta.vendor.rhino` — the network-script engine; consumers never see `org.mozilla`. |
| `dta-client` | Client of the daemon process (feature 008): `DaemonLauncher` (find, start, replace or refuse the daemon by version; recover stale state) and `DaemonClient` (JDK WebSocket, same JSON-RPC envelope as the sidekick channel, live events). Shared by the CLI, `dta mcp` and the coming Android Studio plugin. |
| `dta-cli` | The `dta` command (Clikt) and `dta mcp` (MCP Java SDK, stdio), both generated from the operation catalog in `dta-protocol` (`ops/`). Ships as a distribution (`installDist`); the daemon process runs from the same `lib/`. |
| `dta-sidekick-shim` | Pure-Java hook dispatcher, dexed into the AAR's assets and put on the bootstrap class path by the agent, so boot classes (`java.net.URL`) can call it. Its `onEntry`/`onExit` signatures are a frozen ABI. |

## Install and use (feature 008)

Prerequisites: Java 21+, adb (found through `DTA_ADB`, `ANDROID_HOME`, `ANDROID_SDK_ROOT` or `PATH`), an app built with `dta-sidekick` in its debug build.

```
scripts/install-local.sh          # builds and links ~/.local/bin/dta (or $DTA_BIN_DIR)
# or: brew install yamsergey/packages/dta-cli      (stable)
#     brew install yamsergey/packages/dta-cli-dev  (development builds)
```

```
dta devices                       # connected devices
dta apps                          # running apps with DTA inside, and the foreground app
dta layout --text Pay             # screen tree (View + Compose), filtered
dta network list --status 4xx     # captured HTTP + WebSocket traffic
dta network entry <id>            # headers, timing, initiator
dta network body <id> -o body.bin # a body as text or to a file
dta network watch                 # follow traffic live (Ctrl-C ends the watch, not the daemon)
dta network script set delay.js   # network script (status, logs, clear)
dta network schema add shop protos/ && dta network mapping add --url-pattern '*/api/order' --part request --schema shop --type shop.v1.Order
dta daemon status | stop
```

- One device is chosen automatically; with several, pass `-d <serial>`. Omit `-a` to use the foreground app. Every automatic choice is printed on stderr.
- `--json` prints the full result (`{selection, result}`) and nothing else on stdout.
- Exit codes: 0 ok, 2 usage, 3 not found / not available (no device, no DTA in the app, unknown entry), 4 version conflict, 1 internal.

**Agents.** `dta mcp` serves the same operations as MCP tools over stdio (results are the `--json` documents, bounded at 64 KB by default — `DTA_MCP_MAX_BYTES` — with what was cut stated). In Claude Code, install the plugin from this repository: `/plugin marketplace add <path to sources>` then `/plugin install dta@dta` (see `plugins/dta/README.md`).

**The daemon.** Every command and the MCP server talk to one background daemon per user, started on demand from the installed distribution and shared by all clients. It listens on loopback only and requires the owner-only token in `$DTA_HOME/daemon/token` (`DTA_HOME` defaults to `~/.dta`). A newer `dta` replaces an older daemon and says so; an older `dta` refuses a newer daemon (exit 4) instead of replacing it back. It ignores Ctrl-C and terminal hang-ups, has no idle shutdown, and stops with `dta daemon stop`, `SIGTERM`, or replacement. Logs: `$DTA_HOME/daemon/daemon.log`, `$DTA_HOME/mcp.log`.

## Capabilities

- **channel** (feature 001): the duplex link — discovery, connect, version handshake, requests/events/binary, churn survival.
- **layout** (feature 002): `layout_tree` — merged Views + Compose tree across windows; two-tier Compose (semantics tier always, tooling-enrichment tier best-effort with visible degradation); labeled uiautomator fallback; on-device AND-combined filters. All degradations enumerable and surfaced, never silent.
- **layout detail levels** (feature 003): `layout_tree` takes `params.detail`:
  - `semantic` (default): the feature-002 tree.
  - `layout`: adds layout-only composables (Row/Column/Box/…) as `ComposeNode`s
    with `provenance:"tooling"`, placed under their actual layout parent with
    bounds, name and call-site source. Zero-area or unplaced ones are kept with
    `visible:false`.
  - `composition`: adds a root→node `callChain` of user composables on every
    Compose node, spanning subcompositions. `frameworkFrames:true` adds
    framework frames marked `library`.

  Every capture states `detail {requested, delivered}`. Levels degrade per
  Compose host, never silently:
  - `compose-detail-reduced` (with `compose-enrichment-failed` when that host's
    tooling read threw);
  - `compose-source-info-missing`;
  - `filter-needs-richer-detail` for a type filter that finds nothing below
    `layout`.

  The `layout` capability lists the servable `levels`. Tooling is read with
  ui-tooling-data `mapTree` (reflective); framework vs. user frames are told
  apart by call-site package hash.

- **network** (feature 004): HTTP and WebSocket traffic in **one** list —
  `network_list`, `network_entry`, `network_body`, `network_clear`, and live
  `network.entry` events. Entry kinds: `http`, `ws-connection`, `ws-message`.
  - Captured libraries: OkHttp 3/4/5 (network interceptor, plus failures before
    a connection such as refused/DNS/TLS), `HttpURLConnection`, OkHttp
    WebSocket, nv-websocket-client, Java-WebSocket. The capability lists each
    library's status, gRPC included: detected and listed as not captured (it
    has its own transport), so an empty list is not mistaken for no traffic.
    Presence alone does not degrade the capability.
  - Every request, connection and outgoing message carries an **initiator**
    stack, app frames marked, `firstAppFrame` set; async OkHttp calls show the
    `enqueue` site, not the dispatcher thread.
  - Bodies of any type are teed to device storage as the app reads them (no
    pre-buffering), up to 32 MB each; listings carry size, type and a ≤256-char
    preview. `network_body` returns wire bytes; the daemon decodes (gzip,
    deflate; br/zstd stated as not decoded) and writes files with sha256.
  - On-device filters across all kinds: `url`, `method`, `status`
    (`404`/`4xx`/`failed`/`pending`), `kinds`, time range, `bodyContains`
    (with match locations; binary bodies are searched as bytes for the needle's
    UTF-8 and marked `*-binary`), `runs`.
  - History persists across process restarts (100 MB, oldest evicted first);
    entries are labeled by process run; in-flight entries of a dead process read
    `process-ended`. Capture starts before `Application.onCreate` and runs
    without a client (observation, constitution v1.3.0).
  - Opt-in redaction (`redact`), applied by the daemon: credential headers,
    secret/personal JSON/form/query fields, key/value pairs by their key (OTLP
    attributes); decoded protobuf is redacted as JSON; formats it cannot inspect,
    undecodable binary included, are withheld unless `allowUnredacted`. Off by
    default.
  - Requirements: API 28+ and a debuggable app; otherwise the capability says
    why. Reductions use eight `network-*` degradation codes.

- **network script** (feature 005): one JavaScript script per app intervenes in
  the traffic network capture sees — delay, rewrite, mock, drop, throttle, for
  HTTP (OkHttp, `HttpURLConnection`) and WebSocket (all three libraries).
  - Methods: `network_script_set` (replace; rejected with line/column if it
    does not compile), `network_script_clear`, `network_script_status`,
    `network_script_logs` (`since`); daemon `networkScriptSet/Clear/Status/Logs`.
  - Handlers `onRequest(req)`, `onResponse(res, req)`, `onWsSend(msg, conn)`,
    `onWsReceive(msg, conn)`; helpers `respond({...})` (mock), `drop()`,
    `sleep(ms)`, `throttle(bytesPerSecond)`, `state`, `log`. Return the mutated
    object to change it; `null` is an error, never a silent drop. Bodies are read
    only when the script calls `text()`/`bytes()`/`json()`. Snippets for the
    common cases: `../specs/005-network-scripting/contracts/network-script.md` §8.
  - The script runs on the device, persists across restarts until cleared, and
    applies from the first request of a new process, before any client connects
    (constitution v1.4.0). It is always visible: the `network` capability's
    `script`, an `intervention` record on every entry it touched (handlers,
    changed fields, delay, throttle, mocked/dropped, errors), the app-side request
    / response / message next to the wire view (`app-request`, `app-response`,
    `app-message` body parts), the `intervened` filter, and a device-log line at
    every app start.
  - A script error never breaks traffic: the original passes and the error is
    logged with the entry. Limits: one script per app; handlers run synchronously
    on the calling thread; while a script defines `onRequest`, `HttpURLConnection`
    request bodies are buffered (no streaming); for OkHttp the script handles each
    call once, while each network attempt is captured and marked.
  - Known limitation (also of capture): a shaded or separately loaded copy of a
    supported library is neither captured nor scripted.

- **protobuf bodies** (feature 006): binary protobuf bodies read as JSON. All
  decoding is in the daemon (host); nothing is added to the app.
  - Built in: OpenTelemetry exports over OTLP/HTTP (`POST …/v1/traces|metrics|logs`
    with a protobuf content type, gzip undone) come back as the official
    OTLP/JSON — hex trace/span ids, enums as integers.
  - Your own protobuf: `networkSchemaAdd(pkg, name, SchemaInput.ProtoSources(…))`
    (`.proto` sources, compiled on the JVM — no `protoc`) or
    `SchemaInput.DescriptorSet(bytes)` (`protoc --include_imports
    --descriptor_set_out`), then `networkMappingAdd(pkg, BodyMappingSpec(urlPattern,
    part, schema, type, method?, direction?))`. Globs match the full URL; a WebSocket
    message matches by its connection's URL (e.g. `ws://*/chat`).
    User mappings win over OTLP; the most literal pattern wins, then the most
    recent. Stored per app under `~/.dta/schemas/<package>/` (`DtaDaemon(dataDir)`).
    `networkSchemas`, `networkSchemaRemove` (returns the mappings now broken),
    `networkMappings` (built-in rules included), `networkMappingRemove`.
  - Every body result carries `decoding`: `decoded`, `decoded-with-warnings`
    (unknown fields with their paths, implausible ids), `not-decoded` with the
    reason (does not parse, body incomplete, mapping broken), or
    `not-applicable`. Decoding is never claimed when it did not happen; raw bytes
    stay available through `BodyOutput.File`; `File(structured = true)` writes
    the JSON. Text, file and redaction share one decoding.
  - Not supported: `extend` and proto2 groups in `.proto` sources (rejected with
    their location), schemaless decoding, gRPC traffic.

## Protocol

One duplex WebSocket per (device, app) over
`adb forward tcp:0 localabstract:dta_sidekick_<package>`: correlated
requests/responses, ordered push events, id-tagged binary frames, mandatory
version handshake. The authoritative contract lives with the feature spec:
`../specs/001-sidekick-daemon-channel/contracts/` (wire-protocol.md,
discovery.md).

## Build & test

```bash
./gradlew build                 # everything incl. the sidekick AAR
./gradlew test                  # deviceless suite (protocol, daemon, sidekick,
                                # shim) — no device/emulator needed
```

End-to-end verification runs from the workspace root (outside this repo):
`../harness` drives a local emulator with the `../fixtures/channel-fixture`
app. See `../specs/001-sidekick-daemon-channel/quickstart.md`.

## Governance

Development follows the project constitution
(`../.specify/memory/constitution.md`): strict TDD, no silent degradation,
one lifecycle owner per concern, deviceless-testable cores, and the AS/AOSP
sources as prior art for framework internals.

Third-party code: `dta-sidekick/src/main/cpp/third_party/slicer` is AOSP
dexter/slicer (Apache 2.0), vendored with one patch (checks throw instead of
aborting); see its `README.dta`. `dta-sidekick-rhino` repackages Mozilla Rhino
(MPL 2.0, license kept as `META-INF/rhino-LICENSE.txt`). The control entry hook
(`cpp/control_entry_hook.cc`) follows Android Studio's `ArrayParamsEntryHook`
(Apache 2.0). The daemon uses protobuf-java (BSD-3-Clause), the OpenTelemetry
protocol descriptors `opentelemetry-proto` (Apache 2.0) and Square Wire's `.proto`
parser `wire-schema` (Apache 2.0); none of them ship in the app.
