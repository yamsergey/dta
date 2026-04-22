package io.yamsergey.dta.mcp;

import io.yamsergey.dta.daemon.DaemonClient;
import io.yamsergey.dta.daemon.DaemonLauncher;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
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
    private static final McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(
        tools.jackson.databind.json.JsonMapper.builder().build());
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

        List<McpServerFeatures.SyncToolSpecification> tools = buildToolList();

        var server = io.modelcontextprotocol.server.McpServer.sync(
                new StdioServerTransportProvider(jsonMapper))
            .serverInfo("dta-mcp", getVersion())
            .capabilities(ServerCapabilities.builder()
                .tools(true)
                .build())
            .tools(tools)
            .build();

        log.info("MCP server started with {} tools (stdio transport)", tools.size());
        Thread.currentThread().join();
    }

    /**
     * Builds the full list of MCP tool specifications. Single source of truth —
     * shared by every transport (stdio, HTTP, future variants). Adding a new
     * tool means editing exactly one place: the appropriate {@code collectXxxTools}
     * method below.
     *
     * <p>Calling this triggers a lazy daemon connection on the first tool
     * invocation, not at build time — safe to call without a daemon running.</p>
     */
    public static List<McpServerFeatures.SyncToolSpecification> buildToolList() {
        List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();
        collectDeviceTools(tools);
        collectAppTools(tools);
        collectLayoutTools(tools);
        collectMockTools(tools);
        collectCdpTools(tools);
        collectRunTools(tools);
        collectDataTools(tools);
        return tools;
    }

    /**
     * Returns the shared {@link McpJsonMapper} configured for Jackson 3.
     * Transport entry points (stdio, HTTP) need this to construct their
     * transport providers.
     */
    public static McpJsonMapper getMcpJsonMapper() {
        return jsonMapper;
    }

    /**
     * Returns the plain names of every registered MCP tool. Exposed as a
     * {@code List<String>} so consumers (like the plugin's MCP tab UI) can
     * display the tool list without depending on MCP SDK types directly —
     * the SDK is an {@code implementation} dep of dta-mcp and isn't
     * transitively visible to dta-plugin.
     */
    public static List<String> getToolNames() {
        List<String> names = new ArrayList<>();
        for (var spec : buildToolList()) {
            names.add(spec.tool().name());
        }
        return names;
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
            tool("list_devices", "List connected Android devices and emulators",
                "{\"type\":\"object\",\"properties\":{}}"),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String json = getDaemon().devices();
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("list_devices", e);
                }
            }
        ));

        // list_apps
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("list_apps", "List debuggable apps with sidekick installed",
                schema("device", "string", "Device serial (optional)")),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String device = getString(args, "device");
                    String json = getDaemon().apps(device);
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("list_apps", e);
                }
            }
        ));

        // screenshot — when package is provided, also returns essential UI elements with screen coordinates
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("screenshot", "Capture a screenshot from the device. When package is provided, " +
                "also returns essential UI elements (buttons, text, inputs) with their screen coordinates " +
                "for tap targeting.",
                schema(Map.of(
                    "device", prop("string", "Device serial (optional)", false),
                    "package", prop("string", "App package name (optional — if provided, includes UI element positions)", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String device = getString(args, "device");
                    byte[] data = getDaemon().deviceScreenshot(device);
                    String base64 = Base64.getEncoder().encodeToString(data);
                    var imageContent = new McpSchema.ImageContent(null, base64, "image/png");

                    // If package provided, also fetch essential UI elements
                    String pkg = getString(args, "package");
                    if (pkg != null && !pkg.isEmpty()) {
                        try {
                            String treeJson = getDaemon().layoutTree(pkg, device, null, null, null, null);
                            log.info("Layout tree for screenshot: {} chars", treeJson != null ? treeJson.length() : "null");
                            String elements = extractEssentialElements(treeJson);
                            var textContent = new McpSchema.TextContent(elements);
                            return CallToolResult.builder().content(List.of(imageContent, textContent)).build();
                        } catch (Exception e) {
                            // Layout tree failed — return screenshot without elements
                            log.warn("Failed to fetch UI elements: {}", e.getMessage());
                        }
                    }

                    return CallToolResult.builder().content(List.of(imageContent)).build();
                } catch (Exception e) {
                    return friendlyError("screenshot", e);
                }
            }
        ));

        // scroll_screenshot
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("scroll_screenshot", "Capture a scrolling/long screenshot of scrollable content. " +
                "Auto-detects scrollable views and stitches multiple screenshots into one tall image.",
                schema(Map.of(
                    "device", prop("string", "Device serial (optional)", false),
                    "view_id", prop("string", "Resource ID of scrollable view (e.g., 'com.app:id/recycler'). Auto-detects if omitted.", false),
                    "scroll_to_top", prop("boolean", "Scroll to top before capturing (default: false)", false),
                    "max_captures", prop("integer", "Maximum screenshots to capture (default: 30)", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
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
                        var imageContent = new McpSchema.ImageContent(null, base64, "image/png");

                        ObjectNode metadata = mapper.createObjectNode();
                        if (node.has("width")) metadata.put("width", node.get("width").asInt());
                        if (node.has("height")) metadata.put("height", node.get("height").asInt());
                        if (node.has("captures")) metadata.put("captures", node.get("captures").asInt());
                        if (node.has("reachedEnd")) metadata.put("reachedEnd", node.get("reachedEnd").asBoolean());
                        if (node.has("scrollableView")) metadata.put("scrollableView", node.get("scrollableView").asText());
                        var textContent = new McpSchema.TextContent(mapper.writeValueAsString(metadata));

                        return CallToolResult.builder().content(List.of(imageContent, textContent)).build();
                    }
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("scroll_screenshot", e);
                }
            }
        ));

        // tap
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("tap", "Tap at screen coordinates",
                schema(Map.of(
                    "x", prop("integer", "X coordinate", true),
                    "y", prop("integer", "Y coordinate", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    int x = getInt(args, "x");
                    int y = getInt(args, "y");
                    String device = getString(args, "device");
                    String json = getDaemon().tap(x, y, device);
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("tap", e);
                }
            }
        ));

        // swipe
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("swipe", "Swipe from one point to another",
                schema(Map.of(
                    "x1", prop("integer", "Start X", true),
                    "y1", prop("integer", "Start Y", true),
                    "x2", prop("integer", "End X", true),
                    "y2", prop("integer", "End Y", true),
                    "duration", prop("integer", "Duration in ms (default 300)", false),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String device = getString(args, "device");
                    String json = getDaemon().swipe(getInt(args, "x1"), getInt(args, "y1"),
                        getInt(args, "x2"), getInt(args, "y2"), getInt(args, "duration", 300), device);
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("swipe", e);
                }
            }
        ));

        // input_text
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("input_text", "Input text (requires focus on text field)",
                schema(Map.of(
                    "text", prop("string", "Text to input", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String json = getDaemon().inputText(getString(args, "text"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("input_text", e);
                }
            }
        ));

        // press_key
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("press_key", "Press a key (BACK, HOME, ENTER, etc.)",
                schema(Map.of(
                    "key", prop("string", "Key code (BACK, HOME, ENTER, or number)", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String json = getDaemon().pressKey(getString(args, "key"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("press_key", e);
                }
            }
        ));
    }

    private static void collectAppTools(List<McpServerFeatures.SyncToolSpecification> tools) {
        // network_requests
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("network_requests", "List captured HTTP requests from an app",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String json = getDaemon().networkRequests(getString(args, "package"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("tool", e);
                }
            }
        ));

        // network_request
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("network_request", "Get detailed info about a specific HTTP request",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "request_id", prop("string", "Request ID from network_requests", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String json = getDaemon().networkRequest(
                        getString(args, "package"), getString(args, "request_id"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("tool", e);
                }
            }
        ));

        // websocket_connections
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("websocket_connections", "List captured WebSocket connections from an app",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String json = getDaemon().websocketConnections(getString(args, "package"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("tool", e);
                }
            }
        ));

        // websocket_connection
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("websocket_connection", "Get detailed info about a WebSocket connection including messages",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "connection_id", prop("string", "Connection ID", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String json = getDaemon().websocketConnection(
                        getString(args, "package"), getString(args, "connection_id"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("tool", e);
                }
            }
        ));

        // get_selected_elements
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("get_selected_elements", "Get all selected UI elements (highlighted on device)",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String json = getDaemon().getSelectedElements(getString(args, "package"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("tool", e);
                }
            }
        ));

        // select_element_at
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("select_element_at", "Add the UI element at given screen coordinates to selection (highlight on device)",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "x", prop("integer", "X coordinate", true),
                    "y", prop("integer", "Y coordinate", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String pkg = getString(args, "package");
                    String device = getString(args, "device");
                    int x = getInt(args, "x");
                    int y = getInt(args, "y");
                    String json = getDaemon().selectElementAt(pkg, device, x, y);
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("tool", e);
                }
            }
        ));

        // clear_element_selection
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("clear_element_selection", "Clear all element selections/highlights",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    getDaemon().clearSelectedElements(getString(args, "package"), getString(args, "device"));
                    return ok("{\"success\":true,\"message\":\"Element selection cleared\"}");
                } catch (Exception e) {
                    return friendlyError("tool", e);
                }
            }
        ));

        // remove_selected_element
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("remove_selected_element", "Remove a UI element from selection by coordinates",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "x", prop("integer", "X coordinate of element to remove", true),
                    "y", prop("integer", "Y coordinate of element to remove", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String pkg = getString(args, "package");
                    String device = getString(args, "device");
                    int x = getInt(args, "x");
                    int y = getInt(args, "y");
                    String elementJson = mapper.writeValueAsString(Map.of("x", x, "y", y));
                    String json = getDaemon().removeSelectedElement(pkg, device, elementJson);
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("tool", e);
                }
            }
        ));

        // get_selected_network_requests
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("get_selected_network_requests", "Get all selected network requests",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String json = getDaemon().getSelectedNetworkRequests(getString(args, "package"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("tool", e);
                }
            }
        ));

        // select_network_request
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("select_network_request", "Add a network request to selection by ID",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "request_id", prop("string", "Request ID to add to selection", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String pkg = getString(args, "package");
                    String requestId = getString(args, "request_id");
                    String device = getString(args, "device");
                    String selectionJson = mapper.writeValueAsString(Map.of("id", requestId));
                    String json = getDaemon().addSelectedNetworkRequest(pkg, device, selectionJson);
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("tool", e);
                }
            }
        ));

        // get_selected_websocket_messages
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("get_selected_websocket_messages", "Get all selected WebSocket messages",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String json = getDaemon().getSelectedWebSocketMessages(getString(args, "package"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("tool", e);
                }
            }
        ));

        // select_websocket_message
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("select_websocket_message", "Add a WebSocket message to selection by connection ID and message index",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "connection_id", prop("string", "WebSocket connection ID", true),
                    "message_index", prop("integer", "Message index (0-based)", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
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
                    return friendlyError("tool", e);
                }
            }
        ));

        // clear_network_requests
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("clear_network_requests", "Clear all captured HTTP requests from an app",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String json = getDaemon().clearNetworkRequests(getString(args, "package"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("tool", e);
                }
            }
        ));

        // clear_websocket_connections
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("clear_websocket_connections", "Clear all captured WebSocket connections from an app",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String json = getDaemon().clearWebsocketConnections(getString(args, "package"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("tool", e);
                }
            }
        ));

        // network_request_body
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("network_request_body", "Get the response body for a specific HTTP request",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "request_id", prop("string", "Request ID from network_requests", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String json = getDaemon().networkRequestBody(
                        getString(args, "package"), getString(args, "request_id"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("tool", e);
                }
            }
        ));

        // network_stats
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("network_stats", "Get network statistics for an app",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String json = getDaemon().networkStats(getString(args, "package"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("tool", e);
                }
            }
        ));

        // clear_network_selection
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("clear_network_selection", "Clear all network request selections",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    getDaemon().clearSelectedNetworkRequests(getString(args, "package"), getString(args, "device"));
                    return ok("{\"success\":true,\"message\":\"Network selection cleared\"}");
                } catch (Exception e) {
                    return friendlyError("tool", e);
                }
            }
        ));

        // remove_selected_network_request
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("remove_selected_network_request", "Remove a network request from selection by ID",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "request_id", prop("string", "Request ID to remove from selection", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String selectionJson = mapper.writeValueAsString(Map.of("id", getString(args, "request_id")));
                    String json = getDaemon().removeSelectedNetworkRequest(getString(args, "package"), getString(args, "device"), selectionJson);
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("tool", e);
                }
            }
        ));

        // clear_websocket_selection
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("clear_websocket_selection", "Clear all WebSocket message selections",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    getDaemon().clearSelectedWebSocketMessages(getString(args, "package"), getString(args, "device"));
                    return ok("{\"success\":true,\"message\":\"WebSocket selection cleared\"}");
                } catch (Exception e) {
                    return friendlyError("tool", e);
                }
            }
        ));

        // remove_selected_websocket_message
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("remove_selected_websocket_message", "Remove a WebSocket message from selection",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "connection_id", prop("string", "WebSocket connection ID", true),
                    "message_index", prop("integer", "Message index (0-based)", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String selectionJson = mapper.writeValueAsString(Map.of(
                        "connectionId", getString(args, "connection_id"),
                        "messageIndex", getInt(args, "message_index")
                    ));
                    String json = getDaemon().removeSelectedWebSocketMessage(
                        getString(args, "package"), getString(args, "device"), selectionJson);
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("tool", e);
                }
            }
        ));
    }

    private static void collectLayoutTools(List<McpServerFeatures.SyncToolSpecification> tools) {
        // layout_tree
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("layout_tree",
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
            (exchange, request) -> { var args = request.arguments();
                try {
                    String json = getDaemon().layoutTree(
                        getString(args, "package"), getString(args, "device"),
                        getString(args, "text"), getString(args, "type"),
                        getString(args, "resource_id"), getString(args, "view_id"));
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("tool", e);
                }
            }
        ));

        // layout_properties
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("layout_properties",
                "Get detailed ViewDebug properties for a specific view node. Returns all annotated properties " +
                "organized by category (identity, view, layout, drawing, focus, scrolling) with resolved " +
                "resource names. Use the drawingId from layout_tree results to identify the target view.",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "view_id", prop("string", "Use the drawingId field value from layout_tree results as this parameter", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String json = getDaemon().layoutProperties(
                        getString(args, "package"), getString(args, "view_id"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("tool", e);
                }
            }
        ));
    }

    private static void collectMockTools(List<McpServerFeatures.SyncToolSpecification> tools) {
        // mock_list_rules
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("mock_list_rules", "List all mock rules for HTTP and WebSocket mocking",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String json = getDaemon().mockRules(getString(args, "package"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("tool", e);
                }
            }
        ));

        // mock_create_rule
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("mock_create_rule", "Create a mock rule. Either provide request_id/message_id to create from captured data, OR provide type with other parameters to create from scratch.",
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
            (exchange, request) -> { var args = request.arguments();
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
                    return friendlyError("tool", e);
                }
            }
        ));

        // mock_update_rule
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("mock_update_rule", "Update a mock rule (enable/disable, modify response/message, set content pattern)",
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
            (exchange, request) -> { var args = request.arguments();
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
                    return friendlyError("tool", e);
                }
            }
        ));

        // mock_delete_rule
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("mock_delete_rule", "Delete a mock rule",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "rule_id", prop("string", "Mock rule ID to delete", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String json = getDaemon().deleteMockRule(
                        getString(args, "package"), getString(args, "rule_id"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("tool", e);
                }
            }
        ));

        // mock_config
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("mock_config", "Get or update global mock configuration",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "enabled", prop("boolean", "Enable/disable all mocking (optional, omit to just get config)", false),
                    "http_enabled", prop("boolean", "Enable/disable HTTP mocking", false),
                    "websocket_enabled", prop("boolean", "Enable/disable WebSocket mocking", false),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
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
                    return friendlyError("tool", e);
                }
            }
        ));
    }

    private static void collectCdpTools(List<McpServerFeatures.SyncToolSpecification> tools) {
        // cdp_watch_start
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("cdp_watch_start", "Start watching Custom Tabs network traffic via Chrome DevTools Protocol. Traffic will be captured automatically and stored alongside regular HTTP requests.",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial (optional)", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String json = getDaemon().cdpWatchStart(getString(args, "package"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("cdp_watch_start", e);
                }
            }
        ));

        // cdp_watch_stop
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("cdp_watch_stop", "Stop watching Custom Tabs network traffic",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial (optional)", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String json = getDaemon().cdpWatchStop(getString(args, "package"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("cdp_watch_stop", e);
                }
            }
        ));

        // cdp_watch_status
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("cdp_watch_status", "Check if Custom Tabs network watching is active",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial (optional)", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String json = getDaemon().cdpWatchStatus(getString(args, "package"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("cdp_watch_status", e);
                }
            }
        ));
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private static void collectDataTools(List<McpServerFeatures.SyncToolSpecification> tools) {
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("app_data",
                "Inspect app-local data stores: SQLite databases and SharedPreferences (including encrypted). " +
                "Use the 'command' parameter to select the operation.\n\n" +
                "Commands:\n" +
                "- list_databases: List all databases (detects Room, SQLCipher, WAL mode)\n" +
                "- database_schema: Show tables, columns, types, row counts for a database\n" +
                "- database_query: Execute a SQL query and return results as JSON\n" +
                "- list_prefs: List all SharedPreferences files (detects encrypted + backup status)\n" +
                "- read_prefs: Read all entries from a prefs file (auto-decrypts EncryptedSharedPreferences)\n" +
                "- write_prefs: Write entries to a prefs file\n" +
                "- authenticate: Show biometric/PIN prompt on device to unlock KeyStore-protected data. " +
                "Call this first if read_prefs returns authRequired:true or a database needs a KeyStore-derived passphrase.",
                schema(Map.of(
                    "command", prop("string", "Operation: list_databases, database_schema, database_query, list_prefs, read_prefs, write_prefs, authenticate", true),
                    "package", prop("string", "App package name (auto-detected if only one app)", false),
                    "device", prop("string", "Device serial (auto-detected if only one device)", false),
                    "database", prop("string", "Database name (for database_schema, database_query)", false),
                    "sql", prop("string", "SQL query (for database_query)", false),
                    "args", prop("array", "SQL bind arguments (for database_query)", false),
                    "read_only", prop("boolean", "If false, allows write SQL (INSERT/UPDATE/DELETE). Default: true", false),
                    "passphrase", prop("string", "Decryption passphrase for SQLCipher databases (for database_query, database_schema)", false),
                    "file", prop("string", "SharedPreferences file name (for read_prefs, write_prefs)", false),
                    "entries", prop("object", "Key-value pairs to write (for write_prefs)", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String command = getString(args, "command");
                    if (command == null) return errorResult("'command' is required");
                    String pkg = getString(args, "package");
                    String device = getString(args, "device");

                    return switch (command) {
                        case "authenticate" -> ok(getDaemon().authenticate(pkg, device));
                        case "list_databases" -> ok(getDaemon().listDatabases(pkg, device));
                        case "database_schema" -> {
                            String db = getString(args, "database");
                            if (db == null) yield errorResult("'database' parameter required for database_schema");
                            yield ok(getDaemon().databaseSchema(pkg, db, device));
                        }
                        case "database_query" -> {
                            String db = getString(args, "database");
                            String sql = getString(args, "sql");
                            if (db == null) yield errorResult("'database' parameter required for database_query");
                            if (sql == null) yield errorResult("'sql' parameter required for database_query");
                            boolean readOnly = !Boolean.FALSE.equals(args.get("read_only"));
                            String passphrase = getString(args, "passphrase");
                            @SuppressWarnings("unchecked")
                            List<String> sqlArgs = args.get("args") instanceof List ? (List<String>) args.get("args") : null;
                            ObjectNode body = mapper.createObjectNode();
                            body.put("sql", sql);
                            body.put("readOnly", readOnly);
                            if (passphrase != null) body.put("passphrase", passphrase);
                            if (sqlArgs != null) {
                                var arr = body.putArray("args");
                                sqlArgs.forEach(arr::add);
                            }
                            yield ok(getDaemon().databaseQuery(pkg, db, mapper.writeValueAsString(body), device));
                        }
                        case "list_prefs" -> ok(getDaemon().listSharedPrefs(pkg, device));
                        case "read_prefs" -> {
                            String file = getString(args, "file");
                            if (file == null) yield errorResult("'file' parameter required for read_prefs");
                            yield ok(getDaemon().readSharedPrefs(pkg, file, device));
                        }
                        case "write_prefs" -> {
                            String file = getString(args, "file");
                            @SuppressWarnings("unchecked")
                            Map<String, Object> entries = args.get("entries") instanceof Map ? (Map<String, Object>) args.get("entries") : null;
                            if (file == null) yield errorResult("'file' parameter required for write_prefs");
                            if (entries == null) yield errorResult("'entries' parameter required for write_prefs");
                            ObjectNode body = mapper.createObjectNode();
                            ObjectNode entriesNode = body.putObject("entries");
                            for (var entry : entries.entrySet()) {
                                if (entry.getValue() instanceof String s) entriesNode.put(entry.getKey(), s);
                                else if (entry.getValue() instanceof Number n) entriesNode.put(entry.getKey(), n.doubleValue());
                                else if (entry.getValue() instanceof Boolean b) entriesNode.put(entry.getKey(), b);
                                else entriesNode.put(entry.getKey(), String.valueOf(entry.getValue()));
                            }
                            yield ok(getDaemon().writeSharedPrefs(pkg, file, mapper.writeValueAsString(body), device));
                        }
                        default -> errorResult("Unknown command: " + command +
                            ". Use: list_databases, database_schema, database_query, list_prefs, read_prefs, write_prefs");
                    };
                } catch (Exception e) {
                    return friendlyError("app_data", e);
                }
            }
        ));
    }

    private static void collectRunTools(List<McpServerFeatures.SyncToolSpecification> tools) {
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("run_app",
                "Build and launch an Android app with dta-sidekick auto-injected for inspection. " +
                "Injects the sidekick dependency via Gradle init script, builds the APK, installs it on the device, " +
                "and launches the main activity. After launch, use layout/network/websocket tools to inspect the app.",
                schema(Map.of(
                    "project", prop("string", "Absolute path to the Android project root directory", true),
                    "variant", prop("string", "Build variant in camelCase (default: debug). " +
                        "Must be a debug variant — sidekick requires debuggable build. " +
                        "No flavors: debug. " +
                        "With flavors: stagingDebug, exampleAppUatDebug, freeProductionDebug.", false),
                    "module", prop("string", "Gradle module to build (default: :app)", false),
                    "device", prop("string", "Device serial number (optional, uses default device if omitted)", false),
                    "activity", prop("string", "Activity to launch (optional, auto-detected from APK if omitted). " +
                        "Accepts fully-qualified (io.example.MainActivity), relative (.MainActivity), or bare (MainActivity). " +
                        "Use this to override the default launcher when a debug build has multiple launcher activities.", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String project = getString(args, "project");
                    if (project == null) return errorResult("'project' parameter is required");
                    String variant = getString(args, "variant");
                    if (variant == null) variant = "debug";
                    String module = getString(args, "module");
                    if (module == null) module = ":app";
                    String device = getString(args, "device");
                    String activity = getString(args, "activity");

                    String json = getDaemon().runApp(project, device, variant, module, activity);
                    // Always return the full JSON payload — it carries the
                    // `manualSteps` runbook + `resolutionHint` which a sandboxed
                    // or repo-misconfigured agent needs to fall back or fix.
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("run_app", e);
                }
            }
        ));
    }

    private static CallToolResult ok(String json) {
        return CallToolResult.builder().content(List.of(new McpSchema.TextContent(json))).build();
    }

    /**
     * Gets a string argument. For "device" and "package" keys, auto-resolves
     * when the caller omits them and there's exactly one option — so agents
     * don't need to call list_devices / list_apps first in the common
     * single-device single-app scenario.
     */
    private static String getString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value != null) return value.toString();
        // Auto-resolve device and package when omitted
        if ("device".equals(key)) return resolveDevice(args);
        if ("package".equals(key)) return resolvePackage(args, resolveDevice(args));
        return null;
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

    /**
     * Resolves the device serial. If the caller omitted it, auto-selects
     * when exactly one device is connected. Returns null only when no devices
     * are connected; returns an error result when multiple devices exist and
     * the caller didn't disambiguate.
     */
    private static String resolveDevice(Map<String, Object> args) {
        Object raw = args.get("device");
        if (raw != null && !raw.toString().isEmpty()) return raw.toString();
        try {
            String json = getDaemon().devices();
            var node = mapper.readTree(json);
            var devices = node.path("devices");
            if (devices.isArray()) {
                List<String> serials = new ArrayList<>();
                for (var d : devices) {
                    if ("device".equals(d.path("state").asText())) {
                        serials.add(d.path("serial").asText());
                    }
                }
                if (serials.size() == 1) return serials.get(0);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Resolves the package name. If the caller omitted it, auto-selects when
     * exactly one app with sidekick is running on the resolved device.
     */
    private static String resolvePackage(Map<String, Object> args, String device) {
        Object raw = args.get("package");
        if (raw != null && !raw.toString().isEmpty()) return raw.toString();
        try {
            String json = getDaemon().apps(device);
            var node = mapper.readTree(json);
            var apps = node.path("apps");
            if (apps.isArray() && apps.size() == 1) {
                return apps.get(0).path("package").asText();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Turns exceptions into agent-friendly error messages with corrective
     * guidance, stripping Java stack traces that waste tokens and confuse.
     */
    private static CallToolResult friendlyError(String toolName, Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();

        // Strip stack traces and daemon HTTP wrapper noise
        if (msg.contains("HTTP GET failed") || msg.contains("HTTP POST failed")) {
            int idx = msg.indexOf("Caused by:");
            if (idx > 0) msg = msg.substring(0, idx).trim();
        }

        // Add corrective hints based on common patterns
        String hint = "";
        if (msg.contains("Sidekick not running")) {
            hint = "\nHint: The app must be running with dta-sidekick injected. "
                + "Use run_app to build and launch it, or check list_apps to see which apps have sidekick.";
        } else if (msg.contains("Connection to") && msg.contains("failed recently")) {
            hint = "\nHint: A recent connection attempt failed. The app may have crashed or been stopped. "
                + "Wait a few seconds and retry, or use list_apps to check if sidekick is still running.";
        } else if (msg.contains("timed out") || msg.contains("Read timed out")) {
            hint = "\nHint: The device or app is not responding. Check that the emulator/device is running "
                + "and the app is in the foreground.";
        } else if (msg.contains("No device") || msg.contains("device not found")) {
            hint = "\nHint: No Android device connected. Use list_devices to check connected devices.";
        }

        return CallToolResult.builder()
            .content(List.of(new McpSchema.TextContent(toolName + " failed: " + msg + hint)))
            .isError(true).build();
    }

    private static CallToolResult errorResult(String message) {
        return CallToolResult.builder().content(List.of(new McpSchema.TextContent("Error: " + message))).isError(true).build();
    }

    /**
     * Extracts essential UI elements (with text, roles, or content descriptions) from a layout tree JSON.
     * Returns a compact JSON string with element positions for tap targeting.
     */
    private static String extractEssentialElements(String treeJson) {
        try {
            var node = mapper.readTree(treeJson);
            var root = node.has("root") ? node.get("root") : node;
            var elements = new java.util.ArrayList<Map<String, Object>>();
            collectEssentialElements(root, elements);

            var result = new java.util.LinkedHashMap<String, Object>();
            result.put("elementCount", elements.size());
            result.put("elements", elements);
            log.debug("Extracted {} essential elements from tree (root class: {})",
                    elements.size(), root.has("className") ? root.get("className").asText() : "?");
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            log.warn("Failed to extract elements: {}", e.getMessage());
            return "{\"error\":\"Failed to extract elements: " + e.getMessage() + "\"}";
        }
    }

    private static void collectEssentialElements(tools.jackson.databind.JsonNode node,
                                                  java.util.List<Map<String, Object>> elements) {
        if (node == null) return;

        String text = node.has("text") ? node.get("text").asText() : null;
        String role = node.has("role") ? node.get("role").asText() : null;
        String contentDesc = node.has("contentDescription") ? node.get("contentDescription").asText() : null;
        String testTag = node.has("testTag") ? node.get("testTag").asText() : null;
        String className = node.has("className") ? node.get("className").asText() :
                           node.has("composable") ? node.get("composable").asText() : null;

        boolean isEssential = (text != null && !text.isEmpty())
                || (role != null && !role.isEmpty())
                || (contentDesc != null && !contentDesc.isEmpty())
                || (testTag != null && !testTag.isEmpty());

        if (isEssential && node.has("bounds")) {
            var bounds = node.get("bounds");
            int left = bounds.has("left") ? bounds.get("left").asInt() : 0;
            int top = bounds.has("top") ? bounds.get("top").asInt() : 0;
            int right = bounds.has("right") ? bounds.get("right").asInt() : 0;
            int bottom = bounds.has("bottom") ? bounds.get("bottom").asInt() : 0;
            int w = right - left;
            int h = bottom - top;

            if (w > 0 && h > 0) {
                var elem = new java.util.LinkedHashMap<String, Object>();
                if (className != null) elem.put("type", className);
                if (text != null) elem.put("text", text);
                if (role != null) elem.put("role", role);
                if (contentDesc != null) elem.put("contentDescription", contentDesc);
                if (testTag != null) elem.put("testTag", testTag);
                elem.put("bounds", Map.of(
                    "left", left, "top", top, "right", right, "bottom", bottom,
                    "centerX", left + w / 2, "centerY", top + h / 2
                ));
                elements.add(elem);
            }
        }

        // Recurse children
        if (node.has("children")) {
            for (var child : node.get("children")) {
                collectEssentialElements(child, elements);
            }
        }
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

    private static Tool tool(String name, String description, String inputSchema) {
        return Tool.builder().name(name).description(description).inputSchema(jsonMapper, inputSchema).build();
    }

    private static Map<String, Object> prop(String type, String description, boolean required) {
        Map<String, Object> map = new HashMap<>();
        map.put("type", type);
        map.put("description", description);
        map.put("required", required);
        return map;
    }

    /**
     * Returns the dta-mcp version read from {@code /version.properties}.
     * Public so HTTP transport entry points can pass it to {@code serverInfo()}.
     */
    public static String getVersion() {
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
