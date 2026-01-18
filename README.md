# Android Development Tools (ADT)

A CLI toolkit for Android project analysis, device inspection, and runtime debugging.

## Installation

### Homebrew (macOS)

```bash
brew tap yamsergey/adt
brew install adt-cli
```

### From Source

```bash
git clone https://github.com/yamsergey/yamsergey.adt.git
cd yamsergey.adt
./gradlew :adt-cli:installDist
# Binary at: adt-cli/build/install/adt-cli/bin/adt-cli
```

**Requirements:** Java 21+

## Commands

### `resolve` - Project Analysis

Analyze Android project structure and dependencies via Gradle Tooling API.

```bash
# Get workspace structure (modules, dependencies, source roots)
adt-cli resolve /path/to/project --workspace

# Save to file
adt-cli resolve /path/to/project --workspace --output analysis.json

# List build variants
adt-cli resolve /path/to/project --variants

# Extract raw Gradle data (large output)
adt-cli resolve /path/to/project --raw --output gradle-data.json
```

### `workspace` - IDE Integration

Generate `workspace.json` for Kotlin Language Server integration with editors like Neovim, VSCode, or Emacs.

```bash
# Generate workspace.json in project root
adt-cli workspace /path/to/project

# Custom output location
adt-cli workspace /path/to/project --output /path/to/workspace.json
```

### `drawable` - Asset Conversion

Convert Android vector drawable XML to PNG images at various densities.

```bash
# Convert to PNG at default density
adt-cli drawable icon.xml -o icon.png

# Specify density (mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi)
adt-cli drawable icon.xml -o icon.png --density xxhdpi

# Custom dimensions
adt-cli drawable icon.xml -o icon.png --width 48 --height 48
```

### `inspect` - Device Inspection

Inspect connected Android devices without requiring app modifications.

#### Layout Hierarchy

Dump the current UI view hierarchy from any Android app.

```bash
# JSON format (recommended for programmatic use)
adt-cli inspect layout --format json -o hierarchy.json

# XML format
adt-cli inspect layout --format xml -o hierarchy.xml

# Compressed format (faster, less detail)
adt-cli inspect layout --compressed -o hierarchy.xml

# Target specific device
adt-cli inspect layout -d emulator-5554 --format json
```

#### Screenshot

Capture device screenshot.

```bash
adt-cli inspect screenshot -o screen.png

# From specific device
adt-cli inspect screenshot -d emulator-5554 -o screen.png
```

#### Logcat

Capture logcat logs with filtering options.

```bash
# Last 500 lines
adt-cli inspect logcat --lines 500 -o logcat.txt

# Filter by priority (V, D, I, W, E, F)
adt-cli inspect logcat --priority W -o errors.txt

# Filter by tag
adt-cli inspect logcat --tag MyApp -o app.txt
```

#### Compose Hierarchy

Dump Compose UI hierarchy (requires `adt-sidekick` in the app).

```bash
# Unified tree (recommended)
adt-cli inspect compose com.example.app --tree -o compose-tree.json

# Semantics tree
adt-cli inspect compose com.example.app --semantics -o semantics.json

# Filter by composable name
adt-cli inspect compose com.example.app --tree --composable Button
```

#### Packages

List debuggable packages on device.

```bash
# List debuggable third-party packages
adt-cli inspect packages

# Include system packages
adt-cli inspect packages --all

# Output as JSON
adt-cli inspect packages --json
```

## ADT Sidekick

Runtime inspection library for Android apps. Add to your debug builds for Compose UI inspection, network monitoring, and WebSocket tracking.

### Setup

```gradle
dependencies {
    debugImplementation 'com.github.yamsergey.yamsergey.adt:adt-sidekick:2.0.0'
}
```

The library auto-initializes via AndroidX Startup. No code changes needed.

### Sidekick REST API

When the app runs, sidekick exposes a REST API on port 8642:

```bash
# Setup port forwarding
adb forward tcp:8642 tcp:8642

# Health check
curl http://localhost:8642/health

# Get Compose UI tree
curl http://localhost:8642/compose/tree

# List HTTP requests
curl http://localhost:8642/network/requests

# List WebSocket connections
curl http://localhost:8642/websocket/connections

# Get WebSocket connection details with messages
curl http://localhost:8642/websocket/connections/{id}
```

### Supported Libraries

| Type | Libraries |
|------|-----------|
| HTTP | OkHttp 3.x/4.x, HttpURLConnection |
| WebSocket | OkHttp WebSocket, Java-WebSocket, NV-WebSocket |

### Configuration (Optional)

```kotlin
// In Application.onCreate() or via ContentProvider
Sidekick.configure(
    SidekickConfig.builder()
        .enableDebugLogging()  // Enable verbose logging (disabled by default)
        .build()
)
```

## ADT Inspector

Visual web UI for inspecting Android apps. Install via pipx:

```bash
pipx install adt-inspector
adt-inspector
```

Open http://localhost:8080 to access:
- **Compose Inspector** - Visual UI hierarchy with element selection
- **Network Inspector** - Monitor HTTP requests/responses
- **WebSocket Inspector** - Monitor WebSocket connections and messages

## Modules

| Module | Description |
|--------|-------------|
| **adt-cli** | Unified CLI interface |
| **adt-sidekick** | Runtime inspection library (Android AAR) |
| **adt-mcp** | Python package with adt-inspector web UI and MCP server |
| **tools-android** | Core Gradle Tooling API integration |
| **workspace-kotlin** | Workspace.json converter |

## Library Usage

All libraries are published via JitPack:

```gradle
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    // For runtime inspection in debug builds
    debugImplementation 'com.github.yamsergey.yamsergey.adt:adt-sidekick:1.0.8'

    // For project analysis
    implementation 'com.github.yamsergey.yamsergey.adt:tools-android:1.0.8'
}
```

## License

MIT
