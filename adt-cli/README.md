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

### `inspect` - Inspect Android devices and applications

Parent command for various device and application inspection features.

#### `inspect screenshot` - Capture screenshot

Capture a screenshot from a connected Android device or emulator.

```bash
adt-cli inspect screenshot [OUTPUT] [OPTIONS]
```

**Arguments:**
- `OUTPUT`: Output PNG file path (optional, default: screenshot-TIMESTAMP.png)

**Options:**
- `--output, -o FILE`: Output PNG file path (alternative to positional argument)
- `--device, -d SERIAL`: Device serial number (if not specified, uses first available device)
- `--timeout SECONDS`: Timeout in seconds for ADB commands (default: 30)
- `--adb-path PATH`: Path to ADB executable (if not specified, uses 'adb' from PATH)

**Examples:**

```bash
# Capture with auto-generated filename
adt-cli inspect screenshot

# Save to specific file
adt-cli inspect screenshot screenshot.png

# Or use -o flag
adt-cli inspect screenshot -o screenshot.png

# Capture from specific device
adt-cli inspect screenshot -d emulator-5554 -o screen.png

# Specify custom ADB path
adt-cli inspect screenshot --adb-path /custom/path/to/adb -o screen.png
```

**Output:**
- PNG image file
- Automatically detects and reports image dimensions
- Timestamped filenames when no output path specified (format: screenshot-YYYYMMDD-HHMMSS.png)

**Requirements:**
- Android SDK with ADB installed and in PATH (or use `--adb-path`)
- Connected Android device or running emulator
- Device must be in unlocked state with screen on

#### `inspect layout` - Dump UI layout hierarchy

Dump the current UI layout hierarchy from a connected Android device using UIAutomator. Supports both XML and JSON output formats, with JSON being optimized for coding agents and automation tools.

```bash
adt-cli inspect layout [OPTIONS]
```

**Options:**
- `--output, -o FILE`: Output file path (if not specified, prints to stdout)
- `--format, -f FORMAT`: Output format: `xml` (default) or `json` (agent-friendly)
- `--device, -d SERIAL`: Device serial number (if not specified, uses first available device)
- `--compressed`: Use compressed hierarchy format (faster but less detailed)
- `--pretty`: Pretty-print JSON output (default: true)
- `--timeout SECONDS`: Timeout in seconds for ADB commands (default: 30)
- `--adb-path PATH`: Path to ADB executable (if not specified, uses 'adb' from PATH)

**Examples:**

```bash
# Dump as JSON (best for coding agents)
adt-cli inspect layout --format json -o hierarchy.json

# Dump as XML (default)
adt-cli inspect layout -o hierarchy.xml

# Print JSON to stdout for piping
adt-cli inspect layout --format json | jq '.children[0].text'

# Dump from specific device
adt-cli inspect layout -d emulator-5554 --format json

# Use compressed format (faster, less detail)
adt-cli inspect layout --compressed -o hierarchy.xml

# Specify custom ADB path
adt-cli inspect layout --adb-path /custom/path/to/adb --format json
```

**JSON Output Structure:**

The JSON format provides a structured, easy-to-parse representation:

```json
{
  "index": 0,
  "className": "android.widget.Button",
  "packageName": "com.example.app",
  "text": "Click Me",
  "resourceId": "com.example.app:id/button",
  "contentDesc": "Submit button",
  "bounds": {
    "left": 100,
    "top": 200,
    "right": 300,
    "bottom": 250,
    "width": 200,
    "height": 50,
    "centerX": 200,
    "centerY": 225
  },
  "properties": {
    "clickable": true,
    "enabled": true,
    "focused": false
  },
  "children": [...]
}
```

**Benefits for Coding Agents:**
- Structured data with typed fields
- Easy to query and filter
- Computed properties (width, height, centerX, centerY)
- No XML parsing required
- Works with JSON tools like `jq`

**Requirements:**
- Android SDK with ADB installed and in PATH (or use `--adb-path`)
- Connected Android device or running emulator
- Device must be in unlocked state with screen on

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
- For `inspect` commands: Android SDK with ADB installed

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

**Issue: ADB not found (inspect commands)**
- Install Android SDK and ensure ADB is in PATH
- Or use `--adb-path` to specify ADB location
- Verify with: `adb version`

**Issue: No device connected (inspect commands)**
- Connect an Android device via USB or start an emulator
- Enable USB debugging on the device
- Verify with: `adb devices`

**Issue: Layout dump failed**
- Ensure device screen is on and unlocked
- Check device has sufficient storage space
- Try running manually: `adb shell uiautomator dump`

## Related

- [workspace-kotlin](../workspace-kotlin/README.md) - Workspace converter library
- [tools-android](../tools-android/README.md) - Core analysis library
- [Kotlin Language Server](https://github.com/fwcd/kotlin-language-server)
