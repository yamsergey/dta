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
| `dta-sidekick` | Android AAR embedded in debug builds: `LocalServerSocket` accept loop, AndroidX Startup init, capability registry with live statuses, and layout capture (View walk + two-tier Compose: semantics always, ui-tooling-data enrichment when present). Zero third-party runtime deps — Compose is `compileOnly` (constitution I). |

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
./gradlew :dta-protocol:test :dta-daemon:test :dta-sidekick:testDebugUnitTest
                                # deviceless suite — no device/emulator needed
```

End-to-end verification runs from the workspace root (outside this repo):
`../harness` drives a local emulator with the `../fixtures/channel-fixture`
app. See `../specs/001-sidekick-daemon-channel/quickstart.md`.

## Governance

Development follows the project constitution
(`../.specify/memory/constitution.md`): strict TDD, no silent degradation,
one lifecycle owner per concern, deviceless-testable cores, and the AS/AOSP
sources as prior art for framework internals.
