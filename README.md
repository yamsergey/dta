# Development Tools for Android (DTA)

> Work is in progress, bugs expected, nothing has been finalised.

[![Maven Central](https://img.shields.io/maven-central/v/io.github.yamsergey/dta-sidekick)](https://central.sonatype.com/artifact/io.github.yamsergey/dta-sidekick)
[![Snyk Security](https://github.com/yamsergey/dta/actions/workflows/snyk-security.yml/badge.svg)](https://github.com/yamsergey/dta/actions/workflows/snyk-security.yml)

A terminal-first toolkit that gives AI assistants direct access to Android devices through the Model Context Protocol. Your coding agent can see the screen, read network traffic, mock responses, and interact with the app — without opening Android Studio.

[A bit of an overview of use cases](https://medium.com/@yamsergey/debugging-android-apps-with-ai-how-i-replaced-android-studios-inspector-with-an-mcp-server-848d17cbc989)

## Quick Start

### Install CLI

```bash
brew tap yamsergey/packages
brew install dta-cli
```

**Requirements:** Java 21+

### Add Sidekick to your app (debug builds only)

```gradle
dependencies {
    debugImplementation 'io.github.yamsergey:dta-sidekick:0.9.27'
}
```

Sidekick auto-initializes via AndroidX Startup — no code changes needed. It starts recording HTTP, WebSocket, and Chrome Custom Tab traffic the moment your app launches.

### Connect your AI assistant

#### Cursor

[![Add dta-mcp to Cursor](https://cursor.com/deeplink/mcp-install-dark.svg)](cursor://anysphere.cursor-deeplink/mcp/install?name=dta-mcp&config=eyJjb21tYW5kIjogImR0YS1jbGkiLCAiYXJncyI6IFsibWNwIl19)

#### Claude Desktop

Add to `~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "dta": {
      "command": "dta-cli",
      "args": ["mcp"]
    }
  }
}
```

## What Can Your Agent Do?

### Screen Analysis

DTA returns the real UI hierarchy — actual view classes, bounds, positions, layout parameters, and Compose-specific data. Unlike `adb shell uiautomator dump` which gives a semantic accessibility tree, your agent gets the full picture including content inside WebViews and Chrome Custom Tabs.

### Network Analysis

Sidekick auto-records from app launch — HTTP requests, WebSocket connections, Chrome Custom Tab traffic. No "start recording" button, no missed requests. Your agent can query the full history at any point.

### Mocking

Create mock rules for HTTP and WebSocket from captured data or from scratch. Plug in custom adapters for programmatic control over mock responses:

```kotlin
val httpAdapter = HttpMockAdapter { transaction, proposedResponse ->
    proposedResponse.withBody("""{"message": "custom response"}""")
}

Sidekick.configure(
    SidekickConfig.builder()
        .httpMockAdapter(httpAdapter)
        .build()
)
```

### Verification

The agent can tap, swipe, input text, and press keys — then check the screen state and network traffic to confirm behavior. A full inspect-mock-interact-verify loop.

## Inspector Web Interface

```bash
dta-cli inspector-web
```

Open http://localhost:8080 for visual inspection:

- **Layout Inspector** — UI hierarchy with element selection and filtering
- **Network Inspector** — captured HTTP requests and responses
- **WebSocket Inspector** — WebSocket connections and messages

The inspector enables bi-directional communication between developer and agent — select elements with a click instead of describing them in words.

## Modules

| Module | Description |
|--------|-------------|
| **dta-cli** | Unified CLI interface (includes `mcp` and `inspector-web` commands) |
| **dta-sidekick** | Runtime inspection library (Android AAR) |
| **tools-android** | Core Gradle Tooling API integration |
| **workspace-kotlin** | workspace.json converter for Kotlin Language Server |

## CLI Reference

```bash
dta-cli --help
dta-cli <command> --help
```

## License

Apache License 2.0 — see [LICENSE.txt](LICENSE.txt)
