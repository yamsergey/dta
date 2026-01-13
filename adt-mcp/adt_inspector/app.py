"""ADT Inspector - NiceGUI-based UI for Android app inspection."""

import asyncio
import base64
import json
from nicegui import ui, app
import httpx

from adt_mcp import adb
from adt_mcp.sidekick import SidekickClient, SidekickConnection

# Global state
_current_device: str | None = None
_current_package: str | None = None
_current_connection: SidekickConnection | None = None
_port_counter = 19640


class InspectorState:
    """Shared state for the inspector."""

    def __init__(self):
        self.devices: list[adb.Device] = []
        self.apps: list[adb.SidekickSocket] = []
        self.compose_tree: dict | None = None
        self.network_requests: list = []
        self.websocket_connections: list = []
        self.selected_element: dict | None = None
        self.selected_request: dict | None = None
        self.selected_websocket: dict | None = None
        self.screenshot_data: bytes | None = None
        self.screenshot_base64: str | None = None

        # UI update callbacks
        self.on_tree_update = None
        self.on_screenshot_update = None
        self.on_element_select = None
        self.on_network_update = None
        self.on_websocket_update = None

    async def refresh_devices(self):
        """Refresh device list."""
        self.devices = await adb.list_devices()

    async def refresh_apps(self):
        """Refresh app list for current device."""
        global _current_device
        self.apps = await adb.discover_sidekick_sockets(_current_device)

    async def connect_to_app(self, package: str):
        """Connect to an app's sidekick."""
        global _current_connection, _current_package, _port_counter

        if _current_connection and _current_connection.port:
            await adb.remove_port_forward(_current_connection.port)

        socket_name = f"adt_sidekick_{package}"
        port = _port_counter
        _port_counter += 1

        success = await adb.setup_port_forward(port, socket_name, _current_device)
        if not success:
            ui.notify(f"Failed to connect to {package}", type="negative")
            return False

        _current_connection = SidekickConnection(
            host="127.0.0.1",
            port=port,
            package_name=package,
            device=_current_device or "default"
        )
        _current_package = package
        ui.notify(f"Connected to {package}", type="positive")
        return True

    async def refresh_screenshot(self):
        """Capture screenshot from device."""
        global _current_device
        data = await adb.capture_screenshot(_current_device)
        if data:
            self.screenshot_data = data
            self.screenshot_base64 = base64.b64encode(data).decode()
            if self.on_screenshot_update:
                self.on_screenshot_update()

    async def refresh_compose_tree(self):
        """Fetch compose tree."""
        if not _current_connection:
            return
        try:
            async with SidekickClient(_current_connection) as client:
                self.compose_tree = await client.compose_tree()
                if self.on_tree_update:
                    self.on_tree_update()
        except Exception as e:
            ui.notify(f"Error: {e}", type="negative")

    async def refresh_network(self):
        """Fetch network requests."""
        if not _current_connection:
            return
        try:
            async with SidekickClient(_current_connection) as client:
                data = await client.network_requests()
                self.network_requests = data.get("requests", [])
                if self.on_network_update:
                    self.on_network_update()
        except Exception as e:
            ui.notify(f"Error: {e}", type="negative")

    async def refresh_websocket(self):
        """Fetch WebSocket connections."""
        if not _current_connection:
            return
        try:
            async with SidekickClient(_current_connection) as client:
                data = await client.websocket_connections()
                self.websocket_connections = data.get("connections", [])
                if self.on_websocket_update:
                    self.on_websocket_update()
        except Exception as e:
            ui.notify(f"Error: {e}", type="negative")

    async def select_element(self, element: dict | None):
        """Select a UI element."""
        self.selected_element = element
        if self.on_element_select:
            self.on_element_select(element)
        if _current_connection and element:
            try:
                async with SidekickClient(_current_connection) as client:
                    await client.set_selected_element(element)
            except Exception:
                pass

    async def get_network_request_details(self, request_id: str) -> dict | None:
        """Get full details for a network request."""
        if not _current_connection:
            return None
        try:
            async with SidekickClient(_current_connection) as client:
                return await client.network_request(request_id)
        except Exception as e:
            ui.notify(f"Error: {e}", type="negative")
            return None

    async def get_websocket_details(self, connection_id: str) -> dict | None:
        """Get full details for a WebSocket connection."""
        if not _current_connection:
            return None
        try:
            async with SidekickClient(_current_connection) as client:
                return await client.websocket_connection(connection_id)
        except Exception as e:
            ui.notify(f"Error: {e}", type="negative")
            return None


# Create global state
state = InspectorState()


def flatten_tree(node: dict, result: list = None, path: str = "") -> list:
    """Flatten compose tree to list of elements with paths."""
    if result is None:
        result = []

    node_path = f"{path}/{node.get('composable', 'Unknown')}"
    result.append({"node": node, "path": node_path})

    for i, child in enumerate(node.get("children", [])):
        flatten_tree(child, result, f"{node_path}[{i}]")

    return result


def find_element_at(x: int, y: int, node: dict) -> dict | None:
    """Find the deepest element containing the point."""
    bounds = node.get("bounds")
    if bounds:
        left = bounds.get("left", 0)
        top = bounds.get("top", 0)
        right = bounds.get("right", 0)
        bottom = bounds.get("bottom", 0)

        if not (left <= x <= right and top <= y <= bottom):
            return None

    # Check children (deepest first)
    for child in reversed(node.get("children", [])):
        found = find_element_at(x, y, child)
        if found:
            return found

    # Return this node if it has bounds
    if bounds:
        return node
    return None


@ui.page("/")
def main_page():
    """Main application page."""
    global _current_device

    ui.dark_mode().enable()

    # Add custom CSS for better styling
    ui.add_head_html("""
    <style>
        .tree-node { transition: background-color 0.2s; }
        .tree-node:hover { background-color: rgba(255,255,255,0.1); }
        .tree-node.selected { background-color: rgba(59, 130, 246, 0.5); }
        .element-overlay { position: absolute; border: 2px solid #3b82f6; background: rgba(59, 130, 246, 0.2); pointer-events: auto; cursor: pointer; }
        .element-overlay:hover { background: rgba(59, 130, 246, 0.4); }
        .element-overlay.selected { border-color: #ef4444; background: rgba(239, 68, 68, 0.3); }
    </style>
    """)

    # Left drawer (collapsible)
    with ui.left_drawer(value=True).classes("bg-gray-900 p-4") as drawer:
        ui.label("ADT Inspector").classes("text-xl font-bold text-white mb-4")

        # Device selection
        ui.label("Device").classes("text-gray-400 text-sm mt-4")
        device_select = ui.select(
            options=[],
            label="Select device",
        ).classes("w-full")

        async def on_device_change(e):
            global _current_device
            _current_device = e.value
            await state.refresh_apps()
            app_select.options = {s.package_name: s.package_name for s in state.apps}
            app_select.update()

        device_select.on_value_change(on_device_change)

        async def refresh_devices():
            await state.refresh_devices()
            device_select.options = {d.serial: f"{d.serial} ({d.model or 'unknown'})" for d in state.devices}
            device_select.update()

        ui.button("Refresh Devices", on_click=refresh_devices).classes("w-full mt-2")

        ui.separator().classes("my-4")

        # App selection
        ui.label("App").classes("text-gray-400 text-sm")
        app_select = ui.select(
            options=[],
            label="Select app",
        ).classes("w-full")

        async def on_app_change(e):
            if e.value:
                await state.connect_to_app(e.value)

        app_select.on_value_change(on_app_change)

        async def scan_apps():
            await state.refresh_apps()
            app_select.options = {s.package_name: s.package_name for s in state.apps}
            app_select.update()

        ui.button("Scan Apps", on_click=scan_apps).classes("w-full mt-2")

        # Status
        ui.separator().classes("my-4")
        ui.label("Status").classes("text-gray-400 text-sm")
        status_label = ui.label("Not connected").classes("text-white")

        def update_status():
            if _current_connection:
                status_label.text = f"Connected: {_current_package}"
            else:
                status_label.text = "Not connected"

        ui.timer(1, update_status)

        # Initial load
        ui.timer(0.1, refresh_devices, once=True)

    # Header with drawer toggle
    with ui.header().classes("bg-gray-800 items-center"):
        ui.button(icon="menu", on_click=drawer.toggle).props("flat color=white")
        ui.label("ADT Inspector").classes("text-lg font-semibold")

    # Main content
    with ui.column().classes("w-full h-full p-4"):
        with ui.tabs().classes("w-full") as tabs:
            layout_tab = ui.tab("Layout")
            network_tab = ui.tab("Network")
            websocket_tab = ui.tab("WebSocket")

        with ui.tab_panels(tabs, value=layout_tab).classes("w-full flex-1"):
            with ui.tab_panel(layout_tab).classes("p-0"):
                create_layout_panel()
            with ui.tab_panel(network_tab).classes("p-0"):
                create_network_panel()
            with ui.tab_panel(websocket_tab).classes("p-0"):
                create_websocket_panel()


def create_layout_panel():
    """Create the layout inspector panel with screenshot and tree."""

    with ui.row().classes("w-full items-center mb-2"):
        ui.label("Layout Inspector").classes("text-lg font-bold")

        async def refresh_all():
            await asyncio.gather(
                state.refresh_screenshot(),
                state.refresh_compose_tree()
            )

        ui.button("Refresh", on_click=refresh_all, icon="refresh")

    # Main content: screenshot left, tree right, details below
    with ui.splitter(value=40).classes("w-full").style("height: 400px;") as splitter:
        with splitter.before:
            with ui.column().classes("w-full h-full bg-gray-900 items-center justify-center"):
                @ui.refreshable
                def screenshot_view():
                    if state.screenshot_base64:
                        # Build SVG overlay for selected element bounds
                        svg_content = ""
                        if state.selected_element and state.selected_element.get("bounds"):
                            bounds = state.selected_element["bounds"]
                            left = bounds.get("left", 0)
                            top = bounds.get("top", 0)
                            right = bounds.get("right", 0)
                            bottom = bounds.get("bottom", 0)
                            width = right - left
                            height = bottom - top
                            # Draw selection rectangle (stroke-width scaled for visibility at ~1/6 scale)
                            svg_content = f'''
                                <rect x="{left}" y="{top}" width="{width}" height="{height}"
                                      fill="rgba(59, 130, 246, 0.2)" stroke="#3b82f6" stroke-width="8"/>
                            '''

                        async def on_image_click(e):
                            if not state.compose_tree or "root" not in state.compose_tree:
                                return
                            # e.image_x and e.image_y are in original image coordinates
                            x, y = int(e.image_x), int(e.image_y)
                            element = find_element_at(x, y, state.compose_tree["root"])
                            if element:
                                await state.select_element(element)
                                screenshot_view.refresh()
                                tree_view.refresh()
                                details_view.refresh()
                                # Scroll tree to selected element
                                if hasattr(state, 'scroll_to_selected'):
                                    await state.scroll_to_selected()

                        # Create interactive image with click handler
                        img = ui.interactive_image(
                            source=f"data:image/png;base64,{state.screenshot_base64}",
                            content=svg_content,
                            on_mouse=on_image_click,
                            events=["mousedown"],
                            cross=False,
                        ).classes("max-h-96").style("max-height: 380px; object-fit: contain;")
                    else:
                        ui.label("No screenshot").classes("text-gray-500")

                screenshot_view()
                state.on_screenshot_update = screenshot_view.refresh

        with splitter.after:
            # Tree view
            tree_container = ui.scroll_area().classes("w-full h-full")
            tree_container.props('id="tree-scroll-container"')

            @ui.refreshable
            def tree_view():
                if not state.compose_tree or "root" not in state.compose_tree:
                    ui.label("No compose tree. Connect to an app and click Refresh.").classes("text-gray-500")
                    return

                node_counter = [0]  # Use list to allow mutation in nested function

                def render_node(node: dict, depth: int = 0):
                    """Render a tree node recursively."""
                    composable = node.get("composable", "Unknown")
                    text = node.get("text")
                    bounds = node.get("bounds")
                    is_selected = state.selected_element is node
                    node_id = node.get("id", f"node_{node_counter[0]}")
                    node_counter[0] += 1

                    indent = depth * 16

                    async def select_this():
                        await state.select_element(node)
                        screenshot_view.refresh()
                        tree_view.refresh()
                        details_view.refresh()

                    row = ui.row().classes(f"tree-node p-1 cursor-pointer rounded {'selected' if is_selected else ''}").style(f"margin-left: {indent}px")
                    row.props(f'id="tree-node-{node_id}"')

                    with row:
                        # Expand/collapse icon placeholder
                        children = node.get("children", [])
                        if children:
                            ui.icon("expand_more", size="xs").classes("text-gray-500")
                        else:
                            ui.icon("circle", size="xs").classes("text-gray-700")

                        # Node label
                        label_text = composable
                        if text:
                            label_text += f': "{text[:20]}..."' if len(text) > 20 else f': "{text}"'

                        ui.label(label_text).classes(f"text-sm {'text-blue-400' if is_selected else 'text-white'}").on("click", select_this)

                        # Bounds info
                        if bounds:
                            ui.label(f"[{bounds.get('left', 0)},{bounds.get('top', 0)}]").classes("text-gray-600 text-xs ml-2")

                    # Render children
                    for child in children:
                        render_node(child, depth + 1)

                render_node(state.compose_tree["root"])

            with tree_container:
                tree_view()

            state.on_tree_update = tree_view.refresh

            # Function to scroll to selected element
            async def scroll_to_selected():
                if state.selected_element:
                    node_id = state.selected_element.get("id", "")
                    if node_id:
                        await ui.run_javascript(f'''
                            const el = document.getElementById("tree-node-{node_id}");
                            if (el) {{
                                el.scrollIntoView({{ behavior: "smooth", block: "center" }});
                            }}
                        ''')

            state.scroll_to_selected = scroll_to_selected

    # Element details panel
    ui.separator().classes("my-2")
    ui.label("Element Details").classes("text-md font-semibold")

    @ui.refreshable
    def details_view():
        if not state.selected_element:
            ui.label("Select an element to see details").classes("text-gray-500 text-sm")
            return

        el = state.selected_element
        with ui.grid(columns=2).classes("gap-2 text-sm"):
            ui.label("Composable:").classes("text-gray-400")
            ui.label(el.get("composable", "Unknown")).classes("text-white")

            if el.get("text"):
                ui.label("Text:").classes("text-gray-400")
                ui.label(el.get("text")).classes("text-white")

            if el.get("contentDescription"):
                ui.label("Description:").classes("text-gray-400")
                ui.label(el.get("contentDescription")).classes("text-white")

            if el.get("testTag"):
                ui.label("Test Tag:").classes("text-gray-400")
                ui.label(el.get("testTag")).classes("text-white")

            bounds = el.get("bounds")
            if bounds:
                ui.label("Bounds:").classes("text-gray-400")
                ui.label(f"({bounds.get('left')}, {bounds.get('top')}) - ({bounds.get('right')}, {bounds.get('bottom')})").classes("text-white")

                ui.label("Size:").classes("text-gray-400")
                w = bounds.get("right", 0) - bounds.get("left", 0)
                h = bounds.get("bottom", 0) - bounds.get("top", 0)
                ui.label(f"{w} x {h}").classes("text-white")

            if el.get("clickable"):
                ui.label("Clickable:").classes("text-gray-400")
                ui.label("Yes").classes("text-green-400")

            if el.get("focused"):
                ui.label("Focused:").classes("text-gray-400")
                ui.label("Yes").classes("text-green-400")

            # Show all other properties
            skip_keys = {"composable", "text", "contentDescription", "testTag", "bounds", "children", "clickable", "focused"}
            for key, value in el.items():
                if key not in skip_keys and value is not None:
                    ui.label(f"{key}:").classes("text-gray-400")
                    ui.label(str(value)[:100]).classes("text-white")

    details_view()

    def on_element_select(element):
        details_view.refresh()

    state.on_element_select = on_element_select


def create_network_panel():
    """Create the network requests panel."""

    with ui.row().classes("w-full items-center mb-2"):
        ui.label("Network Requests").classes("text-lg font-bold")
        ui.button("Refresh", on_click=state.refresh_network, icon="refresh")

    # Request list
    request_container = ui.scroll_area().classes("w-full h-64")

    @ui.refreshable
    def request_list():
        if not state.network_requests:
            ui.label("No requests captured").classes("text-gray-500")
            return

        for req in reversed(state.network_requests):  # Newest first
            method = req.get("method", "?")
            url = req.get("url", "Unknown")
            status = req.get("responseCode", "-")
            duration = req.get("duration", 0)
            req_id = req.get("id", "")

            display_url = url[:60] + "..." if len(url) > 60 else url

            async def show_details(request_id=req_id, request=req):
                details = await state.get_network_request_details(request_id)
                if details:
                    show_network_details_dialog(details)
                else:
                    show_network_details_dialog(request)

            with ui.row().classes("w-full p-2 hover:bg-gray-700 cursor-pointer border-b border-gray-800").on("click", show_details):
                # Method badge
                method_colors = {"GET": "green", "POST": "orange", "PUT": "blue", "DELETE": "red", "PATCH": "purple"}
                ui.badge(method, color=method_colors.get(method, "gray")).classes("mr-2")

                # URL
                ui.label(display_url).classes("flex-1 text-white text-sm truncate")

                # Status
                if isinstance(status, int):
                    status_color = "green" if 200 <= status < 300 else "red" if status >= 400 else "yellow"
                    ui.label(str(status)).classes(f"text-{status_color}-500 w-12")
                else:
                    ui.label(str(status)).classes("text-gray-500 w-12")

                # Duration
                ui.label(f"{duration}ms").classes("text-gray-500 w-16 text-right")

    with request_container:
        request_list()

    state.on_network_update = request_list.refresh


def show_network_details_dialog(details: dict):
    """Show a dialog with full network request details."""
    with ui.dialog().props("maximized") as dialog, ui.card().classes("w-full h-full"):
        method = details.get("method", "?")
        url = details.get("url", "Unknown")
        status = details.get("responseCode", "-")

        # Header with close and export buttons
        with ui.row().classes("w-full items-center justify-between mb-4"):
            ui.label("Request Details").classes("text-lg font-bold")
            with ui.row().classes("gap-2"):
                def copy_request():
                    json_str = json.dumps(details, indent=2, default=str)
                    ui.run_javascript(f'navigator.clipboard.writeText({json.dumps(json_str)})')
                    ui.notify("Request copied to clipboard", type="positive")

                def export_request():
                    json_str = json.dumps(details, indent=2, default=str)
                    # Create download via JavaScript
                    ui.run_javascript(f'''
                        const blob = new Blob([{json.dumps(json_str)}], {{type: "application/json"}});
                        const url = URL.createObjectURL(blob);
                        const a = document.createElement("a");
                        a.href = url;
                        a.download = "request_{details.get('id', 'unknown')}.json";
                        a.click();
                        URL.revokeObjectURL(url);
                    ''')
                    ui.notify("Request exported", type="positive")

                ui.button("Copy", on_click=copy_request, icon="content_copy").props("flat")
                ui.button("Export", on_click=export_request, icon="download").props("flat")
                ui.button(icon="close", on_click=dialog.close).props("flat round")

        # Summary
        with ui.row().classes("items-center mb-4"):
            method_colors = {"GET": "green", "POST": "orange", "PUT": "blue", "DELETE": "red", "PATCH": "purple"}
            ui.badge(method, color=method_colors.get(method, "gray"))
            if isinstance(status, int):
                status_color = "green" if 200 <= status < 300 else "red" if status >= 400 else "yellow"
                ui.badge(str(status), color=status_color)
            ui.label(url).classes("text-sm text-white ml-2 break-all")

        # Scrollable content area
        with ui.scroll_area().classes("w-full flex-1"):
            # Request Headers - collapsible
            req_headers = details.get("requestHeaders", {})
            with ui.expansion("Request Headers", icon="arrow_right", value=True).classes("w-full"):
                if req_headers:
                    with ui.element("div").classes("bg-gray-800 p-2 rounded"):
                        for k, v in req_headers.items():
                            ui.label(f"{k}: {v}").classes("text-sm text-gray-300 font-mono")
                else:
                    ui.label("No request headers").classes("text-gray-500")

            # Response Headers - collapsible
            resp_headers = details.get("responseHeaders", {})
            with ui.expansion("Response Headers", icon="arrow_right", value=True).classes("w-full"):
                if resp_headers:
                    with ui.element("div").classes("bg-gray-800 p-2 rounded"):
                        for k, v in resp_headers.items():
                            ui.label(f"{k}: {v}").classes("text-sm text-gray-300 font-mono")
                else:
                    ui.label("No response headers").classes("text-gray-500")

            # Request Body - collapsible
            req_body = details.get("requestBody", "")
            with ui.expansion("Request Body", icon="arrow_right", value=bool(req_body)).classes("w-full"):
                if req_body:
                    with ui.row().classes("w-full justify-end mb-2"):
                        def copy_req_body():
                            ui.run_javascript(f'navigator.clipboard.writeText({json.dumps(req_body)})')
                            ui.notify("Request body copied", type="positive")
                        ui.button("Copy Body", on_click=copy_req_body, icon="content_copy").props("flat dense")
                    with ui.element("div").classes("bg-gray-800 p-2 rounded"):
                        ui.code(req_body, language="json" if req_body.strip().startswith("{") else "text").classes("w-full")
                else:
                    ui.label("No request body").classes("text-gray-500")

            # Response Body - collapsible (NO truncation)
            resp_body = details.get("responseBody", "")
            with ui.expansion("Response Body", icon="arrow_right", value=bool(resp_body)).classes("w-full"):
                if resp_body:
                    with ui.row().classes("w-full justify-between mb-2"):
                        ui.label(f"{len(resp_body):,} bytes").classes("text-gray-500 text-sm")
                        def copy_resp_body():
                            ui.run_javascript(f'navigator.clipboard.writeText({json.dumps(resp_body)})')
                            ui.notify("Response body copied", type="positive")
                        ui.button("Copy Body", on_click=copy_resp_body, icon="content_copy").props("flat dense")
                    with ui.element("div").classes("bg-gray-800 p-2 rounded"):
                        # Full body without truncation
                        ui.code(resp_body, language="json" if resp_body.strip().startswith("{") else "text").classes("w-full")
                else:
                    ui.label("No response body").classes("text-gray-500")

    dialog.open()


def create_websocket_panel():
    """Create the WebSocket connections panel."""

    with ui.row().classes("w-full items-center mb-2"):
        ui.label("WebSocket Connections").classes("text-lg font-bold")
        ui.button("Refresh", on_click=state.refresh_websocket, icon="refresh")

    # Connection list
    connection_container = ui.scroll_area().classes("w-full h-64")

    @ui.refreshable
    def connection_list():
        if not state.websocket_connections:
            ui.label("No WebSocket connections").classes("text-gray-500")
            return

        for conn in state.websocket_connections:
            url = conn.get("url", "Unknown")
            status = conn.get("status", "UNKNOWN")
            message_count = conn.get("messageCount", 0)
            conn_id = conn.get("id", "")

            display_url = url[:60] + "..." if len(url) > 60 else url

            async def show_details(connection_id=conn_id, connection=conn):
                details = await state.get_websocket_details(connection_id)
                if details:
                    show_websocket_details_dialog(details)
                else:
                    show_websocket_details_dialog(connection)

            with ui.row().classes("w-full p-2 hover:bg-gray-700 cursor-pointer border-b border-gray-800").on("click", show_details):
                # Status badge
                status_colors = {"OPEN": "green", "CLOSED": "red", "CONNECTING": "yellow"}
                ui.badge(status, color=status_colors.get(status, "gray")).classes("mr-2")

                # URL
                ui.label(display_url).classes("flex-1 text-white text-sm truncate")

                # Message count
                ui.label(f"{message_count} msgs").classes("text-gray-500 w-20 text-right")

    with connection_container:
        connection_list()

    state.on_websocket_update = connection_list.refresh


def show_websocket_details_dialog(details: dict):
    """Show a dialog with WebSocket connection details."""
    with ui.dialog().props("maximized") as dialog, ui.card().classes("w-full h-full"):
        url = details.get("url", "Unknown")
        status = details.get("status", "UNKNOWN")
        messages = details.get("messages", [])

        # Header with close and export buttons
        with ui.row().classes("w-full items-center justify-between mb-4"):
            ui.label("WebSocket Details").classes("text-lg font-bold")
            with ui.row().classes("gap-2"):
                def copy_all_messages():
                    all_msgs = []
                    for msg in messages:
                        msg_data = {
                            "direction": msg.get("direction", "?"),
                            "timestamp": msg.get("timestamp", ""),
                            "data": get_message_content(msg)
                        }
                        all_msgs.append(msg_data)
                    json_str = json.dumps(all_msgs, indent=2, default=str)
                    ui.run_javascript(f'navigator.clipboard.writeText({json.dumps(json_str)})')
                    ui.notify(f"Copied {len(messages)} messages to clipboard", type="positive")

                def export_all_messages():
                    all_msgs = []
                    for msg in messages:
                        msg_data = {
                            "direction": msg.get("direction", "?"),
                            "timestamp": msg.get("timestamp", ""),
                            "data": get_message_content(msg)
                        }
                        all_msgs.append(msg_data)
                    json_str = json.dumps({"url": url, "status": status, "messages": all_msgs}, indent=2, default=str)
                    ui.run_javascript(f'''
                        const blob = new Blob([{json.dumps(json_str)}], {{type: "application/json"}});
                        const url = URL.createObjectURL(blob);
                        const a = document.createElement("a");
                        a.href = url;
                        a.download = "websocket_{details.get('id', 'unknown')}.json";
                        a.click();
                        URL.revokeObjectURL(url);
                    ''')
                    ui.notify("WebSocket messages exported", type="positive")

                ui.button("Copy All", on_click=copy_all_messages, icon="content_copy").props("flat")
                ui.button("Export All", on_click=export_all_messages, icon="download").props("flat")
                ui.button(icon="close", on_click=dialog.close).props("flat round")

        # Summary
        with ui.row().classes("items-center mb-4"):
            status_colors = {"OPEN": "green", "CLOSED": "red", "CONNECTING": "yellow"}
            ui.badge(status, color=status_colors.get(status, "gray"))
            ui.label(url).classes("text-sm text-white ml-2 break-all")
            ui.label(f"({len(messages)} messages)").classes("text-gray-500 text-sm ml-2")

        # Messages - scrollable
        ui.label("Messages").classes("font-semibold mb-2")
        if messages:
            with ui.scroll_area().classes("w-full flex-1"):
                for i, msg in enumerate(messages):
                    direction = msg.get("direction", "?")
                    timestamp = msg.get("timestamp", "")
                    # Get actual message content
                    data = get_message_content(msg)

                    icon_name = "arrow_upward" if direction == "SENT" else "arrow_downward"
                    color = "text-blue-400" if direction == "SENT" else "text-green-400"
                    bg_color = "bg-blue-900/30" if direction == "SENT" else "bg-green-900/30"

                    # Truncate for preview
                    preview = data[:100] + "..." if len(data) > 100 else data
                    preview = preview.replace("\n", " ")

                    # Create collapsible message
                    with ui.expansion(group="messages").classes(f"w-full mb-1 {bg_color}") as expansion:
                        # Custom header slot
                        with expansion.add_slot("header"):
                            with ui.row().classes("w-full items-center"):
                                ui.icon(icon_name, size="sm").classes(color)
                                ui.label(timestamp).classes("text-gray-400 text-xs w-32 ml-2")
                                ui.label(preview).classes("text-sm text-white font-mono flex-1 truncate")
                                # Copy single message button
                                def copy_single(msg_data=data):
                                    ui.run_javascript(f'navigator.clipboard.writeText({json.dumps(msg_data)})')
                                    ui.notify("Message copied", type="positive")
                                ui.button(icon="content_copy", on_click=copy_single).props("flat dense size=sm")

                        # Full message content when expanded
                        with ui.element("div").classes("bg-gray-800 p-2 rounded mt-2"):
                            if data.strip().startswith("{") or data.strip().startswith("["):
                                try:
                                    # Try to format as JSON
                                    formatted = json.dumps(json.loads(data), indent=2)
                                    ui.code(formatted, language="json").classes("w-full text-xs")
                                except:
                                    ui.code(data, language="text").classes("w-full text-xs")
                            else:
                                ui.code(data, language="text").classes("w-full text-xs")
        else:
            ui.label("No messages captured").classes("text-gray-500")

    dialog.open()


def get_message_content(msg: dict) -> str:
    """Extract message content from various possible formats."""
    # Try different possible keys for message data
    data = msg.get("data")
    if data is None:
        data = msg.get("content")
    if data is None:
        data = msg.get("payload")
    if data is None:
        data = msg.get("message")
    if data is None:
        data = msg.get("text")
    if data is None:
        data = msg.get("body")

    # If data is still None or not a string, try to serialize the whole message
    if data is None:
        # Return all keys except known metadata
        skip_keys = {"direction", "timestamp", "type", "id"}
        remaining = {k: v for k, v in msg.items() if k not in skip_keys}
        if remaining:
            if len(remaining) == 1:
                # Single value - return it directly
                data = str(list(remaining.values())[0])
            else:
                data = json.dumps(remaining, indent=2, default=str)
        else:
            data = str(msg)
    elif not isinstance(data, str):
        # Convert non-string data to string
        if isinstance(data, (dict, list)):
            data = json.dumps(data, indent=2, default=str)
        else:
            data = str(data)

    return data


def main():
    """Entry point for inspector UI."""
    ui.run(
        title="ADT Inspector",
        host="0.0.0.0",
        port=8080,
        reload=False,
        show=True,
    )


if __name__ in {"__main__", "__mp_main__"}:
    main()
