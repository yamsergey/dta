# Android Development Tools (ADT)

Tools for analyzing Android projects and generating workspace.json files for Kotlin Language Server integration.

## Installation

Download the latest release from [GitHub Releases](https://github.com/yamsergey/yamsergey.adt/releases).

Extract the archive:
```bash
tar -xzf adt-cli-vX.X.X.tar.gz
```

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