package io.yamsergey.dta.daemon;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.yamsergey.dta.daemon.scroll.ScrollScreenshot;
import io.yamsergey.dta.daemon.scroll.ScrollScreenshotCapture;
import io.yamsergey.dta.daemon.sidekick.SidekickConnectionManager;
import io.yamsergey.dta.daemon.sidekick.SidekickConnectionManager.Device;
import io.yamsergey.dta.daemon.sidekick.SidekickConnectionManager.SidekickSocket;
import io.yamsergey.dta.tools.sugar.Failure;
import io.yamsergey.dta.tools.sugar.Result;
import io.yamsergey.dta.tools.sugar.Success;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.nio.file.Files;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers all REST routes on a Javalin app instance, delegating to
 * {@link DtaOrchestrator} and {@link SidekickConnectionManager}.
 *
 * <p>All routes use the {@code /api/} prefix for compatibility with the
 * MCP DaemonClient.</p>
 */
public final class DtaRoutes {

    private static final Logger log = LoggerFactory.getLogger(DtaRoutes.class);

    private static final ObjectMapper mapper = new ObjectMapper();

    private DtaRoutes() {}

    /**
     * Registers all API routes on the given Javalin app.
     */
    public static void register(Javalin app) {
        DtaOrchestrator orchestrator = DtaOrchestrator.getInstance();
        SidekickConnectionManager connectionManager = SidekickConnectionManager.getInstance();

        // ====================================================================
        // Version
        // ====================================================================

        app.get("/api/version", ctx -> {
            Map<String, Object> info = new HashMap<>();
            info.put("name", "dta-daemon");
            info.put("version", "standalone");
            info.put("daemon", true);
            info.put("pid", ProcessHandle.current().pid());
            ctx.json(info);
        });

        app.get("/api/connection-status", ctx -> {
            try {
                String packageName = ctx.queryParam("package");
                String device = ctx.queryParam("device");
                ctx.json(orchestrator.getConnectionStatus(packageName, device, "standalone"));
            } catch (Exception e) {
                error(ctx, "Failed to get connection status: " + e.getMessage());
            }
        });

        // ====================================================================
        // Device / App discovery
        // ====================================================================

        app.get("/api/devices", ctx -> {
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
                jsonNode(ctx, result);
            } catch (Exception e) {
                error(ctx, "Failed to list devices: " + e.getMessage());
            }
        });

        app.get("/api/apps", ctx -> {
            try {
                String device = ctx.queryParam("device");
                List<SidekickSocket> sockets = orchestrator.listApps(device);
                var result = mapper.createObjectNode();
                var appsArray = result.putArray("apps");
                for (SidekickSocket socket : sockets) {
                    var a = appsArray.addObject();
                    a.put("package", socket.packageName());
                    a.put("socket", socket.socketName());
                }
                jsonNode(ctx, result);
            } catch (Exception e) {
                error(ctx, "Failed to list apps: " + e.getMessage());
            }
        });

        // ====================================================================
        // App build/install/launch
        // ====================================================================

        app.post("/api/run/app", ctx -> {
            try {
                JsonNode body = mapper.readTree(ctx.body());
                String project = textOrNull(body, "project");
                if (project == null || project.isEmpty()) {
                    error(ctx, "'project' is required");
                    return;
                }
                String device = textOrNull(body, "device");
                String variant = textOrNull(body, "variant");
                String module = textOrNull(body, "module");
                String activity = textOrNull(body, "activity");

                var result = orchestrator.runApp(project, device, variant, module, activity);

                var json = mapper.createObjectNode();
                json.put("success", result.success());
                if (result.success()) {
                    json.put("packageName", result.packageName());
                    json.put("apkPath", result.apkPath());
                    json.put("launchActivity", result.launchActivity());
                } else {
                    json.put("error", result.error());
                }
                jsonNode(ctx, json);
            } catch (Exception e) {
                log.error("run_app failed", e);
                error(ctx, "run_app failed: " + e.getMessage());
            }
        });

        // ====================================================================
        // Screenshot
        // ====================================================================

        app.get("/api/screenshot", ctx -> {
            try {
                String packageName = ctx.queryParam("package");
                String device = ctx.queryParam("device");
                Result<byte[]> result = orchestrator.getScreenshot(packageName, device);
                if (result instanceof Success<byte[]> success) {
                    ctx.contentType("image/png");
                    ctx.header("Cache-Control", "no-store");
                    ctx.result(success.value());
                } else {
                    String err = result instanceof Failure<byte[]> f ? f.description() : "Unknown error";
                    log.error("Screenshot failed for package={}, device={}: {}", packageName, device, err);
                    ctx.status(500).result(err);
                }
            } catch (Exception e) {
                log.error("Screenshot failed", e);
                ctx.status(500).result(e.getMessage());
            }
        });

        app.get("/api/screenshot/device", ctx -> {
            try {
                String device = ctx.queryParam("device");
                byte[] data = connectionManager.captureScreenshot(device);
                ctx.contentType("image/png");
                ctx.header("Cache-Control", "no-store");
                ctx.result(data);
            } catch (Exception e) {
                log.error("Device screenshot failed", e);
                ctx.status(500).result(e.getMessage());
            }
        });

        app.post("/api/scroll-screenshot", ctx -> {
            try {
                String device = ctx.queryParam("device");
                String viewId = ctx.queryParam("viewId");
                boolean scrollToTop = Boolean.parseBoolean(ctx.queryParamAsClass("scrollToTop", String.class).getOrDefault("false"));
                int maxCaptures = Integer.parseInt(ctx.queryParamAsClass("maxCaptures", String.class).getOrDefault("30"));

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

                    ctx.json(Map.of(
                        "imageBase64", base64,
                        "width", screenshot.getWidth(),
                        "height", screenshot.getHeight(),
                        "captures", screenshot.getCaptureCount(),
                        "reachedEnd", screenshot.isReachedScrollEnd(),
                        "scrollableView", screenshot.getScrollableViewId() != null ? screenshot.getScrollableViewId() : ""
                    ));
                } else if (result instanceof Failure<ScrollScreenshot> failure) {
                    tempFile.delete();
                    error(ctx, "Scroll screenshot failed: " + failure.description());
                } else {
                    tempFile.delete();
                    error(ctx, "Scroll screenshot failed: unknown error");
                }
            } catch (Exception e) {
                error(ctx, "Failed to capture scroll screenshot: " + e.getMessage());
            }
        });

        // ====================================================================
        // ADB interaction (tap, swipe, input-text, press-key)
        // ====================================================================

        app.post("/api/tap", ctx -> {
            try {
                int x = Integer.parseInt(ctx.queryParam("x"));
                int y = Integer.parseInt(ctx.queryParam("y"));
                String device = ctx.queryParam("device");
                boolean success = connectionManager.tap(device, x, y);
                ctx.json(Map.of("success", success, "x", x, "y", y));
            } catch (Exception e) {
                error(ctx, "Failed to tap: " + e.getMessage());
            }
        });

        app.post("/api/swipe", ctx -> {
            try {
                int x1 = Integer.parseInt(ctx.queryParam("x1"));
                int y1 = Integer.parseInt(ctx.queryParam("y1"));
                int x2 = Integer.parseInt(ctx.queryParam("x2"));
                int y2 = Integer.parseInt(ctx.queryParam("y2"));
                String durationStr = ctx.queryParam("duration");
                int duration = durationStr != null ? Integer.parseInt(durationStr) : 300;
                String device = ctx.queryParam("device");
                boolean success = connectionManager.swipe(device, x1, y1, x2, y2, duration);
                ctx.json(Map.of("success", success));
            } catch (Exception e) {
                error(ctx, "Failed to swipe: " + e.getMessage());
            }
        });

        app.post("/api/input-text", ctx -> {
            try {
                String text = ctx.queryParam("text");
                String device = ctx.queryParam("device");
                boolean success = connectionManager.inputText(device, text);
                ctx.json(Map.of("success", success));
            } catch (Exception e) {
                error(ctx, "Failed to input text: " + e.getMessage());
            }
        });

        app.post("/api/press-key", ctx -> {
            try {
                String key = ctx.queryParam("key");
                String device = ctx.queryParam("device");
                boolean success = connectionManager.pressKey(device, key);
                ctx.json(Map.of("success", success, "key", key));
            } catch (Exception e) {
                error(ctx, "Failed to press key: " + e.getMessage());
            }
        });

        // ====================================================================
        // Layout endpoints
        // ====================================================================

        app.get("/api/layout/tree", ctx -> {
            try {
                String packageName = ctx.queryParam("package");
                String device = ctx.queryParam("device");
                String text = ctx.queryParam("text");
                String type = ctx.queryParam("type");
                String resourceId = ctx.queryParam("resource_id");
                String viewIdParam = ctx.queryParam("view_id");
                JsonNode tree = orchestrator.getLayoutTree(packageName, device, text, type, resourceId, viewIdParam);
                jsonNode(ctx, tree);
            } catch (Exception e) {
                error(ctx, "Failed: " + e.getMessage());
            }
        });

        app.get("/api/layout/properties/{viewId}", ctx -> {
            try {
                String packageName = ctx.queryParam("package");
                String viewId = ctx.pathParam("viewId");
                String device = ctx.queryParam("device");
                jsonString(ctx, orchestrator.getLayoutProperties(packageName, device, viewId));
            } catch (Exception e) {
                error(ctx, "Failed: " + e.getMessage());
            }
        });

        // ====================================================================
        // Network endpoints
        // ====================================================================

        app.get("/api/network/requests", ctx -> {
            try {
                String packageName = ctx.queryParam("package");
                String device = ctx.queryParam("device");
                jsonString(ctx, orchestrator.getNetworkRequests(packageName, device));
            } catch (Exception e) {
                error(ctx, "Failed: " + e.getMessage());
            }
        });

        app.get("/api/network/requests/{requestId}", ctx -> {
            try {
                String packageName = ctx.queryParam("package");
                String requestId = ctx.pathParam("requestId");
                String device = ctx.queryParam("device");
                jsonString(ctx, orchestrator.getNetworkRequest(packageName, device, requestId));
            } catch (Exception e) {
                error(ctx, "Failed: " + e.getMessage());
            }
        });

        app.get("/api/network/requests/{requestId}/body", ctx -> {
            try {
                String packageName = ctx.queryParam("package");
                String requestId = ctx.pathParam("requestId");
                String device = ctx.queryParam("device");
                jsonString(ctx, orchestrator.getNetworkRequestBody(packageName, device, requestId));
            } catch (Exception e) {
                error(ctx, "Failed: " + e.getMessage());
            }
        });

        app.get("/api/network/stats", ctx -> {
            try {
                String packageName = ctx.queryParam("package");
                String device = ctx.queryParam("device");
                jsonString(ctx, orchestrator.getNetworkStats(packageName, device));
            } catch (Exception e) {
                error(ctx, "Failed: " + e.getMessage());
            }
        });

        app.delete("/api/network/clear", ctx -> {
            try {
                String packageName = ctx.queryParam("package");
                String device = ctx.queryParam("device");
                jsonString(ctx, orchestrator.clearNetworkRequests(packageName, device));
            } catch (Exception e) {
                error(ctx, "Failed: " + e.getMessage());
            }
        });

        // ====================================================================
        // WebSocket endpoints
        // ====================================================================

        app.get("/api/websocket/connections", ctx -> {
            try {
                String packageName = ctx.queryParam("package");
                String device = ctx.queryParam("device");
                jsonString(ctx, orchestrator.getWebSocketConnections(packageName, device));
            } catch (Exception e) {
                error(ctx, "Failed: " + e.getMessage());
            }
        });

        app.get("/api/websocket/connections/{connectionId}", ctx -> {
            try {
                String packageName = ctx.queryParam("package");
                String connectionId = ctx.pathParam("connectionId");
                String device = ctx.queryParam("device");
                jsonString(ctx, orchestrator.getWebSocketConnection(packageName, device, connectionId));
            } catch (Exception e) {
                error(ctx, "Failed: " + e.getMessage());
            }
        });

        app.delete("/api/websocket/clear", ctx -> {
            try {
                String packageName = ctx.queryParam("package");
                String device = ctx.queryParam("device");
                jsonString(ctx, orchestrator.clearWebSocketConnections(packageName, device));
            } catch (Exception e) {
                error(ctx, "Failed: " + e.getMessage());
            }
        });

        // ====================================================================
        // Element selection endpoints
        // ====================================================================

        app.get("/api/selection/element", ctx -> {
            try {
                String packageName = ctx.queryParam("package");
                String device = ctx.queryParam("device");
                jsonString(ctx, orchestrator.getSelectedElements(packageName, device));
            } catch (Exception e) {
                error(ctx, "Failed: " + e.getMessage());
            }
        });

        app.post("/api/selection/element", ctx -> {
            try {
                String packageName = ctx.queryParam("package");
                String device = ctx.queryParam("device");
                String action = ctx.queryParamAsClass("action", String.class).getOrDefault("add");
                String elementJson = ctx.body();
                String result = switch (action) {
                    case "clear" -> orchestrator.clearSelectedElements(packageName, device);
                    case "remove" -> orchestrator.removeSelectedElement(packageName, device, elementJson);
                    default -> orchestrator.addSelectedElement(packageName, device, elementJson);
                };
                jsonString(ctx, result);
            } catch (Exception e) {
                error(ctx, "Failed: " + e.getMessage());
            }
        });

        app.post("/api/selection/element-at", ctx -> {
            try {
                String packageName = ctx.queryParam("package");
                int x = Integer.parseInt(ctx.queryParam("x"));
                int y = Integer.parseInt(ctx.queryParam("y"));
                String device = ctx.queryParam("device");
                jsonString(ctx, orchestrator.selectElementAt(packageName, device, x, y));
            } catch (Exception e) {
                error(ctx, "Failed: " + e.getMessage());
            }
        });

        // ====================================================================
        // Network selection endpoints
        // ====================================================================

        app.get("/api/selection/network", ctx -> {
            try {
                String packageName = ctx.queryParam("package");
                String device = ctx.queryParam("device");
                jsonString(ctx, orchestrator.getSelectedNetworkRequests(packageName, device));
            } catch (Exception e) {
                error(ctx, "Failed: " + e.getMessage());
            }
        });

        app.post("/api/selection/network", ctx -> {
            try {
                String packageName = ctx.queryParam("package");
                String device = ctx.queryParam("device");
                String action = ctx.queryParamAsClass("action", String.class).getOrDefault("add");
                String requestJson = ctx.body();
                String result = switch (action) {
                    case "clear" -> orchestrator.clearSelectedNetworkRequests(packageName, device);
                    case "remove" -> orchestrator.removeSelectedNetworkRequest(packageName, device, requestJson);
                    default -> orchestrator.addSelectedNetworkRequest(packageName, device, requestJson);
                };
                jsonString(ctx, result);
            } catch (Exception e) {
                error(ctx, "Failed: " + e.getMessage());
            }
        });

        // ====================================================================
        // WebSocket message selection endpoints
        // ====================================================================

        app.get("/api/selection/websocket-message", ctx -> {
            try {
                String packageName = ctx.queryParam("package");
                String device = ctx.queryParam("device");
                jsonString(ctx, orchestrator.getSelectedWebSocketMessages(packageName, device));
            } catch (Exception e) {
                error(ctx, "Failed: " + e.getMessage());
            }
        });

        app.post("/api/selection/websocket-message", ctx -> {
            try {
                String packageName = ctx.queryParam("package");
                String device = ctx.queryParam("device");
                String action = ctx.queryParamAsClass("action", String.class).getOrDefault("add");
                String selectionJson = ctx.body();
                String result = switch (action) {
                    case "clear" -> orchestrator.clearSelectedWebSocketMessages(packageName, device);
                    case "remove" -> orchestrator.removeSelectedWebSocketMessage(packageName, device, selectionJson);
                    default -> orchestrator.addSelectedWebSocketMessage(packageName, device, selectionJson);
                };
                jsonString(ctx, result);
            } catch (Exception e) {
                error(ctx, "Failed: " + e.getMessage());
            }
        });

        // ====================================================================
        // Mock endpoints
        // ====================================================================

        app.get("/api/mock/rules", ctx -> {
            try {
                String packageName = ctx.queryParam("package");
                String device = ctx.queryParam("device");
                jsonString(ctx, orchestrator.getMockRules(packageName, device));
            } catch (Exception e) {
                error(ctx, "Failed: " + e.getMessage());
            }
        });

        app.get("/api/mock/rules/{ruleId}", ctx -> {
            try {
                String packageName = ctx.queryParam("package");
                String ruleId = ctx.pathParam("ruleId");
                String device = ctx.queryParam("device");
                jsonString(ctx, orchestrator.getMockRule(packageName, device, ruleId));
            } catch (Exception e) {
                error(ctx, "Failed: " + e.getMessage());
            }
        });

        app.post("/api/mock/rules", ctx -> {
            try {
                String packageName = ctx.queryParam("package");
                String device = ctx.queryParam("device");
                String ruleJson = ctx.body();
                jsonString(ctx, orchestrator.createMockRule(packageName, device, ruleJson));
            } catch (Exception e) {
                error(ctx, "Failed: " + e.getMessage());
            }
        });

        app.put("/api/mock/rules/{ruleId}", ctx -> {
            try {
                String packageName = ctx.queryParam("package");
                String ruleId = ctx.pathParam("ruleId");
                String device = ctx.queryParam("device");
                String updateJson = ctx.body();
                jsonString(ctx, orchestrator.updateMockRule(packageName, device, ruleId, updateJson));
            } catch (Exception e) {
                error(ctx, "Failed: " + e.getMessage());
            }
        });

        app.delete("/api/mock/rules/{ruleId}", ctx -> {
            try {
                String packageName = ctx.queryParam("package");
                String ruleId = ctx.pathParam("ruleId");
                String device = ctx.queryParam("device");
                jsonString(ctx, orchestrator.deleteMockRule(packageName, device, ruleId));
            } catch (Exception e) {
                error(ctx, "Failed: " + e.getMessage());
            }
        });

        app.get("/api/mock/config", ctx -> {
            try {
                String packageName = ctx.queryParam("package");
                String device = ctx.queryParam("device");
                jsonString(ctx, orchestrator.getMockConfig(packageName, device));
            } catch (Exception e) {
                error(ctx, "Failed: " + e.getMessage());
            }
        });

        app.put("/api/mock/config", ctx -> {
            try {
                String packageName = ctx.queryParam("package");
                String device = ctx.queryParam("device");
                String configJson = ctx.body();
                jsonString(ctx, orchestrator.updateMockConfig(packageName, device, configJson));
            } catch (Exception e) {
                error(ctx, "Failed: " + e.getMessage());
            }
        });

        app.post("/api/mock/from-request/{requestId}", ctx -> {
            try {
                String packageName = ctx.queryParam("package");
                String requestId = ctx.pathParam("requestId");
                String device = ctx.queryParam("device");
                jsonString(ctx, orchestrator.createMockFromRequest(packageName, device, requestId));
            } catch (Exception e) {
                error(ctx, "Failed: " + e.getMessage());
            }
        });

        app.post("/api/mock/from-message/{messageId}", ctx -> {
            try {
                String packageName = ctx.queryParam("package");
                String messageId = ctx.pathParam("messageId");
                String device = ctx.queryParam("device");
                jsonString(ctx, orchestrator.createMockFromMessage(packageName, device, messageId));
            } catch (Exception e) {
                error(ctx, "Failed: " + e.getMessage());
            }
        });

        // ====================================================================
        // CDP Watcher endpoints
        // ====================================================================

        app.post("/api/cdp/watch/start", ctx -> {
            try {
                String packageName = ctx.queryParam("package");
                String device = ctx.queryParam("device");
                boolean running = orchestrator.startCdpWatcher(packageName, device);
                ctx.json(Map.of(
                    "status", running ? "started" : "failed",
                    "message", running
                        ? "CDP capture armed — will attach on Custom Tab launch"
                        : "CDP capture could not be started (check server logs)"
                ));
            } catch (Exception e) {
                log.error("Failed to start CDP capture: {}", e.getMessage(), e);
                error(ctx, "Failed to start CDP capture: " + e.getMessage());
            }
        });

        app.post("/api/cdp/watch/stop", ctx -> {
            try {
                String packageName = ctx.queryParam("package");
                String device = ctx.queryParam("device");
                Map<String, Object> result = orchestrator.stopCdpWatcher(packageName, device);
                ctx.json(result);
            } catch (Exception e) {
                log.error("Failed to stop CDP capture: {}", e.getMessage(), e);
                error(ctx, "Failed to stop CDP capture: " + e.getMessage());
            }
        });

        app.get("/api/cdp/watch/status", ctx -> {
            try {
                String packageName = ctx.queryParam("package");
                String device = ctx.queryParam("device");
                ctx.json(orchestrator.getCdpWatcherStatus(packageName, device));
            } catch (Exception e) {
                error(ctx, "Failed to get CDP capture status: " + e.getMessage());
            }
        });

        app.get("/api/cdp/response-body/{requestId}", ctx -> {
            try {
                String requestId = ctx.pathParam("requestId");
                String packageName = ctx.queryParam("package");
                String device = ctx.queryParam("device");
                ctx.json(orchestrator.getCdpResponseBody(packageName, device, requestId));
            } catch (Exception e) {
                error(ctx, "Failed to get CDP response body: " + e.getMessage());
            }
        });
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Sends a raw JSON string as the response with application/json content type.
     */
    private static void jsonString(Context ctx, String json) {
        ctx.contentType("application/json").result(json);
    }

    /**
     * Serializes a Jackson JsonNode and sends it as the response.
     */
    private static void jsonNode(Context ctx, JsonNode node) {
        ctx.contentType("application/json").result(mapper.writeValueAsString(node));
    }

    /**
     * Sends a 400 error response with a JSON error body.
     */
    private static void error(Context ctx, String message) {
        ctx.status(400).json(Map.of("error", message));
    }

    /**
     * Returns the string value of a JSON field, or null if absent/null/empty.
     */
    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText();
        return s.isEmpty() ? null : s;
    }
}
