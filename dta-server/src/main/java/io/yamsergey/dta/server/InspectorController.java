package io.yamsergey.dta.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.yamsergey.dta.tools.android.cdp.CdpWatcherManager;
import io.yamsergey.dta.tools.android.inspect.compose.ComposeNodeFilter;
import io.yamsergey.dta.tools.android.inspect.compose.SidekickConnectionManager;
import io.yamsergey.dta.tools.android.inspect.compose.SidekickConnectionManager.ConnectionInfo;
import io.yamsergey.dta.tools.android.inspect.compose.SidekickConnectionManager.Device;
import io.yamsergey.dta.tools.android.inspect.compose.SidekickConnectionManager.SidekickSocket;
import io.yamsergey.dta.tools.android.inspect.scroll.ScrollScreenshot;
import io.yamsergey.dta.tools.android.inspect.scroll.ScrollScreenshotCapture;
import io.yamsergey.dta.tools.android.sidekick.SidekickSseListener;
import io.yamsergey.dta.tools.sugar.Failure;
import io.yamsergey.dta.tools.sugar.Result;
import io.yamsergey.dta.tools.sugar.Success;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST API controller for the DTA Inspector.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class InspectorController {

    private static final Logger log = LoggerFactory.getLogger(InspectorController.class);

    private static final String VERSION = loadVersion();
    private static final int DEFAULT_CDP_PORT = 9222;

    private final SidekickConnectionManager connectionManager = SidekickConnectionManager.getInstance();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, SidekickSseListener> sseListeners = new ConcurrentHashMap<>();

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
        var info = new java.util.HashMap<String, Object>();
        info.put("name", "dta-server");
        info.put("version", VERSION);
        info.put("daemon", true);
        info.put("pid", ProcessHandle.current().pid());
        return ResponseEntity.ok(info);
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
                if (!SidekickConnectionManager.isVersionCompatible(VERSION, health.version())) {
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
                d.put("name", device.serial());
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
            List<SidekickSocket> sockets = connectionManager.findSidekickSockets(device);
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
    public ResponseEntity<byte[]> screenshot(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device) {
        try {
            ConnectionInfo conn = getConnectionWithCdp(packageName, device);
            Result<byte[]> result = conn.client().getScreenshot();
            if (result instanceof Success<byte[]> success) {
                return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .cacheControl(org.springframework.http.CacheControl.noStore())
                    .body(success.value());
            }
            String error = result instanceof Failure<byte[]> f ? f.description() : "Unknown error";
            log.error("Screenshot failed for package={}, device={}: {}", packageName, device, error);
            return ResponseEntity.internalServerError().body(error.getBytes());
        } catch (Exception e) {
            log.error("Screenshot failed for package={}, device={}", packageName, device, e);
            return ResponseEntity.internalServerError().body(e.getMessage().getBytes());
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

    @PostMapping("/swipe")
    public ResponseEntity<?> swipe(
            @RequestParam int x1,
            @RequestParam int y1,
            @RequestParam int x2,
            @RequestParam int y2,
            @RequestParam(required = false, defaultValue = "300") int duration,
            @RequestParam(required = false) String device) {
        try {
            boolean success = connectionManager.swipe(device, x1, y1, x2, y2, duration);
            return ResponseEntity.ok(Map.of("success", success));
        } catch (Exception e) {
            return error("Failed to swipe: " + e.getMessage());
        }
    }

    @PostMapping("/input-text")
    public ResponseEntity<?> inputText(
            @RequestParam String text,
            @RequestParam(required = false) String device) {
        try {
            boolean success = connectionManager.inputText(device, text);
            return ResponseEntity.ok(Map.of("success", success));
        } catch (Exception e) {
            return error("Failed to input text: " + e.getMessage());
        }
    }

    @PostMapping("/press-key")
    public ResponseEntity<?> pressKey(
            @RequestParam String key,
            @RequestParam(required = false) String device) {
        try {
            boolean success = connectionManager.pressKey(device, key);
            return ResponseEntity.ok(Map.of("success", success, "key", key));
        } catch (Exception e) {
            return error("Failed to press key: " + e.getMessage());
        }
    }

    @GetMapping(value = "/screenshot/device", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> deviceScreenshot(
            @RequestParam(required = false) String device) {
        try {
            byte[] data = connectionManager.captureScreenshot(device);
            return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(data);
        } catch (Exception e) {
            log.error("Device screenshot failed for device={}", device, e);
            return ResponseEntity.internalServerError().body(e.getMessage().getBytes());
        }
    }

    @PostMapping("/scroll-screenshot")
    public ResponseEntity<?> scrollScreenshot(
            @RequestParam(required = false) String device,
            @RequestParam(required = false) String viewId,
            @RequestParam(required = false, defaultValue = "false") boolean scrollToTop,
            @RequestParam(required = false, defaultValue = "30") int maxCaptures) {
        try {
            File tempFile = Files.createTempFile("scroll_screenshot_", ".png").toFile();
            tempFile.deleteOnExit();

            ScrollScreenshotCapture.ScrollScreenshotCaptureBuilder builder =
                ScrollScreenshotCapture.builder()
                    .outputFile(tempFile)
                    .scrollToTop(scrollToTop)
                    .maxCaptures(maxCaptures);

            if (device != null && !device.isEmpty()) {
                builder.deviceSerial(device);
            }
            if (viewId != null && !viewId.isEmpty()) {
                builder.targetViewId(viewId);
            }

            Result<ScrollScreenshot> result = builder.build().capture();

            if (result instanceof Success<ScrollScreenshot> success) {
                ScrollScreenshot screenshot = success.value();
                byte[] imageBytes = Files.readAllBytes(tempFile.toPath());
                String base64 = Base64.getEncoder().encodeToString(imageBytes);
                tempFile.delete();

                return ResponseEntity.ok(Map.of(
                    "imageBase64", base64,
                    "width", screenshot.getWidth(),
                    "height", screenshot.getHeight(),
                    "captures", screenshot.getCaptureCount(),
                    "reachedEnd", screenshot.isReachedScrollEnd(),
                    "scrollableView", screenshot.getScrollableViewId() != null ? screenshot.getScrollableViewId() : ""
                ));
            } else if (result instanceof Failure<ScrollScreenshot> failure) {
                tempFile.delete();
                return error("Scroll screenshot failed: " + failure.description());
            }
            tempFile.delete();
            return error("Scroll screenshot failed: unknown error");
        } catch (Exception e) {
            return error("Failed to capture scroll screenshot: " + e.getMessage());
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
            ConnectionInfo conn = getConnectionWithCdp(packageName, device);
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
            ConnectionInfo conn = getConnectionWithCdp(packageName, device);
            // Call hit test endpoint - need to add this to SidekickClient
            // For now, return the tap coordinates
            return ResponseEntity.ok(Map.of("x", x, "y", y, "hint", "Hit test not yet implemented"));
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    // ========================================================================
    // Layout endpoints (unified View + Compose tree)
    // ========================================================================

    @GetMapping("/layout/tree")
    public ResponseEntity<?> layoutTree(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device,
            @RequestParam(required = false) String text,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String resource_id,
            @RequestParam(required = false) String view_id) {
        try {
            ConnectionInfo conn = getConnectionWithCdp(packageName, device);
            Result<String> result = conn.client().getLayoutTree(text, type, resource_id, view_id);

            if (result instanceof Success<String> success) {
                JsonNode tree = mapper.readTree(success.value());

                // Wrap filtered results in a consistent shape matching compose_tree
                boolean hasFilters = text != null || type != null || resource_id != null || view_id != null;
                if (hasFilters) {
                    ObjectNode response = mapper.createObjectNode();
                    response.put("package", packageName);
                    ObjectNode filters = response.putObject("filters");
                    if (text != null) filters.put("text", text);
                    if (type != null) filters.put("type", type);
                    if (resource_id != null) filters.put("resourceId", resource_id);
                    if (view_id != null) filters.put("viewId", view_id);
                    response.set("result", tree);
                    return ResponseEntity.ok(response);
                }

                return ResponseEntity.ok(tree);
            }
            String desc = result instanceof Failure<?> f ? f.description() : "Unknown error";
            return error("Failed to get layout tree: " + desc);
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    @GetMapping("/layout/properties/{viewId}")
    public ResponseEntity<?> layoutProperties(
            @RequestParam("package") String packageName,
            @PathVariable String viewId,
            @RequestParam(required = false) String device) {
        try {
            ConnectionInfo conn = getConnectionWithCdp(packageName, device);
            Result<String> result = conn.client().getLayoutProperties(viewId);

            if (result instanceof Success<String> success) {
                return ResponseEntity.ok(mapper.readTree(success.value()));
            }
            String desc = result instanceof Failure<?> f ? f.description() : "Unknown error";
            return error("Failed to get layout properties: " + desc);
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
            ConnectionInfo conn = getConnectionWithCdp(packageName, device);
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
            ConnectionInfo conn = getConnectionWithCdp(packageName, device);
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
            ConnectionInfo conn = getConnectionWithCdp(packageName, device);
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
            ConnectionInfo conn = getConnectionWithCdp(packageName, device);
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
            ConnectionInfo conn = getConnectionWithCdp(packageName, device);
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
            ConnectionInfo conn = getConnectionWithCdp(packageName, device);
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
            ConnectionInfo conn = getConnectionWithCdp(packageName, device);
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
            ConnectionInfo conn = getConnectionWithCdp(packageName, device);
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
            ConnectionInfo conn = getConnectionWithCdp(packageName, device);
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
            ConnectionInfo conn = getConnectionWithCdp(packageName, device);
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
            ConnectionInfo conn = getConnectionWithCdp(packageName, device);
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
            ConnectionInfo conn = getConnectionWithCdp(packageName, device);
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
            ConnectionInfo conn = getConnectionWithCdp(packageName, device);
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
            ConnectionInfo conn = getConnectionWithCdp(packageName, device);
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
            ConnectionInfo conn = getConnectionWithCdp(packageName, device);
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
            ConnectionInfo conn = getConnectionWithCdp(packageName, device);
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
            ConnectionInfo conn = getConnectionWithCdp(packageName, device);
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
            ConnectionInfo conn = getConnectionWithCdp(packageName, device);
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
            ConnectionInfo conn = getConnectionWithCdp(packageName, device);
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
            ConnectionInfo conn = getConnectionWithCdp(packageName, device);
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
            ConnectionInfo conn = getConnectionWithCdp(packageName, device);
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
            ConnectionInfo conn = getConnectionWithCdp(packageName, device);
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
            ConnectionInfo conn = getConnectionWithCdp(packageName, device);
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
    // Auto CDP helpers
    // ========================================================================

    /**
     * Returns a sidekick connection and auto-enables CDP watcher (best-effort).
     * Use this instead of {@code connectionManager.getConnection()} in endpoint methods.
     */
    private ConnectionInfo getConnectionWithCdp(String packageName, String device) throws Exception {
        ConnectionInfo conn = connectionManager.getConnection(packageName, device);
        ensureCdpWatcher(packageName, device, conn);
        return conn;
    }

    /**
     * Idempotent, best-effort CDP watcher setup. If already running for this key — returns immediately.
     * On failure, logs a warning and continues — never breaks the calling API request.
     */
    private void ensureCdpWatcher(String packageName, String device, ConnectionInfo conn) {
        String listenerKey = makeListenerKey(packageName, device);

        // Already running — nothing to do
        if (sseListeners.containsKey(listenerKey)) {
            return;
        }

        try {
            // Set up Chrome DevTools port forwarding
            connectionManager.setupCdpPortForward(device, DEFAULT_CDP_PORT);

            // Arm CDP capture on sidekick
            conn.client().requestCdpCapture();

            // Start CdpWatcherManager (no polling, just registers context)
            CdpWatcherManager.getInstance().startWatcher(
                packageName, device, DEFAULT_CDP_PORT, conn.port(), conn.client(), null);

            // Start SSE listener for push events
            SidekickSseListener sseListener = new SidekickSseListener(conn.port(),
                new SidekickSseListener.EventListener() {
                    @Override
                    public void onCustomTabWillLaunch(String eventId, String url, long timestamp) {
                        log.info("SSE: Custom Tab will launch: {} (event={})", url, eventId);
                        CdpWatcherManager.getInstance().onCustomTabWillLaunch(packageName, device, eventId, url);
                    }

                    @Override
                    public void onConnected() {
                        log.info("SSE: Connected to sidekick for {}", packageName);
                    }

                    @Override
                    public void onDisconnected() {
                        log.info("SSE: Disconnected from sidekick for {}", packageName);
                    }
                });
            sseListener.start();

            // Atomically register — if another thread raced us, stop ours
            SidekickSseListener raced = sseListeners.putIfAbsent(listenerKey, sseListener);
            if (raced != null) {
                sseListener.stop();
                CdpWatcherManager.getInstance().stopWatcher(packageName, device);
            } else {
                log.info("Auto-enabled CDP capture for package={}, device={}", packageName, device);
            }
        } catch (Exception e) {
            log.warn("Auto-enable CDP capture failed for package={}, device={}: {} (non-fatal)",
                packageName, device, e.getMessage());
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
            ConnectionInfo conn = connectionManager.getConnection(packageName, device);
            ensureCdpWatcher(packageName, device, conn);

            String listenerKey = makeListenerKey(packageName, device);
            boolean running = sseListeners.containsKey(listenerKey);

            return ResponseEntity.ok(Map.of(
                "status", running ? "started" : "failed",
                "message", running
                    ? "CDP capture armed — will attach on Custom Tab launch"
                    : "CDP capture could not be started (check server logs)"
            ));
        } catch (Exception e) {
            log.error("Failed to start CDP capture for package={}: {}", packageName, e.getMessage(), e);
            return error("Failed to start CDP capture: " + e.getMessage());
        }
    }

    @PostMapping("/cdp/watch/stop")
    public ResponseEntity<?> stopCdpWatcher(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device) {
        try {
            String listenerKey = makeListenerKey(packageName, device);

            // Stop SSE listener
            SidekickSseListener sseListener = sseListeners.remove(listenerKey);
            if (sseListener != null) {
                sseListener.stop();
            }

            // Disarm CDP capture on sidekick
            try {
                ConnectionInfo conn = connectionManager.getConnection(packageName, device);
                conn.client().releaseCdpCapture();
            } catch (Exception e) {
                log.debug("Could not release CDP capture on sidekick: {}", e.getMessage());
            }

            // Stop watcher
            boolean stopped = CdpWatcherManager.getInstance().stopWatcher(packageName, device);

            // Remove ADB port forward for CDP
            connectionManager.removeCdpPortForward(device, DEFAULT_CDP_PORT);

            if (stopped || sseListener != null) {
                log.info("CDP capture disarmed for package={}, device={}", packageName, device);
                return ResponseEntity.ok(Map.of(
                    "status", "stopped",
                    "message", "CDP capture disarmed"
                ));
            } else {
                return ResponseEntity.ok(Map.of(
                    "status", "not_running",
                    "message", "No CDP capture was active"
                ));
            }
        } catch (Exception e) {
            log.error("Failed to stop CDP capture for package={}: {}", packageName, e.getMessage(), e);
            return error("Failed to stop CDP capture: " + e.getMessage());
        }
    }

    @GetMapping("/cdp/watch/status")
    public ResponseEntity<?> getCdpWatcherStatus(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device) {
        try {
            String listenerKey = makeListenerKey(packageName, device);
            SidekickSseListener sseListener = sseListeners.get(listenerKey);
            var info = CdpWatcherManager.getInstance().getWatcherInfo(packageName, device);

            Map<String, Object> status = new java.util.HashMap<>();
            status.put("armed", CdpWatcherManager.getInstance().isWatching(packageName, device));
            status.put("sseConnected", sseListener != null && sseListener.isRunning());

            if (info != null) {
                status.put("watching", true);
                status.put("currentTabUrl", info.currentTabUrl() != null ? info.currentTabUrl() : "");
                status.put("connected", info.isConnected());
                status.put("startTime", info.startTime());
            } else {
                status.put("watching", false);
            }

            return ResponseEntity.ok(status);
        } catch (Exception e) {
            return error("Failed to get CDP capture status: " + e.getMessage());
        }
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    @PreDestroy
    void shutdown() {
        log.info("Shutting down InspectorController — cleaning up CDP watchers");
        sseListeners.forEach((key, listener) -> {
            try {
                listener.stop();
            } catch (Exception e) {
                log.debug("Error stopping SSE listener {}: {}", key, e.getMessage());
            }
            // Release CDP capture on the sidekick for this key
            try {
                String[] parts = key.split(":", 2);
                String device = "default".equals(parts[0]) ? null : parts[0];
                String packageName = parts[1];
                ConnectionInfo conn = connectionManager.getConnection(packageName, device);
                conn.client().releaseCdpCapture();
            } catch (Exception e) {
                log.debug("Error releasing CDP capture for {}: {}", key, e.getMessage());
            }
        });
        sseListeners.clear();
        CdpWatcherManager.getInstance().stopAll();
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private String makeListenerKey(String packageName, String device) {
        return (device != null ? device : "default") + ":" + packageName;
    }

    private ResponseEntity<?> error(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
