"""ADT MCP Server - MCP tools for Android Development Tools."""

import asyncio
import base64
import json
import logging
from typing import Any

from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import Tool, TextContent, ImageContent

from . import adb
from .sidekick import SidekickClient, SidekickConnection

# Logging setup
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("adt-mcp")

# Server instance
server = Server("adt-mcp")

# Session state
_connections: dict[str, SidekickConnection] = {}  # package -> connection
_next_port = 18640  # Starting port for forwarding


def _get_next_port() -> int:
    """Get next available port for forwarding."""
    global _next_port
    port = _next_port
    _next_port += 1
    return port


async def _get_or_create_connection(package: str, device: str | None = None) -> SidekickConnection:
    """Get existing connection or create new one via port forwarding."""
    key = f"{device or 'default'}:{package}"

    if key in _connections:
        return _connections[key]

    # Set up port forwarding
    socket_name = f"adt_sidekick_{package}"
    port = _get_next_port()

    success = await adb.setup_port_forward(port, socket_name, device)
    if not success:
        raise RuntimeError(f"Failed to set up port forwarding for {socket_name}")

    conn = SidekickConnection(
        host="127.0.0.1",
        port=port,
        package_name=package,
        device=device or "default"
    )
    _connections[key] = conn
    return conn


# Tool definitions
@server.list_tools()
async def list_tools() -> list[Tool]:
    """List available tools."""
    return [
        # Device tools
        Tool(
            name="list_devices",
            description="List connected Android devices and emulators",
            inputSchema={"type": "object", "properties": {}}
        ),
        Tool(
            name="list_apps",
            description="List debuggable apps on a device. Shows apps that have sidekick sockets available.",
            inputSchema={
                "type": "object",
                "properties": {
                    "device": {
                        "type": "string",
                        "description": "Device serial. If not specified, uses first available."
                    }
                }
            }
        ),
        Tool(
            name="screenshot",
            description="Capture a screenshot from the device",
            inputSchema={
                "type": "object",
                "properties": {
                    "device": {"type": "string", "description": "Device serial"}
                }
            }
        ),
        Tool(
            name="tap",
            description="Tap at screen coordinates",
            inputSchema={
                "type": "object",
                "properties": {
                    "x": {"type": "integer", "description": "X coordinate"},
                    "y": {"type": "integer", "description": "Y coordinate"},
                    "device": {"type": "string", "description": "Device serial"}
                },
                "required": ["x", "y"]
            }
        ),
        Tool(
            name="swipe",
            description="Swipe from one point to another",
            inputSchema={
                "type": "object",
                "properties": {
                    "x1": {"type": "integer", "description": "Start X"},
                    "y1": {"type": "integer", "description": "Start Y"},
                    "x2": {"type": "integer", "description": "End X"},
                    "y2": {"type": "integer", "description": "End Y"},
                    "duration": {"type": "integer", "description": "Duration in ms (default 300)"},
                    "device": {"type": "string", "description": "Device serial"}
                },
                "required": ["x1", "y1", "x2", "y2"]
            }
        ),
        Tool(
            name="input_text",
            description="Input text (requires focus on text field)",
            inputSchema={
                "type": "object",
                "properties": {
                    "text": {"type": "string", "description": "Text to input"},
                    "device": {"type": "string", "description": "Device serial"}
                },
                "required": ["text"]
            }
        ),
        Tool(
            name="press_key",
            description="Press a key (e.g., BACK, HOME, ENTER)",
            inputSchema={
                "type": "object",
                "properties": {
                    "key": {"type": "string", "description": "Key code (BACK, HOME, ENTER, or number)"},
                    "device": {"type": "string", "description": "Device serial"}
                },
                "required": ["key"]
            }
        ),

        # App tools (require sidekick)
        Tool(
            name="compose_tree",
            description="Get Compose UI hierarchy from an app. "
                       "IMPORTANT: Always use text or type filters to avoid context pollution - "
                       "the full tree can be very large. Use dump_compose_tree if you need the complete hierarchy. "
                       "Returns element bounds for tapping. Requires the app to have sidekick installed.",
            inputSchema={
                "type": "object",
                "properties": {
                    "package": {"type": "string", "description": "App package name (e.g., com.example.app)"},
                    "device": {"type": "string", "description": "Device serial"},
                    "text": {"type": "string", "description": "Filter: find elements containing this text (recommended)"},
                    "type": {"type": "string", "description": "Filter: find elements of this type (e.g., Button, Text)"}
                },
                "required": ["package"]
            }
        ),
        Tool(
            name="compose_select",
            description="Find UI element at screen coordinates. Returns element info and ancestors.",
            inputSchema={
                "type": "object",
                "properties": {
                    "package": {"type": "string", "description": "App package name"},
                    "x": {"type": "integer", "description": "X coordinate"},
                    "y": {"type": "integer", "description": "Y coordinate"},
                    "device": {"type": "string", "description": "Device serial"}
                },
                "required": ["package", "x", "y"]
            }
        ),
        Tool(
            name="network_requests",
            description="List captured HTTP requests from an app. "
                       "Returns summary list. Use network_request for details or dump_network_requests to save all to file.",
            inputSchema={
                "type": "object",
                "properties": {
                    "package": {"type": "string", "description": "App package name"},
                    "device": {"type": "string", "description": "Device serial"}
                },
                "required": ["package"]
            }
        ),
        Tool(
            name="network_request",
            description="Get detailed info about a specific HTTP request including headers and body",
            inputSchema={
                "type": "object",
                "properties": {
                    "package": {"type": "string", "description": "App package name"},
                    "request_id": {"type": "string", "description": "Request ID from network_requests"},
                    "device": {"type": "string", "description": "Device serial"}
                },
                "required": ["package", "request_id"]
            }
        ),
        Tool(
            name="websocket_connections",
            description="List captured WebSocket connections from an app. "
                       "Returns summary list. Use websocket_connection for details or dump_websocket_messages to save all to file.",
            inputSchema={
                "type": "object",
                "properties": {
                    "package": {"type": "string", "description": "App package name"},
                    "device": {"type": "string", "description": "Device serial"}
                },
                "required": ["package"]
            }
        ),
        Tool(
            name="websocket_connection",
            description="Get detailed info about a WebSocket connection including all messages",
            inputSchema={
                "type": "object",
                "properties": {
                    "package": {"type": "string", "description": "App package name"},
                    "connection_id": {"type": "string", "description": "Connection ID"},
                    "device": {"type": "string", "description": "Device serial"}
                },
                "required": ["package", "connection_id"]
            }
        ),

        # Selection tools
        Tool(
            name="get_selection",
            description="Get currently selected element or network request from the inspector UI",
            inputSchema={
                "type": "object",
                "properties": {
                    "package": {"type": "string", "description": "App package name"},
                    "type": {"type": "string", "enum": ["element", "network"], "description": "Selection type"},
                    "device": {"type": "string", "description": "Device serial"}
                },
                "required": ["package", "type"]
            }
        ),
        Tool(
            name="set_selection",
            description="Set selected element or network request (syncs with inspector UI)",
            inputSchema={
                "type": "object",
                "properties": {
                    "package": {"type": "string", "description": "App package name"},
                    "type": {"type": "string", "enum": ["element", "network"], "description": "Selection type"},
                    "data": {"type": "object", "description": "Selection data (element or request info)"},
                    "device": {"type": "string", "description": "Device serial"}
                },
                "required": ["package", "type"]
            }
        ),

        # Dump tools (save to file to avoid context pollution)
        Tool(
            name="dump_compose_tree",
            description="Dump the complete Compose UI hierarchy to a JSON file. "
                       "Use this instead of compose_tree when you need the full tree but want to avoid context pollution. "
                       "You can then read specific parts of the file as needed.",
            inputSchema={
                "type": "object",
                "properties": {
                    "package": {"type": "string", "description": "App package name"},
                    "output_file": {"type": "string", "description": "Path to output JSON file"},
                    "device": {"type": "string", "description": "Device serial"}
                },
                "required": ["package", "output_file"]
            }
        ),
        Tool(
            name="dump_network_requests",
            description="Dump all captured HTTP requests with full details (headers, bodies) to a JSON file. "
                       "Use this to save network traffic for analysis without polluting context.",
            inputSchema={
                "type": "object",
                "properties": {
                    "package": {"type": "string", "description": "App package name"},
                    "output_file": {"type": "string", "description": "Path to output JSON file"},
                    "device": {"type": "string", "description": "Device serial"}
                },
                "required": ["package", "output_file"]
            }
        ),
        Tool(
            name="dump_websocket_messages",
            description="Dump all WebSocket connections and their messages to a JSON file. "
                       "Use this to save WebSocket traffic for analysis without polluting context.",
            inputSchema={
                "type": "object",
                "properties": {
                    "package": {"type": "string", "description": "App package name"},
                    "output_file": {"type": "string", "description": "Path to output JSON file"},
                    "connection_id": {"type": "string", "description": "Optional: specific connection ID. If not provided, dumps all connections."},
                    "device": {"type": "string", "description": "Device serial"}
                },
                "required": ["package", "output_file"]
            }
        ),
    ]


# Tool handlers
@server.call_tool()
async def call_tool(name: str, arguments: dict[str, Any]) -> list[TextContent | ImageContent]:
    """Handle tool calls."""
    try:
        result = await _handle_tool(name, arguments)
        if isinstance(result, bytes):
            # Image result
            return [ImageContent(
                type="image",
                data=base64.standard_b64encode(result).decode(),
                mimeType="image/png"
            )]
        elif isinstance(result, str):
            return [TextContent(type="text", text=result)]
        else:
            return [TextContent(type="text", text=json.dumps(result, indent=2))]
    except Exception as e:
        logger.exception(f"Error executing tool {name}")
        return [TextContent(type="text", text=f"Error: {str(e)}")]


async def _handle_tool(name: str, args: dict[str, Any]) -> Any:
    """Route tool calls to handlers."""
    device = args.get("device")

    # Device tools
    if name == "list_devices":
        devices = await adb.list_devices()
        return {
            "devices": [
                {"serial": d.serial, "state": d.state, "model": d.model, "product": d.product}
                for d in devices
            ]
        }

    if name == "list_apps":
        sockets = await adb.discover_sidekick_sockets(device)
        return {
            "apps": [{"package": s.package_name, "socket": s.socket_name} for s in sockets],
            "hint": "These apps have sidekick installed and can be inspected with compose_tree, network_requests, etc."
        }

    if name == "screenshot":
        data = await adb.capture_screenshot(device)
        if data:
            return data
        return {"error": "Failed to capture screenshot"}

    if name == "tap":
        success = await adb.input_tap(args["x"], args["y"], device)
        return {"success": success, "x": args["x"], "y": args["y"]}

    if name == "swipe":
        success = await adb.input_swipe(
            args["x1"], args["y1"], args["x2"], args["y2"],
            args.get("duration", 300), device
        )
        return {"success": success}

    if name == "input_text":
        success = await adb.input_text(args["text"], device)
        return {"success": success}

    if name == "press_key":
        key = args["key"]
        # Map common key names
        key_map = {
            "BACK": 4, "HOME": 3, "MENU": 82, "ENTER": 66,
            "TAB": 61, "DEL": 67, "SPACE": 62
        }
        keycode = key_map.get(key.upper(), key)
        success = await adb.press_key(keycode, device)
        return {"success": success, "key": key}

    # App tools (require sidekick)
    package = args.get("package")
    if not package:
        return {"error": "package is required"}

    conn = await _get_or_create_connection(package, device)
    async with SidekickClient(conn) as client:
        if name == "compose_tree":
            tree = await client.compose_tree()

            # Apply filters if specified
            text_filter = args.get("text")
            type_filter = args.get("type")

            if text_filter or type_filter:
                matches = _filter_tree(tree.get("root", {}), text_filter, type_filter)
                return {
                    "count": len(matches),
                    "package": package,
                    "filters": {"text": text_filter, "type": type_filter},
                    "matches": matches
                }
            return tree

        if name == "compose_select":
            return await client.compose_select(args["x"], args["y"])

        if name == "network_requests":
            return await client.network_requests()

        if name == "network_request":
            return await client.network_request(args["request_id"])

        if name == "websocket_connections":
            return await client.websocket_connections()

        if name == "websocket_connection":
            return await client.websocket_connection(args["connection_id"])

        if name == "get_selection":
            sel_type = args["type"]
            if sel_type == "element":
                return await client.get_selected_element()
            else:
                return await client.get_selected_network()

        if name == "set_selection":
            sel_type = args["type"]
            data = args.get("data")
            if sel_type == "element":
                return await client.set_selected_element(data)
            else:
                return await client.set_selected_network(data)

        # Dump tools
        if name == "dump_compose_tree":
            output_file = args["output_file"]
            tree = await client.compose_tree()
            await _write_json_file(output_file, tree)
            node_count = _count_nodes(tree.get("root", {}))
            return {
                "success": True,
                "file": output_file,
                "node_count": node_count,
                "hint": "Use file read tools to examine specific parts of the tree"
            }

        if name == "dump_network_requests":
            output_file = args["output_file"]
            requests_list = await client.network_requests()
            # Fetch full details for each request
            detailed_requests = []
            for req in requests_list.get("requests", []):
                req_id = req.get("id")
                if req_id:
                    details = await client.network_request(req_id)
                    detailed_requests.append(details)
            result = {
                "count": len(detailed_requests),
                "requests": detailed_requests
            }
            await _write_json_file(output_file, result)
            return {
                "success": True,
                "file": output_file,
                "request_count": len(detailed_requests),
                "hint": "Use file read tools to examine specific requests"
            }

        if name == "dump_websocket_messages":
            output_file = args["output_file"]
            connection_id = args.get("connection_id")

            if connection_id:
                # Dump specific connection
                conn_details = await client.websocket_connection(connection_id)
                result = {"connections": [conn_details]}
            else:
                # Dump all connections with messages
                connections_list = await client.websocket_connections()
                detailed_connections = []
                for conn_info in connections_list.get("connections", []):
                    conn_id = conn_info.get("id")
                    if conn_id:
                        details = await client.websocket_connection(conn_id)
                        detailed_connections.append(details)
                result = {
                    "count": len(detailed_connections),
                    "connections": detailed_connections
                }

            await _write_json_file(output_file, result)
            msg_count = sum(len(c.get("messages", [])) for c in result.get("connections", []))
            return {
                "success": True,
                "file": output_file,
                "connection_count": len(result.get("connections", [])),
                "message_count": msg_count,
                "hint": "Use file read tools to examine specific messages"
            }

    return {"error": f"Unknown tool: {name}"}


async def _write_json_file(path: str, data: Any) -> None:
    """Write data to a JSON file."""
    import aiofiles
    async with aiofiles.open(path, 'w') as f:
        await f.write(json.dumps(data, indent=2, default=str))


def _count_nodes(node: dict) -> int:
    """Count total nodes in a tree."""
    count = 1
    for child in node.get("children", []):
        count += _count_nodes(child)
    return count


def _filter_tree(node: dict, text_filter: str | None, type_filter: str | None, path: str = "") -> list[dict]:
    """Filter compose tree by text/type."""
    matches = []

    composable = node.get("composable", "")
    current_path = f"{path} > {composable}" if path else composable

    # Check match
    is_match = True
    if type_filter and composable.lower() != type_filter.lower():
        is_match = False
    if text_filter:
        node_text = node.get("text", "")
        if text_filter.lower() not in node_text.lower():
            is_match = False

    if is_match and (text_filter or type_filter):
        match = {
            "path": current_path,
            "composable": composable,
        }
        if "text" in node:
            match["text"] = node["text"]
        if "bounds" in node:
            match["bounds"] = node["bounds"]
        if "semanticsId" in node:
            match["semanticsId"] = node["semanticsId"]
        matches.append(match)

    # Recurse
    for child in node.get("children", []):
        matches.extend(_filter_tree(child, text_filter, type_filter, current_path))

    return matches


async def cleanup():
    """Clean up port forwards on shutdown."""
    for conn in _connections.values():
        await adb.remove_port_forward(conn.port, conn.device if conn.device != "default" else None)


def main():
    """Entry point for MCP server."""
    import atexit

    async def run():
        async with stdio_server() as (read_stream, write_stream):
            await server.run(read_stream, write_stream, server.create_initialization_options())

    # Register cleanup
    atexit.register(lambda: asyncio.run(cleanup()))

    # Run server
    asyncio.run(run())


if __name__ == "__main__":
    main()
