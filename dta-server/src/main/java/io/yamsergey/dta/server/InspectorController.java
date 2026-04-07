package io.yamsergey.dta.server;

import tools.jackson.databind.ObjectMapper;
import io.yamsergey.dta.daemon.DtaOrchestrator;
import io.yamsergey.dta.daemon.sidekick.SidekickConnectionManager;
import io.yamsergey.dta.daemon.sidekick.SidekickConnectionManager.ConnectionInfo;
import io.yamsergey.dta.daemon.sidekick.SidekickConnectionManager.Device;
import io.yamsergey.dta.daemon.sidekick.SidekickConnectionManager.SidekickSocket;
import io.yamsergey.dta.daemon.scroll.ScrollScreenshot;
import io.yamsergey.dta.daemon.scroll.ScrollScreenshotCapture;
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

/**
 * REST API controller for the DTA Inspector.
 * Delegates all orchestration logic to {@link DtaOrchestrator}.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class InspectorController {

    private static final Logger log = LoggerFactory.getLogger(InspectorController.class);

    private static final String VERSION = loadVersion();

    private final DtaOrchestrator orchestrator = DtaOrchestrator.getInstance();
    private final SidekickConnectionManager connectionManager = SidekickConnectionManager.getInstance();
    private final ObjectMapper mapper = new ObjectMapper();

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
        return ResponseEntity.ok(orchestrator.getConnectionStatus(packageName, device, VERSION));
    }

    // ========================================================================
    // Device endpoints
    // ========================================================================

    @GetMapping("/devices")
    public ResponseEntity<?> listDevices() {
        try {
            List<Device> devices = orchestrator.listDevices();
            var result = mapper.createObjectNode();
            var devicesArray = result.putArray("devices");
            for (Device device : devices) {
                var d = devicesArray.addObject();
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
            List<SidekickSocket> sockets = orchestrator.listApps(device);
            var result = mapper.createObjectNode();
            var appsArray = result.putArray("apps");
            for (SidekickSocket socket : sockets) {
                var app = appsArray.addObject();
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
            Result<byte[]> result = orchestrator.getScreenshot(packageName, device);
            if (result instanceof Success<byte[]> success) {
                return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .cacheControl(org.springframework.http.CacheControl.noStore())
                    .body(success.value());
            }
            String err = result instanceof Failure<byte[]> f ? f.description() : "Unknown error";
            log.error("Screenshot failed for package={}, device={}: {}", packageName, device, err);
            return ResponseEntity.internalServerError().body(err.getBytes());
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
            return ResponseEntity.ok(orchestrator.getLayoutTree(packageName, device, text, type, resource_id, view_id));
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
            return ResponseEntity.ok(mapper.readTree(orchestrator.getLayoutProperties(packageName, device, viewId)));
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
            return ResponseEntity.ok(mapper.readTree(orchestrator.getNetworkRequests(packageName, device)));
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
            return ResponseEntity.ok(mapper.readTree(orchestrator.getNetworkRequest(packageName, device, requestId)));
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
            return ResponseEntity.ok(mapper.readTree(orchestrator.getNetworkRequestBody(packageName, device, requestId)));
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    @GetMapping("/network/stats")
    public ResponseEntity<?> networkStats(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device) {
        try {
            return ResponseEntity.ok(mapper.readTree(orchestrator.getNetworkStats(packageName, device)));
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    @DeleteMapping("/network/clear")
    public ResponseEntity<?> clearNetworkRequests(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device) {
        try {
            return ResponseEntity.ok(mapper.readTree(orchestrator.clearNetworkRequests(packageName, device)));
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
            return ResponseEntity.ok(mapper.readTree(orchestrator.getWebSocketConnections(packageName, device)));
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
            return ResponseEntity.ok(mapper.readTree(orchestrator.getWebSocketConnection(packageName, device, connectionId)));
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    @DeleteMapping("/websocket/clear")
    public ResponseEntity<?> clearWebSocketConnections(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device) {
        try {
            return ResponseEntity.ok(mapper.readTree(orchestrator.clearWebSocketConnections(packageName, device)));
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    // ========================================================================
    // Selection endpoints (multi-selection support)
    // ========================================================================

    @GetMapping("/selection/element")
    public ResponseEntity<?> getSelectedElements(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device) {
        try {
            return ResponseEntity.ok(mapper.readTree(orchestrator.getSelectedElements(packageName, device)));
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
            String result = switch (action) {
                case "clear" -> orchestrator.clearSelectedElements(packageName, device);
                case "remove" -> orchestrator.removeSelectedElement(packageName, device, elementJson);
                default -> orchestrator.addSelectedElement(packageName, device, elementJson);
            };
            return ResponseEntity.ok(mapper.readTree(result));
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    @PostMapping("/selection/element-at")
    public ResponseEntity<?> selectElementAt(
            @RequestParam("package") String packageName,
            @RequestParam int x, @RequestParam int y,
            @RequestParam(required = false) String device) {
        try {
            return ResponseEntity.ok(mapper.readTree(orchestrator.selectElementAt(packageName, device, x, y)));
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    // ========================================================================
    // Network selection endpoints
    // ========================================================================

    @GetMapping("/selection/network")
    public ResponseEntity<?> getSelectedNetworkRequests(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device) {
        try {
            return ResponseEntity.ok(mapper.readTree(orchestrator.getSelectedNetworkRequests(packageName, device)));
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
            String result = switch (action) {
                case "clear" -> orchestrator.clearSelectedNetworkRequests(packageName, device);
                case "remove" -> orchestrator.removeSelectedNetworkRequest(packageName, device, requestJson);
                default -> orchestrator.addSelectedNetworkRequest(packageName, device, requestJson);
            };
            return ResponseEntity.ok(mapper.readTree(result));
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    // ========================================================================
    // WebSocket message selection endpoints
    // ========================================================================

    @GetMapping("/selection/websocket-message")
    public ResponseEntity<?> getSelectedWebSocketMessages(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device) {
        try {
            return ResponseEntity.ok(mapper.readTree(orchestrator.getSelectedWebSocketMessages(packageName, device)));
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
            String result = switch (action) {
                case "clear" -> orchestrator.clearSelectedWebSocketMessages(packageName, device);
                case "remove" -> orchestrator.removeSelectedWebSocketMessage(packageName, device, selectionJson);
                default -> orchestrator.addSelectedWebSocketMessage(packageName, device, selectionJson);
            };
            return ResponseEntity.ok(mapper.readTree(result));
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
            return ResponseEntity.ok(mapper.readTree(orchestrator.getMockRules(packageName, device)));
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
            return ResponseEntity.ok(mapper.readTree(orchestrator.getMockRule(packageName, device, ruleId)));
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
            return ResponseEntity.ok(mapper.readTree(orchestrator.createMockRule(packageName, device, ruleJson)));
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
            return ResponseEntity.ok(mapper.readTree(orchestrator.updateMockRule(packageName, device, ruleId, updateJson)));
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
            return ResponseEntity.ok(mapper.readTree(orchestrator.deleteMockRule(packageName, device, ruleId)));
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    @GetMapping("/mock/config")
    public ResponseEntity<?> getMockConfig(
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device) {
        try {
            return ResponseEntity.ok(mapper.readTree(orchestrator.getMockConfig(packageName, device)));
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
            return ResponseEntity.ok(mapper.readTree(orchestrator.updateMockConfig(packageName, device, configJson)));
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
            return ResponseEntity.ok(mapper.readTree(orchestrator.createMockFromRequest(packageName, device, requestId)));
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
            return ResponseEntity.ok(mapper.readTree(orchestrator.createMockFromMessage(packageName, device, messageId)));
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
            boolean running = orchestrator.startCdpWatcher(packageName, device);
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
            Map<String, Object> result = orchestrator.stopCdpWatcher(packageName, device);
            return ResponseEntity.ok(result);
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
            return ResponseEntity.ok(orchestrator.getCdpWatcherStatus(packageName, device));
        } catch (Exception e) {
            return error("Failed to get CDP capture status: " + e.getMessage());
        }
    }

    // ========================================================================
    @GetMapping("/cdp/response-body/{requestId}")
    public ResponseEntity<?> getCdpResponseBody(
            @PathVariable String requestId,
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device) {
        return ResponseEntity.ok(orchestrator.getCdpResponseBody(packageName, device, requestId));
    }

    // Lifecycle
    // ========================================================================

    @PreDestroy
    void shutdown() {
        orchestrator.shutdown();
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private ResponseEntity<?> error(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
