package io.yamsergey.dta.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.yamsergey.dta.tools.android.inspect.compose.ComposeNodeFilter;
import io.yamsergey.dta.tools.android.inspect.compose.HealthResponse;
import io.yamsergey.dta.tools.android.inspect.compose.SidekickClient;
import io.yamsergey.dta.tools.sugar.Result;
import io.yamsergey.dta.tools.sugar.Success;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DTA MCP Server - Provides Android development tools via Model Context Protocol.
 */
public class McpServer {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Map<String, ConnectionInfo> connections = new ConcurrentHashMap<>();
    private static final String MCP_VERSION = getVersion();
    private static int nextPort = 18640;

    private record ConnectionInfo(String packageName, String device, int port, String sidekickVersion, String versionWarning) {}

    public static void main(String[] args) throws Exception {
        // Collect all tool specifications BEFORE building the server
        List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();
        collectDeviceTools(tools);
        collectAppTools(tools);
        collectMockTools(tools);

        // Build server with all tools registered upfront
        var server = io.modelcontextprotocol.server.McpServer.sync(
                new StdioServerTransportProvider(mapper))
            .serverInfo("dta-mcp", getVersion())
            .capabilities(ServerCapabilities.builder()
                .tools(true)
                .build())
            .tools(tools)
            .build();

        // Keep server running
        Thread.currentThread().join();
    }

    private static void collectDeviceTools(List<McpServerFeatures.SyncToolSpecification> tools) {
        // list_devices
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("list_devices", "List connected Android devices and emulators",
                "{\"type\":\"object\",\"properties\":{}}"),
            (exchange, args) -> {
                try {
                    var devices = AdbUtils.listDevices();
                    ObjectNode result = mapper.createObjectNode();
                    ArrayNode devicesArray = result.putArray("devices");
                    for (var device : devices) {
                        ObjectNode d = devicesArray.addObject();
                        d.put("serial", device.serial());
                        d.put("state", device.state());
                        if (device.model() != null) d.put("model", device.model());
                        if (device.product() != null) d.put("product", device.product());
                    }
                    return new CallToolResult(List.of(new McpSchema.TextContent(mapper.writeValueAsString(result))), false);
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
                    var sockets = AdbUtils.discoverSidekickSockets(device);
                    ObjectNode result = mapper.createObjectNode();
                    ArrayNode appsArray = result.putArray("apps");
                    for (var socket : sockets) {
                        ObjectNode app = appsArray.addObject();
                        app.put("package", socket.packageName());
                        app.put("socket", socket.socketName());
                    }
                    result.put("hint", "These apps have sidekick installed and can be inspected");
                    return new CallToolResult(List.of(new McpSchema.TextContent(mapper.writeValueAsString(result))), false);
                } catch (Exception e) {
                    return errorResult("Failed to list apps: " + e.getMessage());
                }
            }
        ));

        // screenshot
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("screenshot", "Capture a screenshot from the device",
                schema("device", "string", "Device serial (optional)")),
            (exchange, args) -> {
                try {
                    String device = getString(args, "device");
                    byte[] data = AdbUtils.captureScreenshot(device);
                    String base64 = Base64.getEncoder().encodeToString(data);
                    // Return as ImageContent: annotations, priority, data, mimeType
                    var imageContent = new McpSchema.ImageContent(List.of(), null, base64, "image/png");
                    return new CallToolResult(List.of(imageContent), false);
                } catch (Exception e) {
                    return errorResult("Failed to capture screenshot: " + e.getMessage());
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
                    boolean success = AdbUtils.tap(device, x, y);
                    return jsonResult(Map.of("success", success, "x", x, "y", y));
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
                    int x1 = getInt(args, "x1");
                    int y1 = getInt(args, "y1");
                    int x2 = getInt(args, "x2");
                    int y2 = getInt(args, "y2");
                    int duration = getInt(args, "duration", 300);
                    String device = getString(args, "device");
                    boolean success = AdbUtils.swipe(device, x1, y1, x2, y2, duration);
                    return jsonResult(Map.of("success", success));
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
                    String text = getString(args, "text");
                    String device = getString(args, "device");
                    boolean success = AdbUtils.inputText(device, text);
                    return jsonResult(Map.of("success", success));
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
                    String key = getString(args, "key");
                    String device = getString(args, "device");
                    boolean success = AdbUtils.pressKey(device, key);
                    return jsonResult(Map.of("success", success, "key", key));
                } catch (Exception e) {
                    return errorResult("Failed to press key: " + e.getMessage());
                }
            }
        ));
    }

    private static void collectAppTools(List<McpServerFeatures.SyncToolSpecification> tools) {
        // compose_tree
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("compose_tree", "Get Compose UI hierarchy. Use text/type filters to reduce output.",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial", false),
                    "text", prop("string", "Filter: find elements containing this text", false),
                    "type", prop("string", "Filter: find elements of this type (e.g., Button, Text)", false)
                ))),
            (exchange, args) -> {
                try {
                    String pkg = getString(args, "package");
                    String device = getString(args, "device");
                    String textFilter = getString(args, "text");
                    String typeFilter = getString(args, "type");

                    return withSidekick(pkg, device, (client, versionWarning) -> {
                        Result<String> result = client.getComposeTree();
                        if (result instanceof Success<String> success) {
                            String json = success.value();

                            // Apply filters if specified
                            if (textFilter != null || typeFilter != null) {
                                ComposeNodeFilter filter = ComposeNodeFilter.builder()
                                    .textPattern(textFilter)
                                    .composablePattern(typeFilter)
                                    .build();
                                String filtered = filter.filter(json);
                                ObjectNode response = mapper.createObjectNode();
                                response.put("package", pkg);
                                if (versionWarning != null) response.put("warning", versionWarning);
                                response.set("filters", mapper.createObjectNode()
                                    .put("text", textFilter)
                                    .put("type", typeFilter));
                                response.set("matches", mapper.readTree(filtered));
                                return new CallToolResult(List.of(new McpSchema.TextContent(mapper.writeValueAsString(response))), false);
                            }

                            return resultWithWarning(json, versionWarning);
                        }
                        return errorResult("Failed to get compose tree");
                    });
                } catch (Exception e) {
                    return errorResult("Failed: " + e.getMessage());
                }
            }
        ));

        // network_requests
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("network_requests", "List captured HTTP requests from an app",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, args) -> {
                try {
                    String pkg = getString(args, "package");
                    String device = getString(args, "device");

                    return withSidekick(pkg, device, (client, versionWarning) -> {
                        Result<String> result = client.getNetworkRequests();
                        if (result instanceof Success<String> success) {
                            return resultWithWarning(success.value(), versionWarning);
                        }
                        return errorResult("Failed to get network requests");
                    });
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
                    String pkg = getString(args, "package");
                    String requestId = getString(args, "request_id");
                    String device = getString(args, "device");

                    return withSidekick(pkg, device, (client, versionWarning) -> {
                        Result<String> result = client.getNetworkRequest(requestId);
                        if (result instanceof Success<String> success) {
                            return new CallToolResult(List.of(new McpSchema.TextContent(success.value())), false);
                        }
                        return errorResult("Failed to get network request");
                    });
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
                    String pkg = getString(args, "package");
                    String device = getString(args, "device");

                    return withSidekick(pkg, device, (client, versionWarning) -> {
                        Result<String> result = client.getWebSocketConnections();
                        if (result instanceof Success<String> success) {
                            return resultWithWarning(success.value(), versionWarning);
                        }
                        return errorResult("Failed to get websocket connections");
                    });
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
                    String pkg = getString(args, "package");
                    String connectionId = getString(args, "connection_id");
                    String device = getString(args, "device");

                    return withSidekick(pkg, device, (client, versionWarning) -> {
                        Result<String> result = client.getWebSocketConnection(connectionId);
                        if (result instanceof Success<String> success) {
                            return new CallToolResult(List.of(new McpSchema.TextContent(success.value())), false);
                        }
                        return errorResult("Failed to get websocket connection");
                    });
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
                    String pkg = getString(args, "package");
                    String device = getString(args, "device");

                    return withSidekick(pkg, device, (client, versionWarning) -> {
                        Result<String> result = client.getSelectedElements();
                        if (result instanceof Success<String> success) {
                            return new CallToolResult(List.of(new McpSchema.TextContent(success.value())), false);
                        }
                        return errorResult("Failed to get selected elements");
                    });
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
                    int x = getInt(args, "x");
                    int y = getInt(args, "y");
                    String device = getString(args, "device");

                    return withSidekick(pkg, device, (client, versionWarning) -> {
                        // First, find the element at coordinates
                        Result<String> hitResult = client.getElementAtCoordinates(x, y);
                        if (hitResult instanceof Success<String> hit) {
                            // Parse the element and add it to selection
                            JsonNode hitNode = mapper.readTree(hit.value());
                            if (hitNode.has("element") && !hitNode.get("element").isNull()) {
                                JsonNode element = hitNode.get("element");
                                // Add to selection
                                Result<String> selectResult = client.addSelectedElement(mapper.writeValueAsString(element));
                                if (selectResult instanceof Success<String>) {
                                    return new CallToolResult(List.of(new McpSchema.TextContent(hit.value())), false);
                                }
                            }
                            return new CallToolResult(List.of(new McpSchema.TextContent(hit.value())), false);
                        }
                        return errorResult("Failed to find element at coordinates");
                    });
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
                    String pkg = getString(args, "package");
                    String device = getString(args, "device");

                    return withSidekick(pkg, device, (client, versionWarning) -> {
                        Result<String> result = client.clearSelectedElements();
                        if (result instanceof Success<String> success) {
                            return jsonResult(Map.of("success", true, "message", "Element selection cleared"));
                        }
                        return errorResult("Failed to clear element selection");
                    });
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
                    int x = getInt(args, "x");
                    int y = getInt(args, "y");
                    String device = getString(args, "device");

                    return withSidekick(pkg, device, (client, versionWarning) -> {
                        // First, find the element at coordinates
                        Result<String> hitResult = client.getElementAtCoordinates(x, y);
                        if (hitResult instanceof Success<String> hit) {
                            JsonNode hitNode = mapper.readTree(hit.value());
                            if (hitNode.has("element") && !hitNode.get("element").isNull()) {
                                JsonNode element = hitNode.get("element");
                                // Remove from selection
                                Result<String> removeResult = client.removeSelectedElement(mapper.writeValueAsString(element));
                                if (removeResult instanceof Success<String> success) {
                                    return new CallToolResult(List.of(new McpSchema.TextContent(success.value())), false);
                                }
                            }
                            return jsonResult(Map.of("success", false, "message", "No element found at coordinates"));
                        }
                        return errorResult("Failed to find element at coordinates");
                    });
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
                    String pkg = getString(args, "package");
                    String device = getString(args, "device");

                    return withSidekick(pkg, device, (client, versionWarning) -> {
                        Result<String> result = client.getSelectedNetworkRequests();
                        if (result instanceof Success<String> success) {
                            return new CallToolResult(List.of(new McpSchema.TextContent(success.value())), false);
                        }
                        return errorResult("Failed to get selected network requests");
                    });
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

                    return withSidekick(pkg, device, (client, versionWarning) -> {
                        // Create selection JSON with the request ID
                        String selectionJson = mapper.writeValueAsString(Map.of("id", requestId));
                        Result<String> result = client.addSelectedNetworkRequest(selectionJson);
                        if (result instanceof Success<String> success) {
                            return new CallToolResult(List.of(new McpSchema.TextContent(success.value())), false);
                        }
                        return errorResult("Failed to add network request to selection");
                    });
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
                    String pkg = getString(args, "package");
                    String device = getString(args, "device");

                    return withSidekick(pkg, device, (client, versionWarning) -> {
                        Result<String> result = client.getSelectedWebSocketMessages();
                        if (result instanceof Success<String> success) {
                            return new CallToolResult(List.of(new McpSchema.TextContent(success.value())), false);
                        }
                        return errorResult("Failed to get selected websocket messages");
                    });
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
                    String connectionId = getString(args, "connection_id");
                    int messageIndex = getInt(args, "message_index");
                    String device = getString(args, "device");

                    return withSidekick(pkg, device, (client, versionWarning) -> {
                        // Create selection JSON
                        String selectionJson = mapper.writeValueAsString(Map.of(
                            "connectionId", connectionId,
                            "messageIndex", messageIndex
                        ));
                        Result<String> result = client.addSelectedWebSocketMessage(selectionJson);
                        if (result instanceof Success<String> success) {
                            return new CallToolResult(List.of(new McpSchema.TextContent(success.value())), false);
                        }
                        return errorResult("Failed to add websocket message to selection");
                    });
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
                    String pkg = getString(args, "package");
                    String device = getString(args, "device");

                    return withSidekick(pkg, device, (client, versionWarning) -> {
                        Result<String> result = client.clearNetworkRequests();
                        if (result instanceof Success<String> success) {
                            return new CallToolResult(List.of(new McpSchema.TextContent(success.value())), false);
                        }
                        return errorResult("Failed to clear network requests");
                    });
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
                    String pkg = getString(args, "package");
                    String device = getString(args, "device");

                    return withSidekick(pkg, device, (client, versionWarning) -> {
                        Result<String> result = client.clearWebSocketConnections();
                        if (result instanceof Success<String> success) {
                            return new CallToolResult(List.of(new McpSchema.TextContent(success.value())), false);
                        }
                        return errorResult("Failed to clear websocket connections");
                    });
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
                    String pkg = getString(args, "package");
                    String requestId = getString(args, "request_id");
                    String device = getString(args, "device");

                    return withSidekick(pkg, device, (client, versionWarning) -> {
                        Result<String> result = client.getNetworkRequestBody(requestId);
                        if (result instanceof Success<String> success) {
                            return new CallToolResult(List.of(new McpSchema.TextContent(success.value())), false);
                        }
                        return errorResult("Failed to get network request body");
                    });
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
                    String pkg = getString(args, "package");
                    String device = getString(args, "device");

                    return withSidekick(pkg, device, (client, versionWarning) -> {
                        Result<String> result = client.getNetworkStats();
                        if (result instanceof Success<String> success) {
                            return new CallToolResult(List.of(new McpSchema.TextContent(success.value())), false);
                        }
                        return errorResult("Failed to get network stats");
                    });
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
                    String pkg = getString(args, "package");
                    String device = getString(args, "device");

                    return withSidekick(pkg, device, (client, versionWarning) -> {
                        Result<String> result = client.clearSelectedNetworkRequests();
                        if (result instanceof Success<String> success) {
                            return jsonResult(Map.of("success", true, "message", "Network selection cleared"));
                        }
                        return errorResult("Failed to clear network selection");
                    });
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
                    String pkg = getString(args, "package");
                    String requestId = getString(args, "request_id");
                    String device = getString(args, "device");

                    return withSidekick(pkg, device, (client, versionWarning) -> {
                        String selectionJson = mapper.writeValueAsString(Map.of("id", requestId));
                        Result<String> result = client.removeSelectedNetworkRequest(selectionJson);
                        if (result instanceof Success<String> success) {
                            return new CallToolResult(List.of(new McpSchema.TextContent(success.value())), false);
                        }
                        return errorResult("Failed to remove network request from selection");
                    });
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
                    String pkg = getString(args, "package");
                    String device = getString(args, "device");

                    return withSidekick(pkg, device, (client, versionWarning) -> {
                        Result<String> result = client.clearSelectedWebSocketMessages();
                        if (result instanceof Success<String> success) {
                            return jsonResult(Map.of("success", true, "message", "WebSocket selection cleared"));
                        }
                        return errorResult("Failed to clear websocket selection");
                    });
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
                    String pkg = getString(args, "package");
                    String connectionId = getString(args, "connection_id");
                    int messageIndex = getInt(args, "message_index");
                    String device = getString(args, "device");

                    return withSidekick(pkg, device, (client, versionWarning) -> {
                        String selectionJson = mapper.writeValueAsString(Map.of(
                            "connectionId", connectionId,
                            "messageIndex", messageIndex
                        ));
                        Result<String> result = client.removeSelectedWebSocketMessage(selectionJson);
                        if (result instanceof Success<String> success) {
                            return new CallToolResult(List.of(new McpSchema.TextContent(success.value())), false);
                        }
                        return errorResult("Failed to remove websocket message from selection");
                    });
                } catch (Exception e) {
                    return errorResult("Failed: " + e.getMessage());
                }
            }
        ));
    }

    // ========================================================================
    // Mock tools
    // ========================================================================

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
                    String pkg = getString(args, "package");
                    String device = getString(args, "device");

                    return withSidekick(pkg, device, (client, versionWarning) -> {
                        Result<String> result = client.getMockRules();
                        if (result instanceof Success<String> success) {
                            return new CallToolResult(List.of(new McpSchema.TextContent(success.value())), false);
                        }
                        return errorResult("Failed to get mock rules");
                    });
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

                    return withSidekick(pkg, device, (client, versionWarning) -> {
                        if (fromCaptured) {
                            Result<String> result;
                            if (requestId != null) {
                                result = client.createMockFromRequest(requestId);
                            } else {
                                result = client.createMockFromMessage(messageId);
                            }
                            if (result instanceof Success<String> success) {
                                return new CallToolResult(List.of(new McpSchema.TextContent(success.value())), false);
                            }
                            return errorResult("Failed to create mock rule");
                        } else {
                            // Create from scratch
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

                            Result<String> result = client.createMockRule(mapper.writeValueAsString(rule));
                            if (result instanceof Success<String> success) {
                                return new CallToolResult(List.of(new McpSchema.TextContent(success.value())), false);
                            }
                            return errorResult("Failed to create mock rule");
                        }
                    });
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

                    // Build update JSON
                    ObjectNode update = mapper.createObjectNode();
                    if (args.containsKey("enabled")) {
                        update.put("enabled", (Boolean) args.get("enabled"));
                    }
                    if (args.containsKey("delay_ms")) {
                        update.put("delayMs", getInt(args, "delay_ms", 0));
                    }
                    if (args.containsKey("content_pattern")) {
                        update.put("contentPattern", getString(args, "content_pattern"));
                    }
                    if (args.containsKey("direction")) {
                        update.put("direction", getString(args, "direction"));
                    }

                    // Build mock response updates if needed (for HTTP rules)
                    if (args.containsKey("status_code") || args.containsKey("body")) {
                        ObjectNode mockResponse = update.putObject("mockResponse");
                        if (args.containsKey("status_code")) {
                            mockResponse.put("statusCode", getInt(args, "status_code", 200));
                        }
                        if (args.containsKey("body")) {
                            mockResponse.put("body", getString(args, "body"));
                        }
                    }

                    // Build mock message updates if needed (for WebSocket rules)
                    if (args.containsKey("text_payload") || args.containsKey("drop")) {
                        ObjectNode mockMessage = update.putObject("mockMessage");
                        if (args.containsKey("text_payload")) {
                            mockMessage.put("textPayload", getString(args, "text_payload"));
                        }
                        if (args.containsKey("drop")) {
                            mockMessage.put("drop", (Boolean) args.get("drop"));
                        }
                    }

                    return withSidekick(pkg, device, (client, versionWarning) -> {
                        Result<String> result = client.updateMockRule(ruleId, mapper.writeValueAsString(update));
                        if (result instanceof Success<String> success) {
                            return new CallToolResult(List.of(new McpSchema.TextContent(success.value())), false);
                        }
                        return errorResult("Failed to update mock rule");
                    });
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
                    String pkg = getString(args, "package");
                    String ruleId = getString(args, "rule_id");
                    String device = getString(args, "device");

                    return withSidekick(pkg, device, (client, versionWarning) -> {
                        Result<String> result = client.deleteMockRule(ruleId);
                        if (result instanceof Success<String> success) {
                            return new CallToolResult(List.of(new McpSchema.TextContent(success.value())), false);
                        }
                        return errorResult("Failed to delete mock rule");
                    });
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

                    // If any config values provided, update; otherwise just get
                    boolean hasUpdate = args.containsKey("enabled") ||
                                       args.containsKey("http_enabled") ||
                                       args.containsKey("websocket_enabled");

                    return withSidekick(pkg, device, (client, versionWarning) -> {
                        if (hasUpdate) {
                            ObjectNode configUpdate = mapper.createObjectNode();
                            if (args.containsKey("enabled")) {
                                configUpdate.put("enabled", (Boolean) args.get("enabled"));
                            }
                            if (args.containsKey("http_enabled")) {
                                configUpdate.put("httpMockingEnabled", (Boolean) args.get("http_enabled"));
                            }
                            if (args.containsKey("websocket_enabled")) {
                                configUpdate.put("webSocketMockingEnabled", (Boolean) args.get("websocket_enabled"));
                            }
                            Result<String> result = client.updateMockConfig(mapper.writeValueAsString(configUpdate));
                            if (result instanceof Success<String> success) {
                                return new CallToolResult(List.of(new McpSchema.TextContent(success.value())), false);
                            }
                            return errorResult("Failed to update mock config");
                        } else {
                            Result<String> result = client.getMockConfig();
                            if (result instanceof Success<String> success) {
                                return new CallToolResult(List.of(new McpSchema.TextContent(success.value())), false);
                            }
                            return errorResult("Failed to get mock config");
                        }
                    });
                } catch (Exception e) {
                    return errorResult("Failed: " + e.getMessage());
                }
            }
        ));
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    @FunctionalInterface
    private interface SidekickAction {
        CallToolResult execute(SidekickClient client, String versionWarning) throws Exception;
    }

    private static CallToolResult withSidekick(String packageName, String device, SidekickAction action) throws Exception {
        String key = (device != null ? device : "default") + ":" + packageName;

        ConnectionInfo conn = connections.get(key);
        if (conn == null) {
            int port = nextPort++;
            String socketName = "dta_sidekick_" + packageName;
            try {
                AdbUtils.setupPortForward(device, port, socketName);
            } catch (Exception e) {
                throw new RuntimeException("Failed to setup port forward", e);
            }

            // Check version on first connection
            SidekickClient tempClient = SidekickClient.builder()
                .packageName(packageName)
                .port(port)
                .deviceSerial(device)
                .build();

            String sidekickVersion = null;
            String versionWarning = null;
            Result<HealthResponse> healthResult = tempClient.checkHealthTyped();
            if (healthResult instanceof Success<HealthResponse> success) {
                sidekickVersion = success.value().version();
                if (!isVersionCompatible(MCP_VERSION, sidekickVersion)) {
                    versionWarning = "Version mismatch: MCP v" + MCP_VERSION + ", Sidekick v" + sidekickVersion;
                }
            }

            conn = new ConnectionInfo(packageName, device, port, sidekickVersion, versionWarning);
            connections.put(key, conn);
        }

        SidekickClient client = SidekickClient.builder()
            .packageName(packageName)
            .port(conn.port())
            .deviceSerial(device)
            .build();

        return action.execute(client, conn.versionWarning());
    }

    /**
     * Checks if the tool version is compatible with sidekick version.
     * Major and minor versions must match.
     */
    private static boolean isVersionCompatible(String toolVersion, String sidekickVersion) {
        if (toolVersion == null || sidekickVersion == null) {
            return true; // Can't check, assume compatible
        }
        if ("unknown".equals(toolVersion) || "unknown".equals(sidekickVersion)) {
            return true;
        }

        String[] tool = toolVersion.split("\\.");
        String[] sidekick = sidekickVersion.split("\\.");

        if (tool.length < 2 || sidekick.length < 2) {
            return true;
        }

        return tool[0].equals(sidekick[0]) && tool[1].equals(sidekick[1]);
    }

    /**
     * Creates a tool result with an optional version warning.
     */
    private static CallToolResult resultWithWarning(String json, String versionWarning) throws Exception {
        if (versionWarning != null) {
            // Parse and add warning to JSON
            ObjectNode node = (ObjectNode) mapper.readTree(json);
            node.put("warning", versionWarning);
            return new CallToolResult(List.of(new McpSchema.TextContent(mapper.writeValueAsString(node))), false);
        }
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

    private static CallToolResult jsonResult(Map<String, Object> data) {
        try {
            return new CallToolResult(List.of(new McpSchema.TextContent(mapper.writeValueAsString(data))), false);
        } catch (Exception e) {
            return errorResult("JSON serialization failed: " + e.getMessage());
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
            ArrayNode required = schema.putArray("required");

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
