# adt-cli

Unified command-line tool for Android project analysis and workspace.json generation.

## Overview

This CLI combines Android project analysis and Kotlin Language Server workspace generation into a single tool, providing easy access to project structure information and LSP integration.

## Installation

### From Release

Download from [GitHub Releases](https://github.com/yamsergey/yamsergey.adt/releases):

```bash
# Extract the archive
tar -xzf adt-cli-vX.X.X.tar.gz

# Or for Windows
unzip adt-cli-vX.X.X.zip
```

### Build from Source

```bash
./gradlew :adt-cli:installDist

# Executable will be at:
# adt-cli/build/install/adt-cli/bin/adt-cli
```

## Commands

### `workspace` - Generate workspace.json

Generate workspace.json for Kotlin Language Server integration.

```bash
adt-cli workspace [PROJECT_PATH] [OPTIONS]
```

**Arguments:**
- `PROJECT_PATH`: Path to Android project directory (default: current directory)

**Options:**
- `--output, -o FILE`: Output file path (default: `workspace.json`)
- `--help, -h`: Show help message
- `--version, -V`: Show version information

**Examples:**

```bash
# Generate workspace.json in project directory
adt-cli workspace /path/to/android/project --output workspace.json

# Generate with custom output path
adt-cli workspace /path/to/project --output /custom/path/workspace.json

# Generate for current directory
adt-cli workspace . --output workspace.json
```

### `resolve` - Analyze project structure

Analyze Android project and output structure information.

```bash
adt-cli resolve [PROJECT_PATH] [OPTIONS]
```

**Arguments:**
- `PROJECT_PATH`: Path to Android project directory (default: current directory)

**Options:**
- `--workspace`: Output project structure with modules and dependencies
- `--variants`: List all build variants
- `--raw`: Output raw Gradle project data (very large)
- `--module NAME`: Filter raw output by module name (comma-separated)
- `--include FIELDS`: Include only specified fields in raw output
- `--exclude FIELDS`: Exclude specified fields from raw output
- `--output, -o FILE`: Save output to file
- `--help, -h`: Show help message

**Examples:**

```bash
# Analyze project structure
adt-cli resolve /path/to/project --workspace --output project-analysis.json

# List build variants
adt-cli resolve /path/to/project --variants --output variants.json

# Get raw data for specific module
adt-cli resolve /path/to/project --raw --module app \
  --exclude tasks,dependencies --output raw-app.json
```

## Requirements

- Java 21 or higher
- Target Android projects must use Gradle 8.0+ and Android Gradle Plugin 8.0+

## Output Formats

### workspace.json

Structured format for Kotlin LSP:
```json
{
  "modules": [...],
  "libraries": [...],
  "sdks": [],
  "kotlinSettings": []
}
```

### Project Analysis (--workspace)

Detailed project structure:
```json
{
  "path": "/path/to/project",
  "name": "ProjectName",
  "modules": [...]
}
```

### Build Variants (--variants)

List of available variants:
```json
[
  {"name": "debug", "displayName": "debug"},
  {"name": "release", "displayName": "release"}
]
```

## Troubleshooting

**Issue: Command not found**
- Add the bin directory to your PATH or use the full path

**Issue: Failed to resolve project**
- Verify the project path is correct
- Ensure build.gradle/build.gradle.kts exists
- Try running `./gradlew build` in the project first

**Issue: OutOfMemoryError**
- Increase heap size: `export JAVA_OPTS="-Xmx4g"`

## Related

- [workspace-kotlin](../workspace-kotlin/README.md) - Workspace converter library
- [tools-android](../tools-android/README.md) - Core analysis library
- [Kotlin Language Server](https://github.com/fwcd/kotlin-language-server)
