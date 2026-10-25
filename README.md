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
| `dta-sidekick-shim` | Pure-Java hook dispatcher, dexed into the AAR's assets and put on the bootstrap class path by the agent, so boot classes (`java.net.URL`) can call it. Its `onEntry`/`onExit` signatures are a frozen ABI. |

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
    library's status.
  - Every request, connection and outgoing message carries an **initiator**
    stack, app frames marked, `firstAppFrame` set; async OkHttp calls show the
    `enqueue` site, not the dispatcher thread.
  - Bodies of any type are teed to device storage as the app reads them (no
    pre-buffering), up to 32 MB each; listings carry size, type and a ≤256-char
    preview. `network_body` returns wire bytes; the daemon decodes (gzip,
    deflate; br/zstd stated as not decoded) and writes files with sha256.
  - On-device filters across all kinds: `url`, `method`, `status`
    (`404`/`4xx`/`failed`/`pending`), `kinds`, time range, `bodyContains`
    (with match locations), `runs`.
  - History persists across process restarts (100 MB, oldest evicted first);
    entries are labeled by process run; in-flight entries of a dead process read
    `process-ended`. Capture starts before `Application.onCreate` and runs
    without a client (observation, constitution v1.3.0).
  - Opt-in redaction (`redact`), applied by the daemon: credential headers,
    secret/personal JSON/form/query fields; formats it cannot inspect, binary
    included, are withheld unless `allowUnredacted`.
  - Requirements: API 28+ and a debuggable app; otherwise the capability says
    why. Reductions use eight `network-*` degradation codes.

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
aborting); see its `README.dta`.
