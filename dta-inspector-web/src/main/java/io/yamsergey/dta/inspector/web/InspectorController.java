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

    private final SidekickConnectionManager connectionManager;
    private final ObjectMapper mapper = new ObjectMapper();

    public InspectorController(SidekickConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
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
    // Selection endpoints
    // ========================================================================

    @GetMapping("/selection/element")
    public ResponseEntity<?> getSelectedElement(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device) {
        try {
            ConnectionInfo conn = connectionManager.getConnection(packageName, device);
            Result<String> result = conn.client().getSelectedElement();

            if (result instanceof Success<String> success) {
                return ResponseEntity.ok(mapper.readTree(success.value()));
            }
            return error("Failed to get selected element");
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    @PostMapping("/selection/element")
    public ResponseEntity<?> setSelectedElement(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device,
            @RequestBody(required = false) String elementJson) {
        try {
            ConnectionInfo conn = connectionManager.getConnection(packageName, device);
            Result<String> result;

            if (elementJson == null || elementJson.isBlank() || "null".equals(elementJson)) {
                result = conn.client().clearSelectedElement();
            } else {
                result = conn.client().setSelectedElement(elementJson);
            }

            if (result instanceof Success<String> success) {
                return ResponseEntity.ok(mapper.readTree(success.value()));
            }
            return error("Failed to set selected element");
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    // ========================================================================
    // Network selection endpoints
    // ========================================================================

    @GetMapping("/selection/network")
    public ResponseEntity<?> getSelectedNetworkRequest(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device) {
        try {
            ConnectionInfo conn = connectionManager.getConnection(packageName, device);
            Result<String> result = conn.client().getSelectedNetworkRequest();

            if (result instanceof Success<String> success) {
                return ResponseEntity.ok(mapper.readTree(success.value()));
            }
            return error("Failed to get selected network request");
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    @PostMapping("/selection/network")
    public ResponseEntity<?> setSelectedNetworkRequest(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device,
            @RequestBody(required = false) String requestJson) {
        try {
            ConnectionInfo conn = connectionManager.getConnection(packageName, device);
            Result<String> result;

            if (requestJson == null || requestJson.isBlank() || "null".equals(requestJson)) {
                result = conn.client().clearSelectedNetworkRequest();
            } else {
                result = conn.client().setSelectedNetworkRequest(requestJson);
            }

            if (result instanceof Success<String> success) {
                return ResponseEntity.ok(mapper.readTree(success.value()));
            }
            return error("Failed to set selected network request");
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    // ========================================================================
    // WebSocket message selection endpoints
    // ========================================================================

    @GetMapping("/selection/websocket-message")
    public ResponseEntity<?> getSelectedWebSocketMessage(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device) {
        try {
            ConnectionInfo conn = connectionManager.getConnection(packageName, device);
            Result<String> result = conn.client().getSelectedWebSocketMessage();

            if (result instanceof Success<String> success) {
                return ResponseEntity.ok(mapper.readTree(success.value()));
            }
            return error("Failed to get selected websocket message");
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    @PostMapping("/selection/websocket-message")
    public ResponseEntity<?> setSelectedWebSocketMessage(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device,
            @RequestBody(required = false) String selectionJson) {
        try {
            ConnectionInfo conn = connectionManager.getConnection(packageName, device);
            Result<String> result;

            if (selectionJson == null || selectionJson.isBlank() || "null".equals(selectionJson)) {
                result = conn.client().clearSelectedWebSocketMessage();
            } else {
                result = conn.client().setSelectedWebSocketMessage(selectionJson);
            }

            if (result instanceof Success<String> success) {
                return ResponseEntity.ok(mapper.readTree(success.value()));
            }
            return error("Failed to set selected websocket message");
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
    // Helpers
    // ========================================================================

    private ResponseEntity<?> error(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
