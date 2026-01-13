# ADT Python Tools

Python components for Android Development Tools:
- **adt-mcp**: MCP server exposing ADT tools for coding agents
- **adt-inspector**: NiceGUI-based inspector UI

## Installation

```bash
cd adt-python
pip install -e .
```

## MCP Server

The MCP server provides tools for:
- Device management (`list_devices`, `screenshot`, `tap`, `swipe`)
- Compose UI inspection (`compose_tree`, `compose_select`)
- Network monitoring (`network_requests`, `websocket_connections`)
- Selection sync with inspector UI (`get_selection`, `set_selection`)

### Usage with Claude Code

Add to your Claude Code MCP config:

```json
{
  "mcpServers": {
    "adt": {
      "command": "python",
      "args": ["-m", "adt_mcp.server"],
      "cwd": "/path/to/adt-python"
    }
  }
}
```

Or if installed:

```json
{
  "mcpServers": {
    "adt": {
      "command": "adt-mcp"
    }
  }
}
```

### Available Tools

| Tool | Description |
|------|-------------|
| `list_devices` | List connected devices/emulators |
| `list_apps` | List apps with sidekick installed |
| `screenshot` | Capture device screenshot |
| `tap` | Tap at coordinates |
| `swipe` | Swipe gesture |
| `input_text` | Enter text |
| `press_key` | Press key (BACK, HOME, etc.) |
| `compose_tree` | Get Compose UI hierarchy |
| `compose_select` | Hit-test at coordinates |
| `network_requests` | List HTTP requests |
| `network_request` | Get request details |
| `websocket_connections` | List WebSocket connections |
| `websocket_connection` | Get connection details |
| `get_selection` | Get selected element/request |
| `set_selection` | Set selected element/request |

## Inspector UI

NiceGUI-based UI for visual inspection:

```bash
adt-inspector
# Or:
python -m adt_inspector.app
```

Opens at http://127.0.0.1:8080

Features:
- **Layout tab**: Compose UI tree viewer with element selection
- **Network tab**: HTTP request list with details
- **WebSocket tab**: WebSocket connection viewer

## Requirements

- Python 3.11+
- ADB in PATH
- Apps must have ADT Sidekick library integrated

## How It Works

1. Sidekick library runs inside the Android app, listening on a Unix domain socket
2. Socket name: `adt_sidekick_{package_name}`
3. MCP server/Inspector uses ADB port forwarding to connect:
   ```
   adb forward tcp:PORT localabstract:adt_sidekick_com.example.app
   ```
4. REST API over HTTP to the forwarded port

## Development

```bash
# Install dev dependencies
pip install -e ".[dev]"

# Run tests
pytest

# Lint
ruff check .
```
