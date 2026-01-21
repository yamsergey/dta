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
    private static int nextPort = 18640;

    private record ConnectionInfo(String packageName, String device, int port) {}

    public static void main(String[] args) throws Exception {
        // Collect all tool specifications BEFORE building the server
        List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();
        collectDeviceTools(tools);
        collectAppTools(tools);

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

                    return withSidekick(pkg, device, client -> {
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
                                response.set("filters", mapper.createObjectNode()
                                    .put("text", textFilter)
                                    .put("type", typeFilter));
                                response.set("matches", mapper.readTree(filtered));
                                return new CallToolResult(List.of(new McpSchema.TextContent(mapper.writeValueAsString(response))), false);
                            }

                            return new CallToolResult(List.of(new McpSchema.TextContent(json)), false);
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

                    return withSidekick(pkg, device, client -> {
                        Result<String> result = client.getNetworkRequests();
                        if (result instanceof Success<String> success) {
                            return new CallToolResult(List.of(new McpSchema.TextContent(success.value())), false);
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

                    return withSidekick(pkg, device, client -> {
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

                    return withSidekick(pkg, device, client -> {
                        Result<String> result = client.getWebSocketConnections();
                        if (result instanceof Success<String> success) {
                            return new CallToolResult(List.of(new McpSchema.TextContent(success.value())), false);
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

                    return withSidekick(pkg, device, client -> {
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

        // get_selected_element
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("get_selected_element", "Get the currently selected UI element (highlighted on device)",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, args) -> {
                try {
                    String pkg = getString(args, "package");
                    String device = getString(args, "device");

                    return withSidekick(pkg, device, client -> {
                        Result<String> result = client.getSelectedElement();
                        if (result instanceof Success<String> success) {
                            return new CallToolResult(List.of(new McpSchema.TextContent(success.value())), false);
                        }
                        return errorResult("Failed to get selected element");
                    });
                } catch (Exception e) {
                    return errorResult("Failed: " + e.getMessage());
                }
            }
        ));

        // select_element_at
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("select_element_at", "Select and highlight the UI element at given screen coordinates",
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

                    return withSidekick(pkg, device, client -> {
                        // First, find the element at coordinates
                        Result<String> hitResult = client.getElementAtCoordinates(x, y);
                        if (hitResult instanceof Success<String> hit) {
                            // Parse the element and set it as selected
                            JsonNode hitNode = mapper.readTree(hit.value());
                            if (hitNode.has("element") && !hitNode.get("element").isNull()) {
                                JsonNode element = hitNode.get("element");
                                // Set as selected
                                Result<String> selectResult = client.setSelectedElement(mapper.writeValueAsString(element));
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

        // clear_selection
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("clear_selection", "Clear the current element selection/highlight",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, args) -> {
                try {
                    String pkg = getString(args, "package");
                    String device = getString(args, "device");

                    return withSidekick(pkg, device, client -> {
                        Result<String> result = client.clearSelectedElement();
                        if (result instanceof Success<String> success) {
                            return jsonResult(Map.of("success", true, "message", "Selection cleared"));
                        }
                        return errorResult("Failed to clear selection");
                    });
                } catch (Exception e) {
                    return errorResult("Failed: " + e.getMessage());
                }
            }
        ));

        // get_selected_network_request
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("get_selected_network_request", "Get the currently selected network request",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, args) -> {
                try {
                    String pkg = getString(args, "package");
                    String device = getString(args, "device");

                    return withSidekick(pkg, device, client -> {
                        Result<String> result = client.getSelectedNetworkRequest();
                        if (result instanceof Success<String> success) {
                            return new CallToolResult(List.of(new McpSchema.TextContent(success.value())), false);
                        }
                        return errorResult("Failed to get selected network request");
                    });
                } catch (Exception e) {
                    return errorResult("Failed: " + e.getMessage());
                }
            }
        ));

        // select_network_request
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("select_network_request", "Select a network request by ID",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "request_id", prop("string", "Request ID to select", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, args) -> {
                try {
                    String pkg = getString(args, "package");
                    String requestId = getString(args, "request_id");
                    String device = getString(args, "device");

                    return withSidekick(pkg, device, client -> {
                        // Create selection JSON with the request ID
                        String selectionJson = mapper.writeValueAsString(Map.of("id", requestId));
                        Result<String> result = client.setSelectedNetworkRequest(selectionJson);
                        if (result instanceof Success<String> success) {
                            return new CallToolResult(List.of(new McpSchema.TextContent(success.value())), false);
                        }
                        return errorResult("Failed to select network request");
                    });
                } catch (Exception e) {
                    return errorResult("Failed: " + e.getMessage());
                }
            }
        ));

        // get_selected_websocket_message
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("get_selected_websocket_message", "Get the currently selected WebSocket message",
                schema(Map.of(
                    "package", prop("string", "App package name", true),
                    "device", prop("string", "Device serial", false)
                ))),
            (exchange, args) -> {
                try {
                    String pkg = getString(args, "package");
                    String device = getString(args, "device");

                    return withSidekick(pkg, device, client -> {
                        Result<String> result = client.getSelectedWebSocketMessage();
                        if (result instanceof Success<String> success) {
                            return new CallToolResult(List.of(new McpSchema.TextContent(success.value())), false);
                        }
                        return errorResult("Failed to get selected websocket message");
                    });
                } catch (Exception e) {
                    return errorResult("Failed: " + e.getMessage());
                }
            }
        ));

        // select_websocket_message
        tools.add(new McpServerFeatures.SyncToolSpecification(
            new Tool("select_websocket_message", "Select a WebSocket message by connection ID and message index",
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

                    return withSidekick(pkg, device, client -> {
                        // Create selection JSON
                        String selectionJson = mapper.writeValueAsString(Map.of(
                            "connectionId", connectionId,
                            "messageIndex", messageIndex
                        ));
                        Result<String> result = client.setSelectedWebSocketMessage(selectionJson);
                        if (result instanceof Success<String> success) {
                            return new CallToolResult(List.of(new McpSchema.TextContent(success.value())), false);
                        }
                        return errorResult("Failed to select websocket message");
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
        CallToolResult execute(SidekickClient client) throws Exception;
    }

    private static CallToolResult withSidekick(String packageName, String device, SidekickAction action) throws Exception {
        String key = (device != null ? device : "default") + ":" + packageName;

        ConnectionInfo conn = connections.computeIfAbsent(key, k -> {
            int port = nextPort++;
            String socketName = "dta_sidekick_" + packageName;
            try {
                AdbUtils.setupPortForward(device, port, socketName);
            } catch (Exception e) {
                throw new RuntimeException("Failed to setup port forward", e);
            }
            return new ConnectionInfo(packageName, device, port);
        });

        SidekickClient client = SidekickClient.builder()
            .packageName(packageName)
            .port(conn.port())
            .deviceSerial(device)
            .build();

        return action.execute(client);
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
