# DTA — Development Tools for Android (rewrite)

Ground-up rewrite of [DTA](https://github.com/yamsergey/dta): a toolkit that
gives AI assistants and developers direct access to Android devices. This tree
is being rebuilt feature by feature under a spec-driven process; the proven
prototype lives on the `pre-refined` branch.

## Modules

| Module | Role |
|---|---|
| `dta-protocol` | Pure-JVM wire core: hand-rolled RFC 6455 server half, JSON message envelope, hello-gated RPC sessions, diagnostic methods. No Android, no third-party transport deps. |
| `dta-daemon` | Host side (library): ADB discovery (`/proc/net/unix` scan), port-forward ownership with startup sweep, `SidekickConnectionManager` (the single connection-lifecycle owner), `SidekickConnection` (JDK WebSocket client). |
| `dta-sidekick` | Android AAR embedded in debug builds: `LocalServerSocket` accept loop, AndroidX Startup init, capability registry with live statuses. Zero third-party runtime deps (constitution I). |

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
