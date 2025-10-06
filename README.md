# Android Development Tools (ADT)

[![](https://jitpack.io/v/yamsergey/yamsergey.adt.svg)](https://jitpack.io/#yamsergey/yamsergey.adt)

Tools for analyzing Android projects and generating workspace.json files for Kotlin Language Server integration.

## Installation

### ATD CLI

Command line tool that utilise ADT libraries for specific tasks (e.g. `workspace.json` generation, android project gradle metatdata review, etc.)

#### Homebrew

```bash
brew tap yamsergey/adt
brew install adt-cli
```

#### APT

TODO: In progress

#### From sources

Download the latest release from [GitHub Releases](https://github.com/yamsergey/yamsergey.adt/releases).

Extract the archive:
```bash
tar -xzf adt-cli-1.0.0.tar.gz
```

### Jar libraries

All libraries piblished via jitpack, for details take a look at appropriate library README.md.

**Requirements:** Java 21+

## Usage

Generate workspace.json for Kotlin LSP:
```bash
adt-cli/bin/adt-cli workspace /path/to/android/project \
  --output /path/to/android/project/workspace.json
```

Analyze Android project structure:
```bash
adt-cli/bin/adt-cli resolve /path/to/android/project \
  --workspace --output project-analysis.json
```

List build variants:
```bash
adt-cli/bin/adt-cli resolve /path/to/android/project --variants
```

## Modules

- **[adt-cli](adt-cli/)** - Unified CLI for project analysis and workspace generation
- **[workspace-kotlin](workspace-kotlin/)** - Workspace converter library
- **[tools-android](tools-android/)** - Core analysis library

See individual module READMEs for detailed documentation.
