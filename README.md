# Android Development Tools (ADT)

Tools for analyzing Android projects and generating workspace.json files for Kotlin Language Server integration.

## Installation

Download the latest release from [GitHub Releases](https://github.com/yamsergey/yamsergey.adt/releases).

Extract the archive:
```bash
tar -xzf workspace-kotlin-cli-vX.X.X.tar.gz
```

## Usage

Generate workspace.json for Kotlin LSP:
```bash
workspace-kotlin-cli/bin/workspace-kotlin-cli generate /path/to/android/project \
  --output /path/to/android/project/workspace.json
```

Analyze Android project structure:
```bash
tools-android-cli/bin/tools-android-cli resolve /path/to/android/project \
  --workspace --output project-analysis.json
```

**Requirements:** Java 21+

## Modules

- **[workspace-kotlin-cli](workspace-kotlin-cli/)** - Generate workspace.json for Kotlin LSP
- **[tools-android-cli](tools-android-cli/)** - Analyze Android project structure
- **[workspace-kotlin](workspace-kotlin/)** - Workspace converter library
- **[tools-android](tools-android/)** - Core analysis library

See individual module READMEs for detailed documentation.