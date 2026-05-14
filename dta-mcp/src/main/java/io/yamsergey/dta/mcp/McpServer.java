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

    /**
     * Instructions surfaced to MCP clients during the {@code initialize}
     * handshake. Per the MCP spec, these become persistent guidance the
     * agent reads as part of its system context — much stickier than
     * per-tool descriptions for behavioral rules ("when X happens, do Y").
     *
     * <p>Scoped narrowly: only the diagnostic-bundle workflow. Other
     * behaviors (how to call list_apps before tap, etc.) belong in the
     * relevant tool descriptions because they're invocation-time
     * decisions, not session-level reflexes.</p>
     */
    private static final String SERVER_INSTRUCTIONS = String.join("\n",
        "When a user reports a problem (bug, hang, \"doesn't work\", \"stuck on X\", unexpected behavior) with the host Android app, sidekick, daemon, plugin, or any DTA tool:",
        "",
        "1. BEFORE asking the user diagnostic questions, call `dta_collect_diagnostic_bundle`. The result includes a structured summary (versions, BootstrapShim status, hook count, error counts, version mismatches) you should read directly, plus a path to a saved zip the user can attach to a bug report.",
        "",
        "2. Read `bootstrapShimAttached`, `jvmtiAgentAvailable`, `versionMismatch`, and the error counts from the summary. These triage 80% of issues without further questions.",
        "",
        "3. Only ask the user for things the bundle doesn't include — e.g., \"what were you doing when this started?\", \"is this reproducible?\", \"real device or emulator?\". Don't ask the user to manually grep logcat, pull files from the device, or run adb commands. The diagnostic tool does it.",
        "",
        "4. If the summary indicates a `versionMismatch`, surface that first — most \"doesn't work\" reports have a stale plugin/sidekick combo as the root cause.",
        "",
        "5. Check `daemonUptimeHours` in the state snapshot. A daemon that's been running for days (especially > 24h) is a strong staleness signal: the user likely rebuilt the CLI/plugin since but the JVM kept its old jars, so recent fixes (hook installs, capture, etc.) silently aren't in effect. Suggest reconnecting MCP / restarting the plugin to force a fresh daemon takeover.",
        "",
        "6. The bundle is redacted by default (host package name hashed, auth headers stripped). Mention this when the user wants to share it externally.",
        "",
        "For non-bug-report tasks (debugging your own work, exploring layouts, checking network requests as part of normal flow) you don't need to call this tool unless the user explicitly asks for \"diagnostics\" or \"logs\"."
    );

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
            .instructions(SERVER_INSTRUCTIONS)
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
        collectInterceptorTools(tools);
        collectCdpTools(tools);
        collectRunTools(tools);
        collectDataTools(tools);
        collectRuntimeTools(tools);
        collectDiagnosticTools(tools);
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
            tool("screenshot", "Capture a screenshot from the device along with essential UI elements " +
                "(buttons, text, inputs) and their screen coordinates for tap targeting. " +
                "If you omit `package` the daemon auto-detects the foreground app and returns its layout — " +
                "you don't need to know the package up-front.",
                schema(Map.of(
                    "device", prop("string", "Device serial (optional)", false),
                    "package", prop("string", "App package name (optional — auto-detected from the foreground app when omitted)", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String device = getString(args, "device");
                    byte[] data = getDaemon().deviceScreenshot(device);
                    String base64 = Base64.getEncoder().encodeToString(data);
                    var imageContent = new McpSchema.ImageContent(null, base64, "image/png");

                    // Always attempt to attach essential UI elements. The
                    // daemon auto-detects the foreground package when none
                    // is provided, falling back to a uiautomator dump for
                    // apps without sidekick. A failure here is non-fatal
                    // (the screenshot is still useful).
                    String pkg = getString(args, "package");
                    try {
                        String treeJson = getDaemon().layoutTree(pkg, device, null, null, null, null);
                        log.info("Layout tree for screenshot: {} chars", treeJson != null ? treeJson.length() : "null");
                        String elements = extractEssentialElements(treeJson);
                        var textContent = new McpSchema.TextContent(elements);
                        return CallToolResult.builder().content(List.of(imageContent, textContent)).build();
                    } catch (Exception e) {
                        log.warn("Failed to fetch UI elements: {}", e.getMessage());
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
            tool("swipe", "Swipe from start point to end point. " +
                "Default 500ms is reliably classified as a swipe; durations under 200ms " +
                "or distances under 24px may be interpreted as taps by Android.",
                schema(Map.of(
                    "startX", prop("integer", "Start X (in screen pixels)", true),
                    "startY", prop("integer", "Start Y (in screen pixels)", true),
                    "endX", prop("integer", "End X (in screen pixels)", true),
                    "endY", prop("integer", "End Y (in screen pixels)", true),
                    "duration", prop("integer", "Duration in ms (default 500; floored at 200 to avoid tap classification)", false),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    // Accept both new descriptive names AND the historical
                    // x1/y1/x2/y2 form, since pre-rename callers (and other
                    // models that learned them from older docs) still try
                    // them. Throws Missing required parameter only if BOTH
                    // forms are absent — that error then has the renamed
                    // schema attached via friendlyError so the caller learns
                    // the canonical names.
                    int startX = firstPresentInt(args, "startX", "x1");
                    int startY = firstPresentInt(args, "startY", "y1");
                    int endX = firstPresentInt(args, "endX", "x2");
                    int endY = firstPresentInt(args, "endY", "y2");
                    String device = getString(args, "device");
                    String json = getDaemon().swipe(startX, startY, endX, endY,
                        getInt(args, "duration", 500), device);
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
                    String json = getDaemon().inputText(requireString(args, "text"), getString(args, "device"));
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
                    String json = getDaemon().pressKey(requireString(args, "key"), getString(args, "device"));
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
                        getString(args, "package"), requireString(args, "request_id"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("network_request", e);
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
                    return friendlyError("websocket_connections", e);
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
                        getString(args, "package"), requireString(args, "connection_id"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("websocket_connection", e);
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
                    String requestId = requireString(args, "request_id");
                    String device = getString(args, "device");
                    String selectionJson = mapper.writeValueAsString(Map.of("id", requestId));
                    String json = getDaemon().addSelectedNetworkRequest(pkg, device, selectionJson);
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("select_network_request", e);
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
                        "connectionId", requireString(args, "connection_id"),
                        "messageIndex", getInt(args, "message_index")
                    ));
                    String json = getDaemon().addSelectedWebSocketMessage(pkg, device, selectionJson);
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("select_websocket_message", e);
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
            tool("network_request_body",
                "Get both the request and response bodies for a captured HTTP transaction. " +
                "Returns {id, request:{contentType,size,encoding,body}, response:{...}}. " +
                "encoding is 'text' for UTF-8 content or 'base64' for image/octet-stream/pdf/audio/video. " +
                "Use this when network_requests' inline truncation isn't enough — for example to inspect " +
                "the exact form-encoded payload an OAuth token POST sent, or the JSON a failing API call " +
                "returned. Either side can be absent (request still in flight, response failed) — the " +
                "corresponding sub-object's body field is omitted in that case.",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "request_id", prop("string", "Request ID from network_requests", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String json = getDaemon().networkRequestBody(
                        getString(args, "package"), requireString(args, "request_id"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("network_request_body", e);
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
                    String selectionJson = mapper.writeValueAsString(Map.of("id", requireString(args, "request_id")));
                    String json = getDaemon().removeSelectedNetworkRequest(getString(args, "package"), getString(args, "device"), selectionJson);
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("remove_selected_network_request", e);
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
                        "connectionId", requireString(args, "connection_id"),
                        "messageIndex", getInt(args, "message_index")
                    ));
                    String json = getDaemon().removeSelectedWebSocketMessage(
                        getString(args, "package"), getString(args, "device"), selectionJson);
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("remove_selected_websocket_message", e);
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
                "Use text/type/resource_id filters to reduce output. Use view_id to get a specific subtree. " +
                "If you omit `package`, the daemon auto-detects the foreground app from `dumpsys window` and " +
                "returns its layout (the resolved name appears in the response as `resolvedPackage`); filters " +
                "still require an explicit package because they are interpreted by the app's sidekick.",
                schema(Map.of(
                    "package", prop("string", "App package name (optional — auto-detected from foreground app when omitted; required when using filters)", false),
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
                        getString(args, "package"), requireString(args, "view_id"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("layout_properties", e);
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

    private static void collectInterceptorTools(List<McpServerFeatures.SyncToolSpecification> tools) {
        final String intercDoc =
            "On-device JavaScript interceptor for HTTP and WebSocket traffic. " +
            "Install a script with `interceptor_set`; sidekick evaluates it synchronously inside the " +
            "OkHttp / WebSocket adapters before each request goes out, each response is delivered to the " +
            "app, and every WebSocket frame in either direction.\n\n" +

            "## Handlers\n\n" +
            "Export any subset of these top-level functions. Missing handlers pass through unchanged. " +
            "Each handler receives a **wrapper object** (described below), mutates fields on it, and " +
            "returns the same object. Returning `null` drops the call.\n\n" +
            "    function onRequest(req)     { return req; }\n" +
            "    function onResponse(resp)   { return resp; }\n" +
            "    function onWsSend(frame)    { return frame; }\n" +
            "    function onWsReceive(frame) { return frame; }\n\n" +

            "## Wrapper shapes (NOT the raw payloads)\n\n" +
            "`req` (HTTP request):\n" +
            "  - req.url        — string (e.g. \"https://api.example.com/path\")\n" +
            "  - req.method     — string (e.g. \"GET\", \"POST\")\n" +
            "  - req.headers    — object: { \"Content-Type\": \"application/json\", … }\n" +
            "  - req.body       — string (UTF-8 decoded) or null\n" +
            "  - req.tag        — \"okhttp\" | \"urlconn\"\n\n" +
            "`resp` (HTTP response):\n" +
            "  - resp.url            — string (URL of the request this response is for)\n" +
            "  - resp.method         — string (\"GET\", \"POST\", …)\n" +
            "  - resp.status         — number (e.g. 200, 404)\n" +
            "  - resp.statusMessage  — string\n" +
            "  - resp.headers        — object\n" +
            "  - resp.body           — string (UTF-8 decoded) or null\n" +
            "  - resp.request        — full request peek: { url, method, headers, body, tag }.\n" +
            "                          Use this when matching on the request body or a header\n" +
            "                          the URL alone doesn't carry. resp.url / resp.method\n" +
            "                          mirror resp.request.url / .method for ergonomics.\n" +
            "  Mutating resp.url / resp.method has no effect — they're informational.\n\n" +
            "`frame` (WebSocket — text or binary):\n" +
            "  - frame.dir          — \"send\" | \"recv\"\n" +
            "  - frame.text         — string for text frames, null for binary\n" +
            "  - frame.binary       — array of byte ints (0–255) for binary frames, null for text\n" +
            "  - frame.connectionId — string (sidekick-side WS id)\n\n" +
            "Frames are NEVER raw payloads. To read a JSON message: `JSON.parse(frame.text)`. " +
            "To rewrite: `frame.text = JSON.stringify(modified); return frame;`. " +
            "To drop: `return null;`.\n\n" +

            "## Minimal working example\n\n" +
            "    function onWsReceive(f) {\n" +
            "      if (!f.text) return f;             // binary frame — leave it alone\n" +
            "      var msg = JSON.parse(f.text);\n" +
            "      if (msg.type === 'ping') return null;   // drop ping frames\n" +
            "      if (msg.from === 'Server') {\n" +
            "        msg.text = '[patched] ' + msg.text;\n" +
            "        f.text = JSON.stringify(msg);    // mutate in place\n" +
            "      }\n" +
            "      return f;                          // ALWAYS return the wrapper, never a string\n" +
            "    }\n\n" +

            "## Available in scope\n\n" +
            "  log(arg1, arg2, …)            — variadic; appends a line to the ring buffer that\n" +
            "                                    `interceptor_logs` returns. Args are stringified\n" +
            "                                    (objects via JSON.stringify when possible).\n" +
            "  state.get(key)                — persistent within an install (cleared on `interceptor_clear`)\n" +
            "  state.set(key, value)\n" +
            "  state.delete(key)\n" +
            "  state.clear()\n" +
            "  sleep(ms)                     — synchronously blocks the calling app thread.\n" +
            "                                    No upper bound — DTA is a dev tool, the agent decides.\n\n" +

            "## Wired adapters\n\n" +
            "  OkHttp                       — request + response, full mutation.\n" +
            "  OkHttp WebSocket             — send + receive, text and binary.\n" +
            "  Java-WebSocket               — send + receive, text and binary.\n" +
            "  nv-websocket-client          — send + receive, text and binary.\n" +
            "  HttpURLConnection            — NOT WIRED (target OkHttp clients for HTTP rewrites).\n" +
            "  Chrome / CCT / WebView       — NOT REACHABLE BY DESIGN (separate process / native net stack).\n\n" +

            "## Drop semantics\n\n" +
            "  HTTP   `onRequest`/`onResponse` returning `null` → synthetic 499 to the app.\n" +
            "         (The real request still goes on the wire — JVMTI hooks can't short-circuit.)\n" +
            "  WS     `onWsSend`/`onWsReceive` returning `null` → empty frame (server / listener\n" +
            "         sees no payload).\n\n" +

            "## Diagnostics\n\n" +
            "The `interceptor_set` response includes `sidekickVersion`. The first entry in " +
            "`interceptor_logs` after install is `\"interceptor installed (N chars) [sidekick=X.Y.Z]\"`. " +
            "If the version isn't the one you expect, the host app was built against an older sidekick " +
            "AAR and features wired in newer versions will fail silently.";

        // interceptor_set
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("interceptor_set",
                "Install or replace the interceptor script for an app. " + intercDoc,
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial (optional)", false),
                    "script", prop("string", "JavaScript source. May export onRequest, onResponse, onWsSend, onWsReceive.", true)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String json = getDaemon().setInterceptor(
                        getString(args, "package"), getString(args, "device"),
                        getString(args, "script"));
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("interceptor_set", e);
                }
            }
        ));

        // interceptor_clear
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("interceptor_clear",
                "Uninstall the active interceptor script for an app. State and logs are reset.",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial (optional)", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String json = getDaemon().clearInterceptor(
                        getString(args, "package"), getString(args, "device"));
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("interceptor_clear", e);
                }
            }
        ));

        // interceptor_logs
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("interceptor_logs",
                "Read entries from the interceptor's ring buffer (script `log()` output and caught errors). " +
                "Pass the highest `seq` returned previously as `since` to page forward; pass 0 to read all.",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial (optional)", false),
                    "since", prop("integer", "Return entries with seq strictly greater than this (default 0)", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    long since = getLong(args, "since", 0L);
                    String json = getDaemon().getInterceptorLogs(
                        getString(args, "package"), getString(args, "device"), since);
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("interceptor_logs", e);
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

        // cct_traces — full ring buffer of recent Custom Tab launches with
        // per-step timing. Use this when a CCT lands on a blank/loading
        // page and stays there: the trace's `finalState` plus the absent
        // step (e.g. no `frame_navigated`) tells you where the chain
        // broke. Pass `since=<seq>` to page forward.
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("cct_traces",
                "Diagnostic traces for recent Custom Tab launches. Each entry records the per-step " +
                "timeline (sse_received, ack_sent, polling_started, tab_matched, cdp_attached, " +
                "network_enabled, autoattach_enabled, page_enabled, page_navigate_sent, frame_navigated) " +
                "with timestamps and a finalState (ok / stuck / failed_*). When `finalState=stuck`, the " +
                "trace also includes a `chromeTargetsAtFailure` snapshot of /json/list. Use this to " +
                "diagnose why a CCT got stuck on the loading page (about:blank), or why network " +
                "capture didn't engage. Pass `since` (highest seq seen previously) to page forward; " +
                "pass 0 to read all (last 50 launches retained).",
                schema(Map.of(
                    "since", prop("integer", "Return entries with seq strictly greater than this (default 0)", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    long since = getLong(args, "since", 0L);
                    String json = getDaemon().getCctTraces(since);
                    return ok(json);
                } catch (Exception e) {
                    return friendlyError("cct_traces", e);
                }
            }
        ));
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private static void collectRuntimeTools(List<McpServerFeatures.SyncToolSpecification> tools) {
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("app_runtime",
                "Inspect app runtime state: navigation, lifecycle, memory, threads, ViewModels.\n\n" +
                "Commands:\n" +
                "- navigation_backstack: Current navigation backstack with routes and arguments\n" +
                "- navigation_graph: Full navigation graph structure (destinations, routes, deeplinks)\n" +
                "- lifecycle: All activities with their lifecycle state (RESUMED/PAUSED/STOPPED)\n" +
                "- memory: Heap and native memory usage (heapUsed, heapMax, nativeHeap)\n" +
                "- threads: List all threads with state. Set stack_traces=true for full traces.\n" +
                "- viewmodels: Live ViewModels with reflected LiveData/StateFlow/Compose state. Includes Activity-scoped (owner.type=\"Activity\") and Navigation 3 NavEntry-scoped (owner.type=\"NavEntry\", owner.key=NavKey toString). NavEntry-scoped ids are prefixed with `navEntry::` — paste the id back into saved_state for SavedStateHandle inspection.\n" +
                "- saved_state: SavedStateHandle contents for the ViewModel addressed by view_model_id (from the viewmodels command).\n" +
                "- app_functions: Enumerates androidx.appfunctions methods the host exposes to Gemini / system AI (Android 16+ framework). Reads the KSP-generated assets/app_functions_v2.xml — no extra deps required on the host. Each entry has {id, description, parameters[{name, isRequired, description, dataType{type, typeName, isNullable, dataTypeReference}}], response, enabledByDefault, schemaCategory/Name/Version}. Returns {functions: []} with a `note` when the host doesn't use AppFunctions.\n" +
                "- navigate: Push a destination onto the host's NavController (Navigation 2 / Compose Navigation). Requires `destination` (the route template or a literal route from navigation_graph) and optional `params` (object whose keys fill `{placeholder}` segments; extras become query params). Returns {status:\"ok\", route:\"...\"} or {error:\"...\"}. Navigation 3 (NavBackStack/NavKey) is NOT supported — use open_deeplink instead, or wait for the Nav 3 research thread to land.\n" +
                "- open_deeplink: Fire Intent.ACTION_VIEW with a URI. Works for any destination the app exposes via <intent-filter><data>. Requires `uri` (string). Inherits the host's task affinity (no external browser detour).",
                schema(Map.of(
                    "command", prop("string", "Operation: navigation_backstack, navigation_graph, lifecycle, memory, threads, viewmodels, saved_state, app_functions, navigate, open_deeplink", true),
                    "package", prop("string", "App package name (auto-detected if only one app)", false),
                    "device", prop("string", "Device serial (auto-detected if only one device)", false),
                    "stack_traces", prop("boolean", "Include stack traces for threads command (default: false)", false),
                    "view_model_id", prop("string", "Required for saved_state — the id field from a viewmodels response", false),
                    "destination", prop("string", "Required for navigate — the route template or literal route to navigate to.", false),
                    "params", prop("object", "Optional for navigate — map of route-placeholder → value. Extras become query params.", false),
                    "uri", prop("string", "Required for open_deeplink — the URI to launch via Intent.ACTION_VIEW.", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String command = getString(args, "command");
                    if (command == null) return errorResult("'command' is required");
                    String pkg = getString(args, "package");
                    String device = getString(args, "device");

                    return switch (command) {
                        case "navigation_backstack" -> ok(getDaemon().navigationBackstack(pkg, device));
                        case "navigation_graph" -> ok(getDaemon().navigationGraph(pkg, device));
                        case "lifecycle" -> ok(getDaemon().lifecycle(pkg, device));
                        case "memory" -> ok(getDaemon().memory(pkg, device));
                        case "threads" -> ok(getDaemon().threads(pkg, device,
                            Boolean.TRUE.equals(args.get("stack_traces"))));
                        case "viewmodels" -> ok(getDaemon().viewModels(pkg, device));
                        case "saved_state" -> {
                            String vmId = getString(args, "view_model_id");
                            if (vmId == null || vmId.isEmpty())
                                yield errorResult("'view_model_id' is required for saved_state");
                            yield ok(getDaemon().viewModelSavedState(pkg, vmId, device));
                        }
                        case "app_functions" -> ok(getDaemon().appFunctions(pkg, device));
                        case "navigate" -> {
                            String destination = getString(args, "destination");
                            if (destination == null || destination.isEmpty())
                                yield errorResult("'destination' is required for navigate");
                            // Forward {destination, params} verbatim as JSON body. We re-build the
                            // body here (rather than passing args directly) so unrelated MCP keys
                            // like `package`/`device` don't leak into the sidekick payload.
                            Map<String, Object> bodyMap = new java.util.HashMap<>();
                            bodyMap.put("destination", destination);
                            Object params = args.get("params");
                            if (params instanceof Map) bodyMap.put("params", params);
                            String body = new tools.jackson.databind.ObjectMapper().writeValueAsString(bodyMap);
                            yield ok(getDaemon().navigate(pkg, device, body));
                        }
                        case "open_deeplink" -> {
                            String uri = getString(args, "uri");
                            if (uri == null || uri.isEmpty())
                                yield errorResult("'uri' is required for open_deeplink");
                            String body = new tools.jackson.databind.ObjectMapper()
                                .writeValueAsString(Map.of("uri", uri));
                            yield ok(getDaemon().openDeepLink(pkg, device, body));
                        }
                        default -> errorResult("Unknown command: " + command);
                    };
                } catch (Exception e) {
                    return friendlyError("app_runtime", e);
                }
            }
        ));
    }

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
                "- list_files: Browse the app's data directory. Returns file names, sizes, types. " +
                "Use 'path' to navigate subdirectories (e.g. 'files', 'cache', 'shared_prefs').\n" +
                "- authenticate: Show biometric/PIN prompt on device to unlock KeyStore-protected data. " +
                "Call this first if read_prefs returns authRequired:true or a database needs a KeyStore-derived passphrase.",
                schema(Map.ofEntries(
                    Map.entry("command", prop("string", "Operation: list_files, list_databases, database_schema, database_query, list_prefs, read_prefs, write_prefs, authenticate", true)),
                    Map.entry("package", prop("string", "App package name (auto-detected if only one app)", false)),
                    Map.entry("device", prop("string", "Device serial (auto-detected if only one device)", false)),
                    Map.entry("database", prop("string", "Database name (for database_schema, database_query)", false)),
                    Map.entry("sql", prop("string", "SQL query (for database_query)", false)),
                    Map.entry("args", prop("array", "SQL bind arguments (for database_query)", false)),
                    Map.entry("read_only", prop("boolean", "If false, allows write SQL (INSERT/UPDATE/DELETE). Default: true", false)),
                    Map.entry("passphrase", prop("string", "Decryption passphrase for SQLCipher databases (for database_query, database_schema)", false)),
                    Map.entry("path", prop("string", "Relative path within app data dir (for list_files, e.g. 'files', 'cache')", false)),
                    Map.entry("file", prop("string", "SharedPreferences file name (for read_prefs, write_prefs)", false)),
                    Map.entry("entries", prop("object", "Key-value pairs to write (for write_prefs)", false))
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String command = getString(args, "command");
                    if (command == null) return errorResult("'command' is required");
                    String pkg = getString(args, "package");
                    String device = getString(args, "device");

                    return switch (command) {
                        case "list_files" -> ok(getDaemon().listFiles(pkg, getString(args, "path"), device));
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
                "and launches the main activity. After launch, use layout/network/websocket tools to inspect the app.\n\n" +
                "On success, the response includes a `shimStatus` object: " +
                "`{shimAttached, reachable, reason, detail, sidekickVersion}`. " +
                "If `shimAttached=false` (or `reachable=false` after the post-launch wait window), " +
                "inspection capabilities are NOT working even though the app launched — typically because the " +
                "build reused stale outputs with an old sidekick AAR. Surface this clearly to the user; " +
                "common reasons: `not_debuggable` (sidekick must be added via debugImplementation), " +
                "`agent_so_missing` / `system_load_failed` / `attach_jvmti_failed` (native-agent issues, often " +
                "stale build), `socket_unreachable` (sidekick didn't come up — try a clean build).",
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

    /**
     * Triage tool wired to the daemon's existing {@code /api/debug/export-logs}
     * endpoint. Surfaces the structured summary inline so the agent can
     * reason about it directly, AND saves the full zip to disk so the user
     * can attach it to a bug report. Server instructions push the agent to
     * call this on any "something's broken" signal from the user before
     * asking diagnostic questions.
     */
    private static void collectDiagnosticTools(List<McpServerFeatures.SyncToolSpecification> tools) {
        tools.add(new McpServerFeatures.SyncToolSpecification(
            tool("dta_collect_diagnostic_bundle",
                "Collects a triage bundle for the user's currently-connected app: versions " +
                "(plugin, daemon, sidekick), BootstrapShim/JVMTI agent state, registered hooks, " +
                "filtered logcat slice, sidekick file log, runtime state. Saves the zip to " +
                "~/.dta/diagnostics/ AND returns a structured inline summary the agent should " +
                "read directly. Call this FIRST whenever the user reports a bug, hang, " +
                "\"doesn't work\", \"stuck on X\", or any unexpected behavior with DTA / " +
                "sidekick / Android app capture. Don't ask the user to manually grep logcat " +
                "or pull files — this tool does it. Bundle is redacted by default (host " +
                "package hashed, auth headers stripped).",
                schema(Map.of(
                    "package", prop("string", "App package name. Auto-resolved when one app is connected; pass explicitly when multiple are.", false),
                    "device", prop("string", "Device serial. Auto-resolved when one device is present.", false),
                    "redact", prop("boolean", "Redact sensitive data (default: true). Pass false only when you control the bundle's destination and need raw URLs/headers/etc.", false)
                ))),
            (exchange, request) -> { var args = request.arguments();
                try {
                    String pkg = getString(args, "package");
                    if (pkg == null) return errorResult("'package' could not be auto-resolved — pass explicitly or call list_apps first");
                    String device = getString(args, "device");
                    boolean redact = !Boolean.FALSE.equals(args.get("redact"));

                    byte[] zip = getDaemon().exportDebugLogs(pkg, device, redact);

                    // Save to ~/.dta/diagnostics/dta-debug-<timestamp>.zip
                    String home = System.getProperty("user.home");
                    java.nio.file.Path outDir = java.nio.file.Path.of(home, ".dta", "diagnostics");
                    java.nio.file.Files.createDirectories(outDir);
                    String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
                    java.nio.file.Path zipPath = outDir.resolve("dta-debug-" + stamp + ".zip");
                    java.nio.file.Files.write(zipPath, zip);

                    // Extract state.json from the zip in-memory and surface
                    // the triage fields the agent actually needs. Full state
                    // is in the zip on disk for deeper inspection.
                    ObjectNode summary = mapper.createObjectNode();
                    summary.put("bundlePath", zipPath.toString());
                    summary.put("bundleSizeBytes", (long) zip.length);
                    summary.put("redacted", redact);

                    try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                            new java.io.ByteArrayInputStream(zip))) {
                        java.util.zip.ZipEntry entry;
                        while ((entry = zis.getNextEntry()) != null) {
                            if ("state.json".equals(entry.getName())) {
                                byte[] stateBytes = zis.readAllBytes();
                                tools.jackson.databind.JsonNode state = mapper.readTree(stateBytes);
                                summary.put("capturedAt", state.path("capturedAt").asText(""));
                                summary.put("device", state.path("device").asText(""));
                                summary.put("daemonVersion", state.path("daemonVersion").asText(""));
                                tools.jackson.databind.JsonNode diag = state.path("diagnostics");
                                if (!diag.isMissingNode()) {
                                    summary.put("sidekickVersion", diag.path("sidekickVersion").asText(""));
                                    summary.put("apiLevel", diag.path("apiLevel").asInt(0));
                                    summary.put("androidVersion", diag.path("androidVersion").asText(""));
                                    tools.jackson.databind.JsonNode shim = diag.path("bootstrapShim");
                                    summary.put("bootstrapShimAttached", shim.path("attached").asBoolean(false));
                                    if (shim.has("error")) summary.put("bootstrapShimError", shim.path("error").asText(""));
                                    tools.jackson.databind.JsonNode agent = diag.path("jvmtiAgent");
                                    summary.put("jvmtiAgentAvailable", agent.path("available").asBoolean(false));
                                    if (agent.has("initError")) summary.put("jvmtiAgentInitError", agent.path("initError").asText(""));
                                    tools.jackson.databind.JsonNode hooks = diag.path("hooks");
                                    summary.put("registeredHooksCount", hooks.isArray() ? hooks.size() : 0);
                                    tools.jackson.databind.JsonNode fileLog = diag.path("fileLogging");
                                    summary.put("fileLoggingEnabled", fileLog.path("enabled").asBoolean(false));
                                    summary.put("fileLogSizeBytes", fileLog.path("sizeBytes").asLong(0));
                                }
                            }
                            zis.closeEntry();
                        }
                    } catch (Exception e) {
                        summary.put("stateParseError", e.getMessage());
                    }

                    // Version-mismatch detection — the most common "doesn't
                    // work" root cause. Compare base versions (strip
                    // -SNAPSHOT.N suffix), report mismatch if any pair
                    // disagrees.
                    String dv = summary.path("daemonVersion").asText("");
                    String sv = summary.path("sidekickVersion").asText("");
                    String dvBase = baseVersion(dv);
                    String svBase = baseVersion(sv);
                    if (!dvBase.isEmpty() && !svBase.isEmpty() && !dvBase.equals(svBase)) {
                        summary.put("versionMismatch", "daemon " + dv + " vs sidekick " + sv);
                    }

                    // Quick error scan — counts only, full traces in the zip.
                    summary.put("errorsInLogcat", countOccurrences(zip, "logcat-filtered.txt", "FATAL EXCEPTION", "AndroidRuntime: java"));
                    summary.put("errorsInSidekickLog", countOccurrences(zip, "sidekick.log", " E/", "Error in onEnter"));

                    summary.put("nextSteps",
                        "Read the fields above first. If versionMismatch is non-null, that's likely the root cause. " +
                        "If bootstrapShimAttached is false on a debuggable app, boot-class hooks won't fire. " +
                        "Open " + zipPath + " for full state.json, sidekick.log, and logcat-filtered.txt. " +
                        "Share the zip path with the user when they want to attach it to a bug report.");

                    return ok(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
                } catch (Exception e) {
                    return friendlyError("dta_collect_diagnostic_bundle", e);
                }
            }
        ));
    }

    /**
     * Strips {@code -SNAPSHOT[.N]} suffix so we compare {@code 0.9.37} to
     * {@code 0.9.37}, not {@code 0.9.37-SNAPSHOT.42} to {@code 0.9.37-SNAPSHOT.41}.
     * Mirrors the comparison the plugin's Daemon panel does.
     */
    private static String baseVersion(String v) {
        if (v == null) return "";
        String s = v.replaceAll("-SNAPSHOT\\.\\d+$", "")
                    .replace("-SNAPSHOT", "")
                    .replaceAll("\\.+$", "");
        return ("standalone".equals(s) || "(unknown)".equals(s)) ? "" : s;
    }

    /**
     * Counts occurrences of any of {@code needles} in the named entry of
     * the zip. Used for quick error tallies in the diagnostic summary —
     * full traces stay in the zip on disk so we don't blow up the agent's
     * context window with stack-trace dumps it didn't ask for.
     */
    private static int countOccurrences(byte[] zip, String entryName, String... needles) {
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(zip))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entryName.equals(entry.getName())) {
                    zis.closeEntry();
                    continue;
                }
                String text = new String(zis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                int count = 0;
                for (String needle : needles) {
                    int idx = 0;
                    while ((idx = text.indexOf(needle, idx)) >= 0) {
                        count++;
                        idx += needle.length();
                    }
                }
                return count;
            }
        } catch (Exception ignored) {
        }
        return 0;
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

    /**
     * Like {@link #getString} but throws a friendly error when a required
     * argument is missing. Use this for params marked {@code required: true}
     * in the tool schema (other than {@code device} / {@code package}, which
     * auto-resolve in {@code getString}).
     */
    private static String requireString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value != null) {
            String s = value.toString();
            if (!s.isEmpty()) return s;
        }
        throw new IllegalArgumentException("Missing required parameter: " + key);
    }

    private static int getInt(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) return Integer.parseInt(s);
        throw new IllegalArgumentException("Missing required parameter: " + key);
    }

    /**
     * Returns the first parameter that's present, parsed as int. Used for
     * tool params that accept multiple aliases — typically a renamed param
     * with the legacy name kept as fallback for callers that learned the
     * old schema. Throws with both names listed when none are present, so
     * the caller learns the canonical name AND knows the old form is still
     * accepted.
     */
    private static int firstPresentInt(Map<String, Object> args, String preferredKey, String... aliases) {
        Object value = args.get(preferredKey);
        if (value == null) {
            for (String alias : aliases) {
                value = args.get(alias);
                if (value != null) break;
            }
        }
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) return Integer.parseInt(s);
        StringBuilder names = new StringBuilder(preferredKey);
        for (String alias : aliases) names.append(" (or ").append(alias).append(")");
        throw new IllegalArgumentException("Missing required parameter: " + names);
    }

    private static int getInt(Map<String, Object> args, String key, int defaultValue) {
        Object value = args.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) return Integer.parseInt(s);
        return defaultValue;
    }

    private static long getLong(Map<String, Object> args, String key, long defaultValue) {
        Object value = args.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) return Long.parseLong(s);
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
        if (msg.startsWith("Missing required parameter")) {
            // Surface the tool's actual schema so the caller doesn't have
            // to re-fetch tools/list to find the right param name. Common
            // failure mode: agent learned an old param name from training
            // data (x1/y1) when the current schema uses a renamed one
            // (startX/startY), or the agent skipped a required param.
            hint = "\nHint: Inspect the tool's `inputSchema` in tools/list "
                + "to see all required parameters and their canonical names. "
                + "Some tools accept legacy aliases (e.g. swipe accepts both "
                + "startX and x1) — the error message lists every accepted form.";
        } else if (msg.contains("ADB not found") || msg.contains("adb not found")) {
            hint = "\nHint: The daemon couldn't locate the ADB executable. "
                + "If you're running this MCP server outside Android Studio, ensure "
                + "ANDROID_HOME / ANDROID_SDK_ROOT is set, or that `adb` is on PATH. "
                + "If you're inside Android Studio, the plugin should auto-configure ADB — "
                + "this may indicate a daemon-side bug routing the configured path.";
        } else if (msg.contains("Sidekick not running") || msg.contains("not currently connected to a sidekick")) {
            hint = "\nHint: The app must be running with dta-sidekick injected. "
                + "Use list_apps to see which apps have sidekick installed, or run_app "
                + "to build and launch one. The package you passed may be wrong — "
                + "list_apps shows the canonical package names.";
        } else if (msg.contains("Connection to") && msg.contains("failed recently")) {
            hint = "\nHint: A recent connection attempt failed. The app may have crashed or been stopped. "
                + "Wait a few seconds and retry, or use list_apps to check if sidekick is still running.";
        } else if (msg.contains("timed out") || msg.contains("Read timed out")) {
            hint = "\nHint: The device or app is not responding. Check that the emulator/device is running "
                + "and the app is in the foreground.";
        } else if (msg.contains("No device") || msg.contains("device not found")) {
            hint = "\nHint: No Android device connected. Use list_devices to check connected devices.";
        } else if (msg.contains("Could not find scrollable view") || msg.contains("No scrollable views found")) {
            // Specific case BEFORE the generic 400 hint: the parameters were
            // fine, the current screen just has nothing to scroll. The
            // generic hint sends the agent on a wild goose chase checking
            // package/device/IDs, which won't help.
            hint = "\nHint: The current screen has no scrollable content (no ScrollView, RecyclerView, "
                + "ListView, or similar found in the layout). This is a screen-state issue, not a "
                + "parameter problem. Use the regular `screenshot` tool for a single capture, or "
                + "navigate to a screen with scrollable content (list, feed, settings) first. "
                + "If a scrollable view does exist but wasn't detected, pass its resource-id via "
                + "the `viewId` parameter — `layout_tree` shows resource IDs for visible elements.";
        } else if (msg.contains("HTTP error 400") || msg.contains("HTTP error 404")) {
            // Daemon returned a 4xx — usually a bad parameter value the
            // schema didn't catch (wrong package on this device, missing
            // viewId for a specific layout, etc.).
            hint = "\nHint: The daemon rejected this request — likely a bad parameter value. "
                + "Verify the package is currently connected (list_apps), the device serial "
                + "is reachable (list_devices), and any IDs you passed exist on the current screen.";
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
