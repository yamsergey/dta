package io.yamsergey.dta.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * DTA MCP Server - Provides Android development tools via Model Context Protocol.
 * All operations are delegated to the DTA daemon server via HTTP.
 */
public class McpServer {

    private static final Logger log = LoggerFactory.getLogger(McpServer.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String MCP_VERSION = getVersion();

    private static DaemonClient daemonClient;

    public static void main(String[] args) throws Exception {
        start(null, null);
    }

    public static void start(String logFile, String logLevel) throws Exception {
        log.info("Starting DTA MCP server v{}", MCP_VERSION);
        if (logFile != null) {
            log.info("File logging enabled: {} (level: {})", logFile, logLevel != null ? logLevel : "INFO");
        }

        List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();
        collectDeviceTools(tools);
        collectAppTools(tools);
        collectLayoutTools(tools);
        collectMockTools(tools);
        collectCdpTools(tools);

        var server = io.modelcontextprotocol.server.McpServer.sync(
                new StdioServerTransportProvider(mapper))
            .serverInfo("dta-mcp", getVersion())
            .capabilities(ServerCapabilities.builder()
                .tools(true)
                .build())
            .tools(tools)
            .build();

        log.info("MCP server started with {} tools", tools.size());
        Thread.currentThread().join();
    }

    /**
     * Ensures the daemon is running and returns the client.
     * Called lazily on first tool invocation.
     */
    public static DaemonClient ensureDaemon() {
        if (daemonClient == null) {
            daemonClient = DaemonLauncher.ensureDaemonRunning();
            log.info("Connected to daemon at {}", daemonClient.getBaseUrl());
        }
        return daemonClient;
    }

    private static DaemonClient getDaemon() {
        return ensureDaemon();
    }

    private static void collectDeviceTools(List<McpServerFeatures.SyncToolSpecification> tools) {
        // list_devices
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("list_devices", "List connected Android devices and emulators",
                "{\"type\":\"object\",\"properties\":{}}"),
            (exchange, args) -> {
                try {
                    String json = getDaemon().devices();
                    return ok(json);
                } catch (Exception e) {
                    return errorResult("Failed to list devices: " + e.getMessage());
                }
            }
        ));

        // list_apps
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("list_apps", "List debuggable apps with sidekick installed",
                schema("device", "string", "Device serial (optional)")),
            (exchange, args) -> {
                try {
                    String device = getString(args, "device");
                    String json = getDaemon().apps(device);
                    return ok(json);
                } catch (Exception e) {
                    return errorResult("Failed to list apps: " + e.getMessage());
                }
            }
        ));

        // screenshot (device screenshot via ADB, no package needed)
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("screenshot", "Capture a screenshot from the device",
                schema("device", "string", "Device serial (optional)")),
            (exchange, args) -> {
                try {
                    String device = getString(args, "device");
                    byte[] data = getDaemon().deviceScreenshot(device);
                    String base64 = Base64.getEncoder().encodeToString(data);
                    var imageContent = new McpSchema.ImageContent(List.of(), null, base64, "image/png");
                    return new CallToolResult(List.of(imageContent), false);
                } catch (Exception e) {
                    return errorResult("Failed to capture screenshot: " + e.getMessage());
                }
            }
        ));

        // scroll_screenshot
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("scroll_screenshot", "Capture a scrolling/long screenshot of scrollable content. " +
                "Auto-detects scrollable views and stitches multiple screenshots into one tall image.",
                schema(Map.of(
                    "device", prop("string", "Device serial (optional)", false),
                    "view_id", prop("string", "Resource ID of scrollable view (e.g., 'com.app:id/recycler'). Auto-detects if omitted.", false),
                    "scroll_to_top", prop("boolean", "Scroll to top before capturing (default: false)", false),
                    "max_captures", prop("integer", "Maximum screenshots to capture (default: 30)", false)
                ))),
            (exchange, args) -> {
                try {
                    String device = getString(args, "device");
                    String viewId = getString(args, "view_id");
                    boolean scrollToTop = Boolean.TRUE.equals(args.get("scroll_to_top"));
                    int maxCaptures = getInt(args, "max_captures", 30);

                    String json = getDaemon().scrollScreenshot(device, viewId, scrollToTop, maxCaptures);

                    // Parse response to extract base64 image and metadata
                    var node = mapper.readTree(json);
                    if (node.has("imageBase64")) {
                        String base64 = node.get("imageBase64").asText();
                        var imageContent = new McpSchema.ImageContent(List.of(), null, base64, "image/png");

                        ObjectNode metadata = mapper.createObjectNode();
                        if (node.has("width")) metadata.put("width", node.get("width").asInt());
                        if (node.has("height")) metadata.put("height", node.get("height").asInt());
                        if (node.has("captures")) metadata.put("captures", node.get("captures").asInt());
                        if (node.has("reachedEnd")) metadata.put("reachedEnd", node.get("reachedEnd").asBoolean());
                        if (node.has("scrollableView")) metadata.put("scrollableView", node.get("scrollableView").asText());
                        var textContent = new McpSchema.TextContent(mapper.writeValueAsString(metadata));

                        return new CallToolResult(List.of(imageContent, textContent), false);
                    }
                    return ok(json);
                } catch (Exception e) {
                    return errorResult("Failed to capture scroll screenshot: " + e.getMessage());
                }
            }
        ));

        // tap
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("tap", "Tap at screen coordinates",
                schema(Map.of(
                    "x", prop("integer", "X coordinate", true),
                    "y", prop("integer", "Y coordinate", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, args) -> {
                try {
                    int x = getInt(args, "x");
                    int y = getInt(args, "y");
                    String device = getString(args, "device");
                    String json = getDaemon().tap(x, y, device);
                    return ok(json);
                } catch (Exception e) {
                    return errorResult("Failed to tap: " + e.getMessage());
                }
            }
        ));

        // swipe
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("swipe", "Swipe from one point to another",
                schema(Map.of(
                    "x1", prop("integer", "Start X", true),
                    "y1", prop("integer", "Start Y", true),
                    "x2", prop("integer", "End X", true),
                    "y2", prop("integer", "End Y", true),
                    "duration", prop("integer", "Duration in ms (default 300)", false),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, args) -> {
                try {
                    String device = getString(args, "device");
                    String json = getDaemon().swipe(getInt(args, "x1"), getInt(args, "y1"),
                        getInt(args, "x2"), getInt(args, "y2"), getInt(args, "duration", 300), device);
                    return ok(json);
                } catch (Exception e) {
                    return errorResult("Failed to swipe: " + e.getMessage());
                }
            }
        ));

        // input_text
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("input_text", "Input text (requires focus on text field)",
                schema(Map.of(
                    "text", prop("string", "Text to input", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, args) -> {
                try {
                    String json = getDaemon().inputText(getString(args, "text"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return errorResult("Failed to input text: " + e.getMessage());
                }
            }
        ));

        // press_key
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("press_key", "Press a key (BACK, HOME, ENTER, etc.)",
                schema(Map.of(
                    "key", prop("string", "Key code (BACK, HOME, ENTER, or number)", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, args) -> {
                try {
                    String json = getDaemon().pressKey(getString(args, "key"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return errorResult("Failed to press key: " + e.getMessage());
                }
            }
        ));
    }

    private static void collectAppTools(List<McpServerFeatures.SyncToolSpecification> tools) {
        // network_requests
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("network_requests", "List captured HTTP requests from an app",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, args) -> {
                try {
                    String json = getDaemon().networkRequests(getString(args, "package"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return errorResult("Failed: " + e.getMessage());
                }
            }
        ));

        // network_request
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("network_request", "Get detailed info about a specific HTTP request",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "request_id", prop("string", "Request ID from network_requests", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, args) -> {
                try {
                    String json = getDaemon().networkRequest(
                        getString(args, "package"), getString(args, "request_id"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return errorResult("Failed: " + e.getMessage());
                }
            }
        ));

        // websocket_connections
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("websocket_connections", "List captured WebSocket connections from an app",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, args) -> {
                try {
                    String json = getDaemon().websocketConnections(getString(args, "package"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return errorResult("Failed: " + e.getMessage());
                }
            }
        ));

        // websocket_connection
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("websocket_connection", "Get detailed info about a WebSocket connection including messages",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "connection_id", prop("string", "Connection ID", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, args) -> {
                try {
                    String json = getDaemon().websocketConnection(
                        getString(args, "package"), getString(args, "connection_id"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return errorResult("Failed: " + e.getMessage());
                }
            }
        ));

        // get_selected_elements
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("get_selected_elements", "Get all selected UI elements (highlighted on device)",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, args) -> {
                try {
                    String json = getDaemon().getSelectedElements(getString(args, "package"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return errorResult("Failed: " + e.getMessage());
                }
            }
        ));

        // select_element_at
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("select_element_at", "Add the UI element at given screen coordinates to selection (highlight on device)",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "x", prop("integer", "X coordinate", true),
                    "y", prop("integer", "Y coordinate", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, args) -> {
                try {
                    String pkg = getString(args, "package");
                    String device = getString(args, "device");
                    int x = getInt(args, "x");
                    int y = getInt(args, "y");
                    // The server's element selection endpoint handles hit-testing internally
                    // We pass coordinates as the element JSON
                    String elementJson = mapper.writeValueAsString(Map.of("x", x, "y", y));
                    String json = getDaemon().addSelectedElement(pkg, device, elementJson);
                    return ok(json);
                } catch (Exception e) {
                    return errorResult("Failed: " + e.getMessage());
                }
            }
        ));

        // clear_element_selection
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("clear_element_selection", "Clear all element selections/highlights",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, args) -> {
                try {
                    getDaemon().clearSelectedElements(getString(args, "package"), getString(args, "device"));
                    return ok("{\"success\":true,\"message\":\"Element selection cleared\"}");
                } catch (Exception e) {
                    return errorResult("Failed: " + e.getMessage());
                }
            }
        ));

        // remove_selected_element
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("remove_selected_element", "Remove a UI element from selection by coordinates",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "x", prop("integer", "X coordinate of element to remove", true),
                    "y", prop("integer", "Y coordinate of element to remove", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, args) -> {
                try {
                    String pkg = getString(args, "package");
                    String device = getString(args, "device");
                    int x = getInt(args, "x");
                    int y = getInt(args, "y");
                    String elementJson = mapper.writeValueAsString(Map.of("x", x, "y", y));
                    String json = getDaemon().removeSelectedElement(pkg, device, elementJson);
                    return ok(json);
                } catch (Exception e) {
                    return errorResult("Failed: " + e.getMessage());
                }
            }
        ));

        // get_selected_network_requests
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("get_selected_network_requests", "Get all selected network requests",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, args) -> {
                try {
                    String json = getDaemon().getSelectedNetworkRequests(getString(args, "package"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return errorResult("Failed: " + e.getMessage());
                }
            }
        ));

        // select_network_request
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("select_network_request", "Add a network request to selection by ID",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "request_id", prop("string", "Request ID to add to selection", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, args) -> {
                try {
                    String pkg = getString(args, "package");
                    String requestId = getString(args, "request_id");
                    String device = getString(args, "device");
                    String selectionJson = mapper.writeValueAsString(Map.of("id", requestId));
                    String json = getDaemon().addSelectedNetworkRequest(pkg, device, selectionJson);
                    return ok(json);
                } catch (Exception e) {
                    return errorResult("Failed: " + e.getMessage());
                }
            }
        ));

        // get_selected_websocket_messages
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("get_selected_websocket_messages", "Get all selected WebSocket messages",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, args) -> {
                try {
                    String json = getDaemon().getSelectedWebSocketMessages(getString(args, "package"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return errorResult("Failed: " + e.getMessage());
                }
            }
        ));

        // select_websocket_message
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("select_websocket_message", "Add a WebSocket message to selection by connection ID and message index",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "connection_id", prop("string", "WebSocket connection ID", true),
                    "message_index", prop("integer", "Message index (0-based)", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, args) -> {
                try {
                    String pkg = getString(args, "package");
                    String device = getString(args, "device");
                    String selectionJson = mapper.writeValueAsString(Map.of(
                        "connectionId", getString(args, "connection_id"),
                        "messageIndex", getInt(args, "message_index")
                    ));
                    String json = getDaemon().addSelectedWebSocketMessage(pkg, device, selectionJson);
                    return ok(json);
                } catch (Exception e) {
                    return errorResult("Failed: " + e.getMessage());
                }
            }
        ));

        // clear_network_requests
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("clear_network_requests", "Clear all captured HTTP requests from an app",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, args) -> {
                try {
                    String json = getDaemon().clearNetworkRequests(getString(args, "package"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return errorResult("Failed: " + e.getMessage());
                }
            }
        ));

        // clear_websocket_connections
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("clear_websocket_connections", "Clear all captured WebSocket connections from an app",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, args) -> {
                try {
                    String json = getDaemon().clearWebsocketConnections(getString(args, "package"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return errorResult("Failed: " + e.getMessage());
                }
            }
        ));

        // network_request_body
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("network_request_body", "Get the response body for a specific HTTP request",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "request_id", prop("string", "Request ID from network_requests", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, args) -> {
                try {
                    String json = getDaemon().networkRequestBody(
                        getString(args, "package"), getString(args, "request_id"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return errorResult("Failed: " + e.getMessage());
                }
            }
        ));

        // network_stats
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("network_stats", "Get network statistics for an app",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, args) -> {
                try {
                    String json = getDaemon().networkStats(getString(args, "package"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return errorResult("Failed: " + e.getMessage());
                }
            }
        ));

        // clear_network_selection
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("clear_network_selection", "Clear all network request selections",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, args) -> {
                try {
                    getDaemon().clearSelectedNetworkRequests(getString(args, "package"), getString(args, "device"));
                    return ok("{\"success\":true,\"message\":\"Network selection cleared\"}");
                } catch (Exception e) {
                    return errorResult("Failed: " + e.getMessage());
                }
            }
        ));

        // remove_selected_network_request
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("remove_selected_network_request", "Remove a network request from selection by ID",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "request_id", prop("string", "Request ID to remove from selection", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, args) -> {
                try {
                    String selectionJson = mapper.writeValueAsString(Map.of("id", getString(args, "request_id")));
                    String json = getDaemon().removeSelectedNetworkRequest(getString(args, "package"), getString(args, "device"), selectionJson);
                    return ok(json);
                } catch (Exception e) {
                    return errorResult("Failed: " + e.getMessage());
                }
            }
        ));

        // clear_websocket_selection
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("clear_websocket_selection", "Clear all WebSocket message selections",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, args) -> {
                try {
                    getDaemon().clearSelectedWebSocketMessages(getString(args, "package"), getString(args, "device"));
                    return ok("{\"success\":true,\"message\":\"WebSocket selection cleared\"}");
                } catch (Exception e) {
                    return errorResult("Failed: " + e.getMessage());
                }
            }
        ));

        // remove_selected_websocket_message
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("remove_selected_websocket_message", "Remove a WebSocket message from selection",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "connection_id", prop("string", "WebSocket connection ID", true),
                    "message_index", prop("integer", "Message index (0-based)", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, args) -> {
                try {
                    String selectionJson = mapper.writeValueAsString(Map.of(
                        "connectionId", getString(args, "connection_id"),
                        "messageIndex", getInt(args, "message_index")
                    ));
                    String json = getDaemon().removeSelectedWebSocketMessage(
                        getString(args, "package"), getString(args, "device"), selectionJson);
                    return ok(json);
                } catch (Exception e) {
                    return errorResult("Failed: " + e.getMessage());
                }
            }
        ));
    }

    private static void collectLayoutTools(List<McpServerFeatures.SyncToolSpecification> tools) {
        // layout_tree
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("layout_tree",
                "Get the complete UI layout hierarchy including both Android Views and Jetpack Compose nodes " +
                "with rich properties. Returns a unified tree showing the full view hierarchy with class names, " +
                "resource IDs, bounds, layout parameters, drawing properties, and Compose-specific data " +
                "(composable names, parameters, semantics, source locations). " +
                "Use text/type/resource_id filters to reduce output. Use view_id to get a specific subtree.",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial", false),
                    "text", prop("string", "Filter: find elements containing this text (case-insensitive)", false),
                    "type", prop("string", "Filter: find elements of this type (e.g., Button, TextView, LinearLayout)", false),
                    "resource_id", prop("string", "Filter: find elements with this resource ID (e.g., 'com.example:id/button')", false),
                    "view_id", prop("string", "Get subtree rooted at this specific view drawing ID", false)
                ))),
            (exchange, args) -> {
                try {
                    String json = getDaemon().layoutTree(
                        getString(args, "package"), getString(args, "device"),
                        getString(args, "text"), getString(args, "type"),
                        getString(args, "resource_id"), getString(args, "view_id"));
                    return ok(json);
                } catch (Exception e) {
                    return errorResult("Failed: " + e.getMessage());
                }
            }
        ));

        // layout_properties
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("layout_properties",
                "Get detailed ViewDebug properties for a specific view node. Returns all annotated properties " +
                "organized by category (identity, view, layout, drawing, focus, scrolling) with resolved " +
                "resource names. Use the drawingId from layout_tree results to identify the target view.",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "view_id", prop("string", "Use the drawingId field value from layout_tree results as this parameter", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, args) -> {
                try {
                    String json = getDaemon().layoutProperties(
                        getString(args, "package"), getString(args, "view_id"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return errorResult("Failed: " + e.getMessage());
                }
            }
        ));
    }

    private static void collectMockTools(List<McpServerFeatures.SyncToolSpecification> tools) {
        // mock_list_rules
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("mock_list_rules", "List all mock rules for HTTP and WebSocket mocking",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, args) -> {
                try {
                    String json = getDaemon().mockRules(getString(args, "package"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return errorResult("Failed: " + e.getMessage());
                }
            }
        ));

        // mock_create_rule
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("mock_create_rule", "Create a mock rule. Either provide request_id/message_id to create from captured data, OR provide type with other parameters to create from scratch.",
                schema(Map.ofEntries(
                    Map.entry("package", prop("string", "App package name", true)),
                    Map.entry("request_id", prop("string", "ID of captured HTTP request (mode 1)", false)),
                    Map.entry("message_id", prop("string", "ID of captured WebSocket message (mode 1)", false)),
                    Map.entry("type", prop("string", "Rule type: HTTP or WEBSOCKET (mode 2)", false)),
                    Map.entry("name", prop("string", "Rule name", false)),
                    Map.entry("url_pattern", prop("string", "URL pattern regex", false)),
                    Map.entry("direction", prop("string", "WebSocket direction: SENT, RECEIVED, or BOTH", false)),
                    Map.entry("body", prop("string", "Mock response body (HTTP)", false)),
                    Map.entry("status_code", prop("integer", "HTTP status code (default 200)", false)),
                    Map.entry("text_payload", prop("string", "Mock text payload (WebSocket)", false)),
                    Map.entry("device", prop("string", "Device serial", false))
                ))),
            (exchange, args) -> {
                try {
                    String pkg = getString(args, "package");
                    String requestId = getString(args, "request_id");
                    String messageId = getString(args, "message_id");
                    String type = getString(args, "type");
                    String device = getString(args, "device");

                    boolean fromCaptured = requestId != null || messageId != null;
                    boolean fromScratch = type != null;

                    if (!fromCaptured && !fromScratch) {
                        return errorResult("Provide request_id, message_id, or type to create a rule");
                    }
                    if (fromCaptured && fromScratch) {
                        return errorResult("Cannot mix request_id/message_id with type");
                    }
                    if (requestId != null && messageId != null) {
                        return errorResult("Provide either request_id or message_id, not both");
                    }

                    if (fromCaptured) {
                        String json;
                        if (requestId != null) {
                            json = getDaemon().createMockFromRequest(pkg, requestId, device);
                        } else {
                            json = getDaemon().createMockFromMessage(pkg, messageId, device);
                        }
                        return ok(json);
                    } else {
                        // Build rule JSON from scratch
                        String ruleType = type.toUpperCase();
                        if (!ruleType.equals("HTTP") && !ruleType.equals("WEBSOCKET")) {
                            return errorResult("type must be HTTP or WEBSOCKET");
                        }

                        ObjectNode rule = mapper.createObjectNode();
                        rule.put("type", ruleType);
                        rule.put("enabled", true);
                        rule.put("name", getString(args, "name") != null ? getString(args, "name") : "New " + ruleType + " Rule");
                        rule.put("urlPattern", getString(args, "url_pattern") != null ? getString(args, "url_pattern") : ".*");

                        if (ruleType.equals("HTTP")) {
                            ObjectNode mockResponse = rule.putObject("mockResponse");
                            mockResponse.put("statusCode", getInt(args, "status_code", 200));
                            mockResponse.put("statusMessage", "OK");
                            mockResponse.put("body", getString(args, "body") != null ? getString(args, "body") : "{\"mocked\":true}");
                            mockResponse.put("contentType", "application/json");
                        } else {
                            rule.put("direction", getString(args, "direction") != null ? getString(args, "direction").toUpperCase() : "RECEIVED");
                            ObjectNode mockMessage = rule.putObject("mockMessage");
                            mockMessage.put("textPayload", getString(args, "text_payload") != null ? getString(args, "text_payload") : "{\"mocked\":true}");
                        }

                        String json = getDaemon().createMockRule(pkg, device, mapper.writeValueAsString(rule));
                        return ok(json);
                    }
                } catch (Exception e) {
                    return errorResult("Failed: " + e.getMessage());
                }
            }
        ));

        // mock_update_rule
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("mock_update_rule", "Update a mock rule (enable/disable, modify response/message, set content pattern)",
                schema(Map.ofEntries(
                    Map.entry("package", prop("string", "App package name", true)),
                    Map.entry("rule_id", prop("string", "Mock rule ID", true)),
                    Map.entry("enabled", prop("boolean", "Enable or disable the rule", false)),
                    Map.entry("content_pattern", prop("string", "Regex pattern to match message/body content (for selective mocking)", false)),
                    Map.entry("direction", prop("string", "WebSocket direction: SENT, RECEIVED, or BOTH (for WebSocket rules)", false)),
                    Map.entry("status_code", prop("integer", "New HTTP status code (for HTTP rules)", false)),
                    Map.entry("body", prop("string", "New response body (for HTTP rules)", false)),
                    Map.entry("text_payload", prop("string", "New WebSocket message payload (for WebSocket rules)", false)),
                    Map.entry("drop", prop("boolean", "Drop WebSocket message instead of modifying (for WebSocket rules)", false)),
                    Map.entry("delay_ms", prop("integer", "Response delay in milliseconds", false)),
                    Map.entry("device", prop("string", "Device serial", false))
                ))),
            (exchange, args) -> {
                try {
                    String pkg = getString(args, "package");
                    String ruleId = getString(args, "rule_id");
                    String device = getString(args, "device");

                    ObjectNode update = mapper.createObjectNode();
                    if (args.containsKey("enabled")) update.put("enabled", (Boolean) args.get("enabled"));
                    if (args.containsKey("delay_ms")) update.put("delayMs", getInt(args, "delay_ms", 0));
                    if (args.containsKey("content_pattern")) update.put("contentPattern", getString(args, "content_pattern"));
                    if (args.containsKey("direction")) update.put("direction", getString(args, "direction"));

                    if (args.containsKey("status_code") || args.containsKey("body")) {
                        ObjectNode mockResponse = update.putObject("mockResponse");
                        if (args.containsKey("status_code")) mockResponse.put("statusCode", getInt(args, "status_code", 200));
                        if (args.containsKey("body")) mockResponse.put("body", getString(args, "body"));
                    }

                    if (args.containsKey("text_payload") || args.containsKey("drop")) {
                        ObjectNode mockMessage = update.putObject("mockMessage");
                        if (args.containsKey("text_payload")) mockMessage.put("textPayload", getString(args, "text_payload"));
                        if (args.containsKey("drop")) mockMessage.put("drop", (Boolean) args.get("drop"));
                    }

                    String json = getDaemon().updateMockRule(pkg, ruleId, device, mapper.writeValueAsString(update));
                    return ok(json);
                } catch (Exception e) {
                    return errorResult("Failed: " + e.getMessage());
                }
            }
        ));

        // mock_delete_rule
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("mock_delete_rule", "Delete a mock rule",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "rule_id", prop("string", "Mock rule ID to delete", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, args) -> {
                try {
                    String json = getDaemon().deleteMockRule(
                        getString(args, "package"), getString(args, "rule_id"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return errorResult("Failed: " + e.getMessage());
                }
            }
        ));

        // mock_config
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("mock_config", "Get or update global mock configuration",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "enabled", prop("boolean", "Enable/disable all mocking (optional, omit to just get config)", false),
                    "http_enabled", prop("boolean", "Enable/disable HTTP mocking", false),
                    "websocket_enabled", prop("boolean", "Enable/disable WebSocket mocking", false),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, args) -> {
                try {
                    String pkg = getString(args, "package");
                    String device = getString(args, "device");

                    boolean hasUpdate = args.containsKey("enabled") ||
                                        args.containsKey("http_enabled") ||
                                        args.containsKey("websocket_enabled");

                    if (hasUpdate) {
                        ObjectNode configUpdate = mapper.createObjectNode();
                        if (args.containsKey("enabled")) configUpdate.put("enabled", (Boolean) args.get("enabled"));
                        if (args.containsKey("http_enabled")) configUpdate.put("httpMockingEnabled", (Boolean) args.get("http_enabled"));
                        if (args.containsKey("websocket_enabled")) configUpdate.put("webSocketMockingEnabled", (Boolean) args.get("websocket_enabled"));
                        String json = getDaemon().updateMockConfig(pkg, device, mapper.writeValueAsString(configUpdate));
                        return ok(json);
                    } else {
                        String json = getDaemon().mockConfig(pkg, device);
                        return ok(json);
                    }
                } catch (Exception e) {
                    return errorResult("Failed: " + e.getMessage());
                }
            }
        ));
    }

    private static void collectCdpTools(List<McpServerFeatures.SyncToolSpecification> tools) {
        // cdp_watch_start
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("cdp_watch_start", "Start watching Custom Tabs network traffic via Chrome DevTools Protocol. Traffic will be captured automatically and stored alongside regular HTTP requests.",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial (optional)", false)
                ))),
            (exchange, args) -> {
                try {
                    String json = getDaemon().cdpWatchStart(getString(args, "package"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return errorResult("Failed to start CDP watcher: " + e.getMessage());
                }
            }
        ));

        // cdp_watch_stop
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("cdp_watch_stop", "Stop watching Custom Tabs network traffic",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial (optional)", false)
                ))),
            (exchange, args) -> {
                try {
                    String json = getDaemon().cdpWatchStop(getString(args, "package"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return errorResult("Failed to stop CDP watcher: " + e.getMessage());
                }
            }
        ));

        // cdp_watch_status
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("cdp_watch_status", "Check if Custom Tabs network watching is active",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial (optional)", false)
                ))),
            (exchange, args) -> {
                try {
                    String json = getDaemon().cdpWatchStatus(getString(args, "package"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return errorResult("Failed to get CDP watcher status: " + e.getMessage());
                }
            }
        ));
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private static CallToolResult ok(String json) {
        return new CallToolResult(List.of(new McpSchema.TextContent(json)), false);
    }

    private static String getString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value != null ? value.toString() : null;
    }

    private static int getInt(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) return Integer.parseInt(s);
        throw new IllegalArgumentException("Missing required parameter: " + key);
    }

    private static int getInt(Map<String, Object> args, String key, int defaultValue) {
        Object value = args.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) return Integer.parseInt(s);
        return defaultValue;
    }

    private static CallToolResult errorResult(String message) {
        return new CallToolResult(List.of(new McpSchema.TextContent("Error: " + message)), true);
    }

    private static String schema(String propName, String propType, String description) {
        return String.format("""
            {
              "type": "object",
              "properties": {
                "%s": {"type": "%s", "description": "%s"}
              }
            }
            """, propName, propType, description);
    }

    private static String schema(Map<String, Map<String, Object>> properties) {
        try {
            ObjectNode schema = mapper.createObjectNode();
            schema.put("type", "object");
            ObjectNode props = schema.putObject("properties");
            var required = schema.putArray("required");

            for (var entry : properties.entrySet()) {
                String name = entry.getKey();
                Map<String, Object> propDef = entry.getValue();

                ObjectNode prop = props.putObject(name);
                prop.put("type", (String) propDef.get("type"));
                prop.put("description", (String) propDef.get("description"));

                if (Boolean.TRUE.equals(propDef.get("required"))) {
                    required.add(name);
                }
            }

            return mapper.writeValueAsString(schema);
        } catch (Exception e) {
            return "{\"type\":\"object\",\"properties\":{}}";
        }
    }

    private static Map<String, Object> prop(String type, String description, boolean required) {
        Map<String, Object> map = new HashMap<>();
        map.put("type", type);
        map.put("description", description);
        map.put("required", required);
        return map;
    }

    private static String getVersion() {
        try (var is = McpServer.class.getResourceAsStream("/version.properties")) {
            if (is != null) {
                var props = new java.util.Properties();
                props.load(is);
                return props.getProperty("version", "unknown");
            }
        } catch (Exception e) {
            // Ignore
        }
        return "unknown";
    }
}
