package io.yamsergey.dta.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.yamsergey.dta.tools.android.cdp.CdpAccessibilityInspector;
import io.yamsergey.dta.tools.android.cdp.CdpWatcherManager;
import io.yamsergey.dta.tools.android.cdp.ChromeDevToolsClient;
import io.yamsergey.dta.tools.android.cdp.WebViewCdpManager;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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
                boolean hasFilters = text != null || type != null || resource_id != null || view_id != null;

                if (hasFilters) {
                    return ResponseEntity.ok(adaptFilteredResponse(tree));
                }

                // Enrich WebView/Custom Tab nodes with web accessibility trees
                JsonNode adapted = adaptUnfilteredResponse(tree);
                enrichWebViewNodes(adapted, packageName, device);

                return ResponseEntity.ok(adapted);
            }
            String desc = result instanceof Failure<?> f ? f.description() : "Unknown error";
            return error("Failed to get layout tree: " + desc);
        } catch (Exception e) {
            return error("Failed: " + e.getMessage());
        }
    }

    /**
     * Adapts unfiltered layout_tree response: flattens {@code windows} into a single {@code root}.
     * <ul>
     *   <li>1 window → {@code root = windows[0].tree}</li>
     *   <li>N windows → synthesize a {@code root} with each window tree as a child</li>
     * </ul>
     */
    private JsonNode adaptUnfilteredResponse(JsonNode tree) {
        JsonNode windows = tree.get("windows");
        if (windows == null || !windows.isArray() || windows.isEmpty()) {
            return tree;
        }

        ObjectNode result = mapper.createObjectNode();

        // Copy top-level metadata (screen, timestamp, etc.)
        tree.fieldNames().forEachRemaining(field -> {
            if (!"windows".equals(field)) {
                result.set(field, tree.get(field));
            }
        });

        if (windows.size() == 1) {
            JsonNode windowTree = windows.get(0).get("tree");
            result.set("root", windowTree != null ? windowTree : mapper.createObjectNode());
        } else {
            // Multiple windows: synthesize a virtual root
            ObjectNode virtualRoot = mapper.createObjectNode();
            virtualRoot.put("nodeType", "view");
            virtualRoot.put("className", "Windows");
            ArrayNode children = virtualRoot.putArray("children");
            for (JsonNode window : windows) {
                JsonNode windowTree = window.get("tree");
                if (windowTree != null) {
                    children.add(windowTree);
                }
            }
            result.set("root", virtualRoot);
        }

        return result;
    }

    /**
     * Adapts filtered layout_tree response: flattens {@code windows[].matches} into
     * a top-level {@code matches} array with {@code {node, parents}} entries.
     * The {@code parentChain} (list of strings) is converted to {@code parents}
     * (list of objects with {@code composable} field) for web inspector compatibility.
     */
    private JsonNode adaptFilteredResponse(JsonNode tree) {
        ObjectNode result = mapper.createObjectNode();

        // Copy top-level metadata (filters, totalMatches, screen, timestamp)
        tree.fieldNames().forEachRemaining(field -> {
            if (!"windows".equals(field)) {
                result.set(field, tree.get(field));
            }
        });

        ArrayNode allMatches = mapper.createArrayNode();
        JsonNode windows = tree.get("windows");
        if (windows != null && windows.isArray()) {
            for (JsonNode window : windows) {
                JsonNode matches = window.get("matches");
                if (matches != null && matches.isArray()) {
                    for (JsonNode match : matches) {
                        ObjectNode entry = mapper.createObjectNode();
                        // The match node itself (without parentChain) becomes "node"
                        ObjectNode node = match.deepCopy();
                        node.remove("parentChain");
                        entry.set("node", node);

                        // Convert parentChain strings to parent objects
                        ArrayNode parents = mapper.createArrayNode();
                        JsonNode parentChain = match.get("parentChain");
                        if (parentChain != null && parentChain.isArray()) {
                            for (JsonNode name : parentChain) {
                                ObjectNode parent = mapper.createObjectNode();
                                parent.put("composable", name.asText());
                                parents.add(parent);
                            }
                        }
                        entry.set("parents", parents);

                        allMatches.add(entry);
                    }
                }
            }
        }

        result.set("matches", allMatches);
        return result;
    }

    // ========================================================================
    // WebView/Custom Tab enrichment
    // ========================================================================

    private record WebViewInfo(ObjectNode node, String url) {}

    /**
     * Enriches the layout tree with web accessibility content from WebViews
     * and Chrome Custom Tabs. Non-fatal — the tree is returned unchanged on errors.
     */
    private void enrichWebViewNodes(JsonNode adapted, String packageName, String device) {
        // Stage 5: Enrich in-app WebView nodes
        try {
            JsonNode root = adapted.get("root");
            if (root != null) {
                List<WebViewInfo> webViewNodes = new ArrayList<>();
                collectWebViewNodes(root, webViewNodes);

                if (!webViewNodes.isEmpty()) {
                    try (WebViewCdpManager manager = new WebViewCdpManager(device)) {
                        List<WebViewCdpManager.WebViewTreeResult> trees = manager.fetchWebViewTrees();
                        if (!trees.isEmpty()) {
                            matchAndInjectWebViewTrees(webViewNodes, trees);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("WebView enrichment failed (non-fatal): {}", e.getMessage());
        }

        // Stage 6: Enrich with Custom Tab if active
        // Runs even when root is null (Custom Tab may be in foreground with no app windows)
        try {
            enrichCustomTabNode(adapted, packageName, device);
        } catch (Exception e) {
            log.debug("Custom Tab enrichment failed (non-fatal): {}", e.getMessage());
        }
    }

    /**
     * Recursively walks the tree to find nodes with {@code isWebView: true}.
     */
    private void collectWebViewNodes(JsonNode node, List<WebViewInfo> results) {
        if (node == null || !node.isObject()) return;

        if (node.path("isWebView").asBoolean(false)) {
            String url = node.path("webViewUrl").asText(null);
            if (node instanceof ObjectNode objNode) {
                results.add(new WebViewInfo(objNode, url));
            }
        }

        // Recurse into children
        JsonNode children = node.get("children");
        if (children != null && children.isArray()) {
            for (JsonNode child : children) {
                collectWebViewNodes(child, results);
            }
        }
    }

    /**
     * Matches fetched WebView accessibility trees to WebView nodes by URL
     * and injects the web nodes as children.
     * Transforms CSS bounds from CDP into screen-pixel bounds using the
     * WebView's position and viewport dimensions.
     */
    private void matchAndInjectWebViewTrees(
            List<WebViewInfo> webViewNodes,
            List<WebViewCdpManager.WebViewTreeResult> trees) {

        for (WebViewInfo webViewInfo : webViewNodes) {
            WebViewCdpManager.WebViewTreeResult bestMatch = null;

            if (webViewInfo.url() != null) {
                // Match by URL
                for (WebViewCdpManager.WebViewTreeResult tree : trees) {
                    if (urlsMatch(webViewInfo.url(), tree.url())) {
                        bestMatch = tree;
                        break;
                    }
                }
            }

            // If only one WebView and one tree, match them directly
            if (bestMatch == null && webViewNodes.size() == 1 && trees.size() == 1) {
                bestMatch = trees.get(0);
            }

            if (bestMatch != null && !bestMatch.children().isEmpty()) {
                // Transform CSS bounds → screen bounds using WebView's position
                JsonNode wvBounds = webViewInfo.node().get("bounds");
                if (wvBounds != null && bestMatch.viewportWidth() > 0) {
                    double wvLeft = wvBounds.path("left").asDouble(0);
                    double wvTop = wvBounds.path("top").asDouble(0);
                    double wvWidth = wvBounds.path("width").asDouble(0);
                    double scale = wvWidth / bestMatch.viewportWidth();
                    transformCssBoundsToScreen(bestMatch.children(), wvLeft, wvTop, scale);
                }

                ArrayNode children = mapper.valueToTree(bestMatch.children());
                webViewInfo.node().set("children", children);
                log.debug("Injected {} web nodes into WebView (url={})",
                    bestMatch.children().size(), bestMatch.url());
            }
        }
    }

    /**
     * Recursively transforms {@code cssBounds} on web nodes into screen-pixel {@code bounds}.
     *
     * @param nodes    list of web node maps (may contain children)
     * @param offsetX  WebView left edge in screen pixels
     * @param offsetY  WebView top edge in screen pixels
     * @param scale    ratio of screen pixels to CSS pixels (webViewWidth / viewportWidth)
     */
    @SuppressWarnings("unchecked")
    private static void transformCssBoundsToScreen(
            List<Map<String, Object>> nodes, double offsetX, double offsetY, double scale) {
        for (Map<String, Object> node : nodes) {
            Object cssBoundsObj = node.remove("cssBounds");
            if (cssBoundsObj instanceof Map<?, ?> cssBounds) {
                double cssLeft = toDouble(cssBounds.get("left"));
                double cssTop = toDouble(cssBounds.get("top"));
                double cssRight = toDouble(cssBounds.get("right"));
                double cssBottom = toDouble(cssBounds.get("bottom"));

                int left = (int) Math.round(offsetX + cssLeft * scale);
                int top = (int) Math.round(offsetY + cssTop * scale);
                int right = (int) Math.round(offsetX + cssRight * scale);
                int bottom = (int) Math.round(offsetY + cssBottom * scale);

                Map<String, Object> bounds = new LinkedHashMap<>();
                bounds.put("left", left);
                bounds.put("top", top);
                bounds.put("right", right);
                bounds.put("bottom", bottom);
                bounds.put("width", right - left);
                bounds.put("height", bottom - top);
                node.put("bounds", bounds);
            }

            Object childrenObj = node.get("children");
            if (childrenObj instanceof List<?> children) {
                transformCssBoundsToScreen((List<Map<String, Object>>) children,
                    offsetX, offsetY, scale);
            }
        }
    }

    private static double toDouble(Object val) {
        if (val instanceof Number n) return n.doubleValue();
        return 0;
    }

    /**
     * Returns the device status bar height in pixels by parsing ADB dumpsys output.
     * Returns 0 if unavailable (non-fatal).
     */
    private double getStatusBarHeight(String device) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("adb");
            if (device != null && !device.isEmpty()) {
                cmd.add("-s");
                cmd.add(device);
            }
            cmd.add("shell");
            cmd.add("dumpsys");
            cmd.add("window");
            cmd.add("windows");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return 0;
            }

            // Look for status bar insets, e.g.:
            // type=statusBars, source=FRAME, ..., insetsSize=Insets{left=0, top=63, right=0, bottom=0}
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "type=statusBars.*insetsSize=Insets\\{[^}]*top=(\\d+)");
            java.util.regex.Matcher matcher = pattern.matcher(output);
            if (matcher.find()) {
                double height = Double.parseDouble(matcher.group(1));
                log.debug("Status bar height from ADB: {}px", height);
                return height;
            }
        } catch (Exception e) {
            log.debug("Failed to get status bar height: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * If a Chrome Custom Tab is active, fetches its accessibility tree and
     * injects a synthetic node into the layout tree.
     *
     * <p>When the Custom Tab is in the foreground, the app's layout tree has no windows.
     * In this case we create a synthetic root containing the Custom Tab node, so the
     * tree is still useful for AI assistants.</p>
     */
    private void enrichCustomTabNode(JsonNode adapted, String packageName, String device) {
        try {
            ChromeDevToolsClient cdpClient = CdpWatcherManager.getInstance()
                .getCdpClient(packageName, device);
            if (cdpClient == null || !cdpClient.isConnected()) return;

            String tabUrl = CdpWatcherManager.getInstance().getCurrentTabUrl(packageName, device);

            CdpAccessibilityInspector.AccessibilityTreeResult treeResult =
                CdpAccessibilityInspector.fetchAccessibilityTreeWithUrl(cdpClient);

            if (treeResult.nodes().isEmpty()) return;

            // Build a synthetic Custom Tab node
            Map<String, Object> customTabNode = new LinkedHashMap<>();
            customTabNode.put("nodeType", "web");
            customTabNode.put("className", "ChromeCustomTab");
            String url = treeResult.url() != null ? treeResult.url() : tabUrl;
            if (url != null) {
                customTabNode.put("webViewUrl", url);
            }
            // Transform CSS bounds → screen bounds for Custom Tab
            if (treeResult.viewportWidth() > 0) {
                double scale = 0;
                // Prefer screen dimensions from layout tree metadata
                JsonNode screen = adapted.get("screen");
                if (screen != null) {
                    double screenWidth = screen.path("width").asDouble(0);
                    if (screenWidth > 0) {
                        scale = screenWidth / treeResult.viewportWidth();
                    }
                }
                // Fallback: use devicePixelRatio from CDP (when app has no windows)
                if (scale == 0 && treeResult.devicePixelRatio() > 0) {
                    scale = treeResult.devicePixelRatio();
                }
                if (scale > 0) {
                    // screenOffsetY from CDP = Chrome toolbar height (px).
                    // Add the device status bar height to get the full top offset.
                    double offsetY = treeResult.screenOffsetY() + getStatusBarHeight(device);
                    transformCssBoundsToScreen(treeResult.nodes(), 0, offsetY, scale);
                }
            }

            customTabNode.put("children", treeResult.nodes());

            // Inject at the end of root's children (or create a synthetic root)
            JsonNode root = adapted.get("root");
            if (root instanceof ObjectNode rootObj) {
                ArrayNode children = (ArrayNode) root.get("children");
                if (children == null) {
                    children = mapper.createArrayNode();
                    rootObj.set("children", children);
                }
                children.add(mapper.valueToTree(customTabNode));
            } else if (adapted instanceof ObjectNode adaptedObj) {
                // No root (Custom Tab is in foreground, app has no windows).
                // Create a synthetic root with the Custom Tab as its only child.
                ObjectNode syntheticRoot = mapper.createObjectNode();
                syntheticRoot.put("nodeType", "view");
                syntheticRoot.put("className", "ChromeCustomTabWindow");
                ArrayNode children = syntheticRoot.putArray("children");
                children.add(mapper.valueToTree(customTabNode));
                adaptedObj.set("root", syntheticRoot);
            }

            log.debug("Injected Custom Tab node (url={}, {} web nodes)",
                url, treeResult.nodes().size());

        } catch (Exception e) {
            log.debug("Custom Tab enrichment failed (non-fatal): {}", e.getMessage());
        }
    }

    /**
     * Simple URL matching: exact match, or one URL starts with the other's base.
     */
    private static boolean urlsMatch(String url1, String url2) {
        if (url1 == null || url2 == null) return false;
        if (url1.equals(url2)) return true;

        // Normalize trailing slashes and compare
        String norm1 = url1.endsWith("/") ? url1.substring(0, url1.length() - 1) : url1;
        String norm2 = url2.endsWith("/") ? url2.substring(0, url2.length() - 1) : url2;
        if (norm1.equals(norm2)) return true;

        // Check if one is a prefix of the other (handles query params differences)
        return norm1.startsWith(norm2) || norm2.startsWith(norm1);
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
     *
     * <p>Synchronized to prevent concurrent requests from racing to create/destroy watchers.
     * Multiple requests arrive simultaneously on first connection and each calls
     * getConnectionWithCdp → ensureCdpWatcher. Without synchronization, losing threads
     * call stopWatcher() which kills the shared watcher the winning thread created.</p>
     */
    private synchronized void ensureCdpWatcher(String packageName, String device, ConnectionInfo conn) {
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
            sseListeners.put(listenerKey, sseListener);

            log.info("Auto-enabled CDP capture for package={}, device={}", packageName, device);
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
    @GetMapping("/cdp/response-body/{requestId}")
    public ResponseEntity<?> getCdpResponseBody(
            @PathVariable String requestId,
            @RequestParam("package") String packageName,
            @RequestParam(required = false) String device) {
        try {
            String key = makeListenerKey(packageName, device);
            ChromeDevToolsClient client = CdpWatcherManager.getInstance().getCdpClient(packageName,
                    device != null ? device : "emulator-5554");
            if (client == null || !client.isConnected()) {
                return ResponseEntity.ok(Map.of("error", "CDP not connected. Open a Custom Tab first."));
            }
            var body = client.getResponseBody(requestId)
                    .orTimeout(5, TimeUnit.SECONDS)
                    .join();
            if (body != null) {
                return ResponseEntity.ok(Map.of(
                        "body", body.body() != null ? body.body() : "",
                        "base64Encoded", body.base64Encoded()));
            }
            return ResponseEntity.ok(Map.of("error", "No body returned"));
        } catch (Exception e) {
            String msg = e.getMessage();
            if (e.getCause() != null) msg = e.getCause().getMessage();
            return ResponseEntity.ok(Map.of("error", "Failed to fetch body: " + msg));
        }
    }

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
