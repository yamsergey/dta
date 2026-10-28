# DTA plugin for Claude Code

Gives Claude Code the DTA tools (`layout_tree`, `network_list`, `network_entry`, `network_body`, network scripts, protobuf schemas and mappings) and a skill that explains how to use them.

## Prerequisites

- Java 21 or newer on the machine.
- `adb` (Android SDK platform-tools): found through `DTA_ADB`, `ANDROID_HOME`, `ANDROID_SDK_ROOT` or `PATH`.
- The `dta` command on `PATH`:
  - from a checkout: `scripts/install-local.sh` (installs to `~/.local/bin/dta`);
  - or from the package manager: `brew install <tap>/dta-cli` (stable) or `<tap>/dta-cli-dev` (development builds).
- The app under inspection built with DTA inside (debug build).

## Install

```
/plugin marketplace add <path to this repository's sources/ directory>
/plugin install dta@dta
```

Start a new session; `/mcp` lists the `dta` server and its tools.

## When the server does not start

- `dta: command not found` — install the CLI as above and make sure its directory is on `PATH` for the shell Claude Code starts from.
- The tools answer "newer than this dta" — a newer DTA daemon runs on this machine; upgrade the CLI, or run `dta daemon stop`.
- The tools answer "DTA needs Java 21" — install Java 21 and restart Claude Code.
- Anything else: `dta daemon status` and the logs in `~/.dta/mcp.log` and `~/.dta/daemon/daemon.log`.
