package io.yamsergey.dta.inspector.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.yamsergey.dta.inspector.web.SidekickConnectionManager.ConnectionInfo;
import io.yamsergey.dta.inspector.web.SidekickConnectionManager.Device;
import io.yamsergey.dta.inspector.web.SidekickConnectionManager.SidekickSocket;
import io.yamsergey.dta.tools.android.inspect.compose.ComposeNodeFilter;
import io.yamsergey.dta.tools.sugar.Result;
import io.yamsergey.dta.tools.sugar.Success;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API controller for the DTA Inspector.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class InspectorController {

    private static final String VERSION = loadVersion();

    private final SidekickConnectionManager connectionManager;
    private final ObjectMapper mapper = new ObjectMapper();

    public InspectorController(SidekickConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    private static String loadVersion() {
        try (var is = InspectorController.class.getResourceAsStream("/version.properties")) {
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

    /**
     * Returns the inspector-web tool version.
     */
    public static String getVersion() {
        return VERSION;
    }

    // ========================================================================
    // Version endpoint
    // ========================================================================

    @GetMapping("/version")
    public ResponseEntity<?> getToolVersion() {
        return ResponseEntity.ok(Map.of(
            "name", "dta-inspector-web",
            "version", VERSION
        ));
    }

    @GetMapping("/connection-status")
    public ResponseEntity<?> getConnectionStatus(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device) {
        try {
            ConnectionInfo conn = connectionManager.getConnection(packageName, device);
            var healthResult = conn.client().checkHealthTyped();

            ObjectNode result = mapper.createObjectNode();
            result.put("connected", true);
            result.put("toolVersion", VERSION);

            if (healthResult instanceof io.yamsergey.dta.tools.sugar.Success<io.yamsergey.dta.tools.android.inspect.compose.HealthResponse> success) {
                var health = success.value();
                result.put("sidekickVersion", health.version());
                result.put("sidekickName", health.name());

                // Check version compatibility
                if (!isVersionCompatible(VERSION, health.version())) {
                    result.put("warning", "Version mismatch: Inspector v" + VERSION + ", Sidekick v" + health.version());
                }
            }

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            ObjectNode result = mapper.createObjectNode();
            result.put("connected", false);
            result.put("toolVersion", VERSION);
            result.put("error", e.getMessage());
            return ResponseEntity.ok(result);
        }
    }

    /**
     * Checks version compatibility - major and minor must match.
     */
    private boolean isVersionCompatible(String toolVersion, String sidekickVersion) {
        if (toolVersion == null || sidekickVersion == null) return true;
        if ("unknown".equals(toolVersion) || "unknown".equals(sidekickVersion)) return true;

        String[] tool = toolVersion.split("\\.");
        String[] sidekick = sidekickVersion.split("\\.");

        if (tool.length < 2 || sidekick.length < 2) return true;

        return tool[0].equals(sidekick[0]) && tool[1].equals(sidekick[1]);
    }

    // ========================================================================
    // Device endpoints
    // ========================================================================

    @GetMapping("/devices")
    public ResponseEntity<?> listDevices() {
        try {
            List<Device> devices = connectionManager.listDevices();
            ObjectNode result = mapper.createObjectNode();
            ArrayNode devicesArray = result.putArray("devices");
            for (Device device : devices) {
                ObjectNode d = devicesArray.addObject();
                d.put("serial", device.serial());
                d.put("state", device.state());
                if (device.model() != null) d.put("model", device.model());
                if (device.product() != null) d.put("product", device.product());
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return error("Failed to list devices: " + e.getMessage());
        }
    }

    @GetMapping("/apps")
    public ResponseEntity<?> listApps(@RequestParam(required = false) String device) {
        try {
            List<SidekickSocket> sockets = connectionManager.discoverSockets(device);
            ObjectNode result = mapper.createObjectNode();
            ArrayNode appsArray = result.putArray("apps");
            for (SidekickSocket socket : sockets) {
                ObjectNode app = appsArray.addObject();
                app.put("package", socket.packageName());
                app.put("socket", socket.socketName());
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return error("Failed to list apps: " + e.getMessage());
        }
    }

    @GetMapping(value = "/screenshot", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> screenshot(@RequestParam(required = false) String device) {
        try {
            byte[] data = connectionManager.captureScreenshot(device);
            return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(data);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/tap")
    public ResponseEntity<?> tap(
            @RequestParam int x,
            @RequestParam int y,
            @RequestParam(required = false) String device) {
        try {
            boolean success = connectionManager.tap(device, x, y);
            return ResponseEntity.ok(Map.of("success", success, "x", x, "y", y));
        } catch (Exception e) {
            return error("Failed to tap: " + e.getMessage());
        }
    }

    // ========================================================================
    // Compose endpoints
    // ========================================================================

    @GetMapping("/compose/tree")
    public ResponseEntity<?> composeTree(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device,
            @RequestParam(required = false) String text,
            @RequestParam(required = false) String type) {
        try {
            ConnectionInfo conn = connectionManager.getConnection(packageName, device);
            Result<String> result = conn.client().getComposeTree();

            if (result instanceof Success<String> success) {
                String json = success.value();

                // Apply filters if specified
                if (text != null || type != null) {
                    ComposeNodeFilter filter = ComposeNodeFilter.builder()
                        .textPattern(text)
                        .composablePattern(type)
                        .build();
                    String filtered = filter.filter(json);
                    ObjectNode response = mapper.createObjectNode();
                    response.put("package", packageName);
                    ObjectNode filters = response.putObject("filters");
                    if (text != null) filters.put("text", text);
                    if (type != null) filters.put("type", type);
                    response.set("matches", mapper.readTree(filtered));
                    return ResponseEntity.ok(response);
                }

                return ResponseEntity.ok(mapper.readTree(json));
            }
            return error("Failed to get compose tree");
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    @GetMapping("/compose/select")
    public ResponseEntity<?> composeSelect(
            @RequestParam("package") String packageName,
            @RequestParam int x,
            @RequestParam int y,
            @RequestParam(required = false) String device) {
        try {
            ConnectionInfo conn = connectionManager.getConnection(packageName, device);
            // Call hit test endpoint - need to add this to SidekickClient
            // For now, return the tap coordinates
            return ResponseEntity.ok(Map.of("x", x, "y", y, "hint", "Hit test not yet implemented"));
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    // ========================================================================
    // Network endpoints
    // ========================================================================

    @GetMapping("/network/requests")
    public ResponseEntity<?> networkRequests(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device) {
        try {
            ConnectionInfo conn = connectionManager.getConnection(packageName, device);
            Result<String> result = conn.client().getNetworkRequests();

            if (result instanceof Success<String> success) {
                return ResponseEntity.ok(mapper.readTree(success.value()));
            }
            return error("Failed to get network requests");
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    @GetMapping("/network/requests/{requestId}")
    public ResponseEntity<?> networkRequest(
            @RequestParam("package") String packageName,
            @PathVariable String requestId,
            @RequestParam(required = false) String device) {
        try {
            ConnectionInfo conn = connectionManager.getConnection(packageName, device);
            Result<String> result = conn.client().getNetworkRequest(requestId);

            if (result instanceof Success<String> success) {
                return ResponseEntity.ok(mapper.readTree(success.value()));
            }
            return error("Failed to get network request");
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    @GetMapping("/network/requests/{requestId}/body")
    public ResponseEntity<?> networkRequestBody(
            @RequestParam("package") String packageName,
            @PathVariable String requestId,
            @RequestParam(required = false) String device) {
        try {
            ConnectionInfo conn = connectionManager.getConnection(packageName, device);
            Result<String> result = conn.client().getNetworkRequestBody(requestId);

            if (result instanceof Success<String> success) {
                return ResponseEntity.ok(mapper.readTree(success.value()));
            }
            return error("Failed to get network request body");
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    @GetMapping("/network/stats")
    public ResponseEntity<?> networkStats(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device) {
        try {
            ConnectionInfo conn = connectionManager.getConnection(packageName, device);
            Result<String> result = conn.client().getNetworkStats();

            if (result instanceof Success<String> success) {
                return ResponseEntity.ok(mapper.readTree(success.value()));
            }
            return error("Failed to get network stats");
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    @DeleteMapping("/network/clear")
    public ResponseEntity<?> clearNetworkRequests(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device) {
        try {
            ConnectionInfo conn = connectionManager.getConnection(packageName, device);
            Result<String> result = conn.client().clearNetworkRequests();

            if (result instanceof Success<String> success) {
                return ResponseEntity.ok(mapper.readTree(success.value()));
            }
            return error("Failed to clear network requests");
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    // ========================================================================
    // WebSocket endpoints
    // ========================================================================

    @GetMapping("/websocket/connections")
    public ResponseEntity<?> websocketConnections(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device) {
        try {
            ConnectionInfo conn = connectionManager.getConnection(packageName, device);
            Result<String> result = conn.client().getWebSocketConnections();

            if (result instanceof Success<String> success) {
                return ResponseEntity.ok(mapper.readTree(success.value()));
            }
            return error("Failed to get websocket connections");
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    @GetMapping("/websocket/connections/{connectionId}")
    public ResponseEntity<?> websocketConnection(
            @RequestParam("package") String packageName,
            @PathVariable String connectionId,
            @RequestParam(required = false) String device) {
        try {
            ConnectionInfo conn = connectionManager.getConnection(packageName, device);
            Result<String> result = conn.client().getWebSocketConnection(connectionId);

            if (result instanceof Success<String> success) {
                return ResponseEntity.ok(mapper.readTree(success.value()));
            }
            return error("Failed to get websocket connection");
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    @DeleteMapping("/websocket/clear")
    public ResponseEntity<?> clearWebSocketConnections(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device) {
        try {
            ConnectionInfo conn = connectionManager.getConnection(packageName, device);
            Result<String> result = conn.client().clearWebSocketConnections();

            if (result instanceof Success<String> success) {
                return ResponseEntity.ok(mapper.readTree(success.value()));
            }
            return error("Failed to clear websocket connections");
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    // ========================================================================
    // Selection endpoints (multi-selection support)
    // Query param action: add (default), remove, clear
    // ========================================================================

    @GetMapping("/selection/element")
    public ResponseEntity<?> getSelectedElements(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device) {
        try {
            ConnectionInfo conn = connectionManager.getConnection(packageName, device);
            Result<String> result = conn.client().getSelectedElements();

            if (result instanceof Success<String> success) {
                return ResponseEntity.ok(mapper.readTree(success.value()));
            }
            return error("Failed to get selected elements");
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    @PostMapping("/selection/element")
    public ResponseEntity<?> modifySelectedElements(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device,
            @RequestParam(defaultValue = "add") String action,
            @RequestBody(required = false) String elementJson) {
        try {
            ConnectionInfo conn = connectionManager.getConnection(packageName, device);
            Result<String> result = switch (action) {
                case "clear" -> conn.client().clearSelectedElements();
                case "remove" -> conn.client().removeSelectedElement(elementJson);
                default -> conn.client().addSelectedElement(elementJson);
            };

            if (result instanceof Success<String> success) {
                return ResponseEntity.ok(mapper.readTree(success.value()));
            }
            return error("Failed to modify element selection");
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    // ========================================================================
    // Network selection endpoints (multi-selection support)
    // ========================================================================

    @GetMapping("/selection/network")
    public ResponseEntity<?> getSelectedNetworkRequests(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device) {
        try {
            ConnectionInfo conn = connectionManager.getConnection(packageName, device);
            Result<String> result = conn.client().getSelectedNetworkRequests();

            if (result instanceof Success<String> success) {
                return ResponseEntity.ok(mapper.readTree(success.value()));
            }
            return error("Failed to get selected network requests");
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    @PostMapping("/selection/network")
    public ResponseEntity<?> modifySelectedNetworkRequests(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device,
            @RequestParam(defaultValue = "add") String action,
            @RequestBody(required = false) String requestJson) {
        try {
            ConnectionInfo conn = connectionManager.getConnection(packageName, device);
            Result<String> result = switch (action) {
                case "clear" -> conn.client().clearSelectedNetworkRequests();
                case "remove" -> conn.client().removeSelectedNetworkRequest(requestJson);
                default -> conn.client().addSelectedNetworkRequest(requestJson);
            };

            if (result instanceof Success<String> success) {
                return ResponseEntity.ok(mapper.readTree(success.value()));
            }
            return error("Failed to modify network request selection");
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    // ========================================================================
    // WebSocket message selection endpoints (multi-selection support)
    // ========================================================================

    @GetMapping("/selection/websocket-message")
    public ResponseEntity<?> getSelectedWebSocketMessages(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device) {
        try {
            ConnectionInfo conn = connectionManager.getConnection(packageName, device);
            Result<String> result = conn.client().getSelectedWebSocketMessages();

            if (result instanceof Success<String> success) {
                return ResponseEntity.ok(mapper.readTree(success.value()));
            }
            return error("Failed to get selected websocket messages");
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    @PostMapping("/selection/websocket-message")
    public ResponseEntity<?> modifySelectedWebSocketMessages(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device,
            @RequestParam(defaultValue = "add") String action,
            @RequestBody(required = false) String selectionJson) {
        try {
            ConnectionInfo conn = connectionManager.getConnection(packageName, device);
            Result<String> result = switch (action) {
                case "clear" -> conn.client().clearSelectedWebSocketMessages();
                case "remove" -> conn.client().removeSelectedWebSocketMessage(selectionJson);
                default -> conn.client().addSelectedWebSocketMessage(selectionJson);
            };

            if (result instanceof Success<String> success) {
                return ResponseEntity.ok(mapper.readTree(success.value()));
            }
            return error("Failed to modify websocket message selection");
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    // ========================================================================
    // Mock endpoints
    // ========================================================================

    @GetMapping("/mock/rules")
    public ResponseEntity<?> getMockRules(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device) {
        try {
            ConnectionInfo conn = connectionManager.getConnection(packageName, device);
            Result<String> result = conn.client().getMockRules();

            if (result instanceof Success<String> success) {
                return ResponseEntity.ok(mapper.readTree(success.value()));
            }
            return error("Failed to get mock rules");
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    @GetMapping("/mock/rules/{ruleId}")
    public ResponseEntity<?> getMockRule(
            @RequestParam("package") String packageName,
            @PathVariable String ruleId,
            @RequestParam(required = false) String device) {
        try {
            ConnectionInfo conn = connectionManager.getConnection(packageName, device);
            Result<String> result = conn.client().getMockRule(ruleId);

            if (result instanceof Success<String> success) {
                return ResponseEntity.ok(mapper.readTree(success.value()));
            }
            return error("Failed to get mock rule");
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    @PostMapping("/mock/rules")
    public ResponseEntity<?> createMockRule(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device,
            @RequestBody String ruleJson) {
        try {
            ConnectionInfo conn = connectionManager.getConnection(packageName, device);
            Result<String> result = conn.client().createMockRule(ruleJson);

            if (result instanceof Success<String> success) {
                return ResponseEntity.ok(mapper.readTree(success.value()));
            }
            return error("Failed to create mock rule");
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    @PutMapping("/mock/rules/{ruleId}")
    public ResponseEntity<?> updateMockRule(
            @RequestParam("package") String packageName,
            @PathVariable String ruleId,
            @RequestParam(required = false) String device,
            @RequestBody String updateJson) {
        try {
            ConnectionInfo conn = connectionManager.getConnection(packageName, device);
            Result<String> result = conn.client().updateMockRule(ruleId, updateJson);

            if (result instanceof Success<String> success) {
                return ResponseEntity.ok(mapper.readTree(success.value()));
            }
            return error("Failed to update mock rule");
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    @DeleteMapping("/mock/rules/{ruleId}")
    public ResponseEntity<?> deleteMockRule(
            @RequestParam("package") String packageName,
            @PathVariable String ruleId,
            @RequestParam(required = false) String device) {
        try {
            ConnectionInfo conn = connectionManager.getConnection(packageName, device);
            Result<String> result = conn.client().deleteMockRule(ruleId);

            if (result instanceof Success<String> success) {
                return ResponseEntity.ok(mapper.readTree(success.value()));
            }
            return error("Failed to delete mock rule");
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    @GetMapping("/mock/config")
    public ResponseEntity<?> getMockConfig(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device) {
        try {
            ConnectionInfo conn = connectionManager.getConnection(packageName, device);
            Result<String> result = conn.client().getMockConfig();

            if (result instanceof Success<String> success) {
                return ResponseEntity.ok(mapper.readTree(success.value()));
            }
            return error("Failed to get mock config");
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    @PutMapping("/mock/config")
    public ResponseEntity<?> updateMockConfig(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device,
            @RequestBody String configJson) {
        try {
            ConnectionInfo conn = connectionManager.getConnection(packageName, device);
            Result<String> result = conn.client().updateMockConfig(configJson);

            if (result instanceof Success<String> success) {
                return ResponseEntity.ok(mapper.readTree(success.value()));
            }
            return error("Failed to update mock config");
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    @PostMapping("/mock/from-request/{requestId}")
    public ResponseEntity<?> createMockFromRequest(
            @RequestParam("package") String packageName,
            @PathVariable String requestId,
            @RequestParam(required = false) String device) {
        try {
            ConnectionInfo conn = connectionManager.getConnection(packageName, device);
            Result<String> result = conn.client().createMockFromRequest(requestId);

            if (result instanceof Success<String> success) {
                return ResponseEntity.ok(mapper.readTree(success.value()));
            }
            return error("Failed to create mock from request");
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    @PostMapping("/mock/from-message/{messageId}")
    public ResponseEntity<?> createMockFromMessage(
            @RequestParam("package") String packageName,
            @PathVariable String messageId,
            @RequestParam(required = false) String device) {
        try {
            ConnectionInfo conn = connectionManager.getConnection(packageName, device);
            Result<String> result = conn.client().createMockFromMessage(messageId);

            if (result instanceof Success<String> success) {
                return ResponseEntity.ok(mapper.readTree(success.value()));
            }
            return error("Failed to create mock from message");
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    // ========================================================================
    // CDP Watcher endpoints
    // ========================================================================

    @PostMapping("/cdp/watch/start")
    public ResponseEntity<?> startCdpWatcher(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device) {
        try {
            boolean started = connectionManager.startCdpWatcher(packageName, device);
            if (started) {
                return ResponseEntity.ok(Map.of(
                    "status", "started",
                    "message", "CDP watcher started for Custom Tabs"
                ));
            } else {
                return ResponseEntity.ok(Map.of(
                    "status", "already_running",
                    "message", "CDP watcher already active"
                ));
            }
        } catch (Exception e) {
            return error("Failed to start CDP watcher: " + e.getMessage());
        }
    }

    @PostMapping("/cdp/watch/stop")
    public ResponseEntity<?> stopCdpWatcher(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device) {
        try {
            boolean stopped = connectionManager.stopCdpWatcher(packageName, device);
            if (stopped) {
                return ResponseEntity.ok(Map.of(
                    "status", "stopped",
                    "message", "CDP watcher stopped"
                ));
            } else {
                return ResponseEntity.ok(Map.of(
                    "status", "not_running",
                    "message", "No CDP watcher was running"
                ));
            }
        } catch (Exception e) {
            return error("Failed to stop CDP watcher: " + e.getMessage());
        }
    }

    @GetMapping("/cdp/watch/status")
    public ResponseEntity<?> getCdpWatcherStatus(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device) {
        try {
            var info = connectionManager.getCdpWatcherInfo(packageName, device);
            if (info != null) {
                return ResponseEntity.ok(Map.of(
                    "watching", true,
                    "currentTabUrl", info.currentTabUrl() != null ? info.currentTabUrl() : "",
                    "connected", info.isConnected(),
                    "startTime", info.startTime()
                ));
            } else {
                return ResponseEntity.ok(Map.of(
                    "watching", false
                ));
            }
        } catch (Exception e) {
            return error("Failed to get CDP watcher status: " + e.getMessage());
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private ResponseEntity<?> error(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
