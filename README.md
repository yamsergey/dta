# Development Tools for Android (DTA)

A CLI toolkit for Android project analysis, device inspection, and runtime debugging.

## Installation

### Homebrew (macOS)

```bash
brew tap yamsergey/adt
brew install dta-cli
```

### From Source

```bash
git clone https://github.com/yamsergey/yamsergey.dta.git
cd yamsergey.dta
./gradlew :dta-cli:installDist
# Binary at: dta-cli/build/install/dta-cli/bin/dta-cli
```

**Requirements:** Java 21+

## Commands

### `resolve` - Project Analysis

Analyze Android project structure and dependencies via Gradle Tooling API.

```bash
# Get workspace structure (modules, dependencies, source roots)
dta-cli resolve /path/to/project --workspace

# Save to file
dta-cli resolve /path/to/project --workspace --output analysis.json

# List build variants
dta-cli resolve /path/to/project --variants

# Extract raw Gradle data (large output)
dta-cli resolve /path/to/project --raw --output gradle-data.json
```

### `workspace` - IDE Integration

Generate `workspace.json` for Kotlin Language Server integration with editors like Neovim, VSCode, or Emacs.

```bash
# Generate workspace.json in project root
dta-cli workspace /path/to/project

# Custom output location
dta-cli workspace /path/to/project --output /path/to/workspace.json
```

### `drawable` - Asset Conversion

Convert Android vector drawable XML to PNG images at various densities.

```bash
# Convert to PNG at default density
dta-cli drawable icon.xml -o icon.png

# Specify density (mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi)
dta-cli drawable icon.xml -o icon.png --density xxhdpi

# Custom dimensions
dta-cli drawable icon.xml -o icon.png --width 48 --height 48
```

### `inspect` - Device Inspection

Inspect connected Android devices without requiring app modifications.

#### Layout Hierarchy

Dump the current UI view hierarchy from any Android app.

```bash
# JSON format (recommended for programmatic use)
dta-cli inspect layout --format json -o hierarchy.json

# XML format
dta-cli inspect layout --format xml -o hierarchy.xml

# Compressed format (faster, less detail)
dta-cli inspect layout --compressed -o hierarchy.xml

# Target specific device
dta-cli inspect layout -d emulator-5554 --format json
```

#### Screenshot

Capture device screenshot.

```bash
dta-cli inspect screenshot -o screen.png

# From specific device
dta-cli inspect screenshot -d emulator-5554 -o screen.png
```

#### Logcat

Capture logcat logs with filtering options.

```bash
# Last 500 lines
dta-cli inspect logcat --lines 500 -o logcat.txt

# Filter by priority (V, D, I, W, E, F)
dta-cli inspect logcat --priority W -o errors.txt

# Filter by tag
dta-cli inspect logcat --tag MyApp -o app.txt
```

#### Compose Hierarchy

Dump Compose UI hierarchy (requires `dta-sidekick` in the app).

```bash
# Get full UI tree
dta-cli inspect compose com.example.app

# Filter by text content (recommended to reduce output)
dta-cli inspect compose com.example.app --text "Login"

# Filter by composable type
dta-cli inspect compose com.example.app --type Button

# Combine filters
dta-cli inspect compose com.example.app --text "Submit" --type Button

# Save to file
dta-cli inspect compose com.example.app -o tree.json
```

#### Packages

List debuggable packages on device.

```bash
# List debuggable third-party packages
dta-cli inspect packages

# Include system packages
dta-cli inspect packages --all

# Output as JSON
dta-cli inspect packages --json
```

## DTA Sidekick

Runtime inspection library for Android apps. Add to your debug builds for Compose UI inspection, network monitoring, and WebSocket tracking.

### Setup

```gradle
dependencies {
    debugImplementation 'com.github.yamsergey.yamsergey.dta:dta-sidekick:1.0.8'
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

## MCP Server

Start the Model Context Protocol server for integration with AI assistants.

```bash
# Start MCP server (uses stdin/stdout)
dta-cli mcp
```

### Claude Desktop Configuration

Add to `~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "dta": {
      "command": "/path/to/dta-cli",
      "args": ["mcp"]
    }
  }
}
```

### Available Tools

| Tool | Description |
|------|-------------|
| `list_devices` | List connected Android devices |
| `list_apps` | List apps with sidekick installed |
| `screenshot` | Capture device screenshot |
| `tap` | Tap at screen coordinates |
| `swipe` | Swipe gesture |
| `input_text` | Input text |
| `press_key` | Press key (BACK, HOME, etc.) |
| `compose_tree` | Get Compose UI hierarchy (with filters) |
| `network_requests` | List captured HTTP requests |
| `network_request` | Get request details |
| `websocket_connections` | List WebSocket connections |
| `websocket_connection` | Get connection details with messages |

## Web Inspector

Start the web-based inspector for visual UI inspection.

```bash
# Start on default port (8080)
dta-cli inspector-web

# Start on custom port
dta-cli inspector-web --port 3000
```

Open http://localhost:8080 to access:
- **Compose Inspector** - Visual UI hierarchy with element selection and filtering
- **Network Inspector** - Monitor HTTP requests/responses
- **WebSocket Inspector** - Monitor WebSocket connections and messages

## Modules

| Module | Description |
|--------|-------------|
| **dta-cli** | Unified CLI interface |
| **dta-mcp** | MCP server for AI assistant integration |
| **dta-inspector-web** | Web-based inspector (Spring Boot + Vue.js) |
| **dta-sidekick** | Runtime inspection library (Android AAR) |
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
    debugImplementation 'com.github.yamsergey.yamsergey.dta:dta-sidekick:1.0.8'

    // For project analysis
    implementation 'com.github.yamsergey.yamsergey.dta:tools-android:1.0.8'
}
```

## License

MIT
