package io.yamsergey.dta.tools.android.cdp;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Fetches the accessibility tree from a Chrome DevTools Protocol target
 * and converts it into layout_tree-compatible node format.
 *
 * <p>Uses the {@code Accessibility.getFullAXTree} CDP method to retrieve the
 * full accessibility tree, then converts it to a hierarchical structure matching
 * the layout_tree format with {@code nodeType: "web"}.</p>
 *
 * <p>Noise nodes (generic/none roles with no text or children) are filtered out
 * to keep the tree concise and useful for AI assistants.</p>
 */
public final class CdpAccessibilityInspector {

    private static final Logger log = LoggerFactory.getLogger(CdpAccessibilityInspector.class);

    private static final long CDP_TIMEOUT_MS = 10_000;

    // Roles considered noise when they have no text and no meaningful children
    private static final Set<String> NOISE_ROLES = Set.of(
        "none", "generic", "IgnoredRole"
    );

    private CdpAccessibilityInspector() {}

    /**
     * Fetches the full accessibility tree from the given CDP client and returns
     * it as a list of layout_tree-compatible child nodes.
     *
     * <p>The client must already be attached to a page target via WebSocket.</p>
     *
     * @param client an attached ChromeDevToolsClient
     * @return list of root-level web nodes (to inject as children of a WebView node),
     *         or empty list if the tree cannot be fetched
     */
    public static List<Map<String, Object>> fetchAccessibilityTree(ChromeDevToolsClient client) {
        try {
            // Enable the Accessibility domain
            client.send("Accessibility.enable", Map.of())
                .get(CDP_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            // Fetch the full AX tree
            JsonNode result = client.send("Accessibility.getFullAXTree", Map.of())
                .get(CDP_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            JsonNode nodesArray = result.get("nodes");
            if (nodesArray == null || !nodesArray.isArray() || nodesArray.isEmpty()) {
                log.debug("Empty accessibility tree received");
                return List.of();
            }

            return buildTree(nodesArray);

        } catch (Exception e) {
            log.warn("Failed to fetch accessibility tree: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Fetches the accessibility tree and also returns the page URL and viewport info.
     * Resolves CSS bounds for each node via {@code DOM.getBoxModel} and includes
     * viewport dimensions for screen-coordinate conversion.
     *
     * @param client an attached ChromeDevToolsClient
     * @return result containing the tree nodes, page URL, and viewport dimensions
     */
    public static AccessibilityTreeResult fetchAccessibilityTreeWithUrl(ChromeDevToolsClient client) {
        String url = null;
        double viewportWidth = 0;
        double viewportHeight = 0;
        double devicePixelRatio = 0;
        double screenOffsetY = 0;

        try {
            // Get page URL
            JsonNode docResult = client.send("DOM.getDocument", Map.of())
                .get(CDP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            JsonNode root = docResult.get("root");
            if (root != null) {
                url = root.path("documentURL").asText(null);
            }
        } catch (Exception e) {
            log.debug("Could not get page URL: {}", e.getMessage());
        }

        try {
            // Get viewport dimensions for coordinate transformation
            JsonNode metrics = client.send("Page.getLayoutMetrics", Map.of())
                .get(CDP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            JsonNode vv = metrics.get("cssVisualViewport");
            if (vv != null) {
                viewportWidth = vv.path("clientWidth").asDouble(0);
                viewportHeight = vv.path("clientHeight").asDouble(0);
            }
        } catch (Exception e) {
            log.debug("Could not get viewport metrics: {}", e.getMessage());
        }

        try {
            // Get DPR and innerHeight
            JsonNode jsResult = client.send("Runtime.evaluate",
                    Map.of("expression",
                        "JSON.stringify({dpr: window.devicePixelRatio, innerH: window.innerHeight})",
                        "returnByValue", true))
                .get(CDP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            String json = jsResult.path("result").path("value").asText("{}");
            JsonNode parsed = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            devicePixelRatio = parsed.path("dpr").asDouble(0);
            double innerH = parsed.path("innerH").asDouble(0);
            double viewportPx = innerH * devicePixelRatio;

            // Determine the Chrome toolbar height from a CDP viewport screenshot.
            // Page.captureScreenshot captures the browser window content (toolbar + viewport)
            // excluding the system status bar and navigation bar.
            // So: pngHeight = chromeToolbar + viewport → chromeToolbar = pngHeight - viewport
            JsonNode ssResult = client.send("Page.captureScreenshot",
                    Map.of("format", "png", "captureBeyondViewport", false))
                .get(CDP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            String ssData = ssResult.path("data").asText("");
            if (!ssData.isEmpty()) {
                byte[] imgBytes = java.util.Base64.getDecoder().decode(ssData);
                // Read PNG height from IHDR chunk (bytes 20-23, big-endian)
                if (imgBytes.length > 24) {
                    int pngHeight = ((imgBytes[20] & 0xFF) << 24) | ((imgBytes[21] & 0xFF) << 16)
                                  | ((imgBytes[22] & 0xFF) << 8)  |  (imgBytes[23] & 0xFF);
                    double chromeToolbarPx = pngHeight - viewportPx;
                    // screenOffsetY = status bar + Chrome toolbar.
                    // We store just the toolbar for now; the caller (InspectorController)
                    // will add the status bar height from ADB if available.
                    screenOffsetY = chromeToolbarPx;
                    log.debug("Viewport offset: dpr={}, innerH={}, viewportPx={}, pngH={}, "
                        + "chromeToolbar={}px (screenOffsetY={}px, caller should add status bar)",
                        devicePixelRatio, innerH, viewportPx, pngHeight,
                        chromeToolbarPx, screenOffsetY);
                }
            }
        } catch (Exception e) {
            log.debug("Could not get devicePixelRatio/offset: {}", e.getMessage());
        }

        // Fetch AX tree and resolve bounds
        List<Map<String, Object>> nodes = fetchAccessibilityTreeWithBounds(client);
        return new AccessibilityTreeResult(nodes, url, viewportWidth, viewportHeight,
            devicePixelRatio, screenOffsetY);
    }

    /**
     * Fetches the accessibility tree and resolves CSS bounding boxes for each node.
     */
    private static List<Map<String, Object>> fetchAccessibilityTreeWithBounds(
            ChromeDevToolsClient client) {
        try {
            client.send("Accessibility.enable", Map.of())
                .get(CDP_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            JsonNode result = client.send("Accessibility.getFullAXTree", Map.of())
                .get(CDP_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            JsonNode nodesArray = result.get("nodes");
            if (nodesArray == null || !nodesArray.isArray() || nodesArray.isEmpty()) {
                return List.of();
            }

            // Build the tree, collecting backendDOMNodeId → converted node mappings
            Map<Integer, Map<String, Object>> backendIdToNode = new LinkedHashMap<>();
            List<Map<String, Object>> tree = buildTreeWithBackendIds(nodesArray, backendIdToNode);

            // Resolve bounds for all nodes with backendDOMNodeIds
            resolveBounds(client, backendIdToNode);

            return tree;

        } catch (Exception e) {
            log.warn("Failed to fetch accessibility tree with bounds: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Result of an accessibility tree fetch, including the page URL and viewport info.
     */
    public record AccessibilityTreeResult(
        List<Map<String, Object>> nodes,
        String url,
        double viewportWidth,
        double viewportHeight,
        double devicePixelRatio,
        double screenOffsetY
    ) {
        public AccessibilityTreeResult(List<Map<String, Object>> nodes, String url) {
            this(nodes, url, 0, 0, 0, 0);
        }
    }

    /**
     * Builds a hierarchical tree from the flat CDP AX node list.
     */
    private static List<Map<String, Object>> buildTree(JsonNode nodesArray) {
        // Index nodes by ID
        Map<String, JsonNode> nodeById = new LinkedHashMap<>();
        for (JsonNode node : nodesArray) {
            String nodeId = node.path("nodeId").asText(null);
            if (nodeId != null) {
                nodeById.put(nodeId, node);
            }
        }

        // Build parent→children mapping
        Map<String, List<String>> childrenMap = new LinkedHashMap<>();
        for (JsonNode node : nodesArray) {
            String nodeId = node.path("nodeId").asText(null);
            if (nodeId == null) continue;

            JsonNode childIds = node.get("childIds");
            if (childIds != null && childIds.isArray()) {
                List<String> children = new ArrayList<>();
                for (JsonNode childId : childIds) {
                    children.add(childId.asText());
                }
                childrenMap.put(nodeId, children);
            }
        }

        // Find root node (first node, typically the "RootWebArea")
        if (nodeById.isEmpty()) {
            return List.of();
        }

        String rootId = nodeById.keySet().iterator().next();

        // Convert the root node and its descendants
        // The root is typically a RootWebArea — we return it as the top-level node
        // so the web content has a clear root with the page title.
        return convertNodes(rootId, nodeById, childrenMap);
    }

    /**
     * Recursively converts a CDP AX node and its descendants to layout_tree-compatible maps.
     * Uses a list-based return to support promoting children of filtered nodes.
     */
    private static List<Map<String, Object>> convertNodes(
            String nodeId,
            Map<String, JsonNode> nodeById,
            Map<String, List<String>> childrenMap) {

        JsonNode axNode = nodeById.get(nodeId);
        if (axNode == null) return List.of();

        // Extract role and name
        String role = extractPropertyValue(axNode, "role");
        String name = extractPropertyValue(axNode, "name");
        String value = extractPropertyValue(axNode, "value");
        String description = extractPropertyValue(axNode, "description");

        // Check if node is ignored
        JsonNode ignoredNode = axNode.get("ignored");
        boolean ignored = ignoredNode != null && ignoredNode.asBoolean(false);

        // Recursively convert children
        List<String> childIds = childrenMap.getOrDefault(nodeId, List.of());
        List<Map<String, Object>> children = new ArrayList<>();
        for (String childId : childIds) {
            children.addAll(convertNodes(childId, nodeById, childrenMap));
        }

        // Filter noise: ignored nodes, or noise-role nodes with no meaningful text.
        // Always promote children of filtered nodes so content isn't lost.
        boolean isNoise = NOISE_ROLES.contains(role) && isBlank(name) && isBlank(value);
        if (ignored || isNoise) {
            // Promote children up to the parent (or return empty if no children)
            return children;
        }

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("nodeType", "web");

        if (role != null && !role.isEmpty()) {
            node.put("role", role);
        }
        if (!isBlank(name)) {
            node.put("text", name);
        }
        if (!isBlank(value)) {
            node.put("value", value);
        }
        if (!isBlank(description)) {
            node.put("description", description);
        }

        // Extract additional properties
        JsonNode properties = axNode.get("properties");
        if (properties != null && properties.isArray()) {
            for (JsonNode prop : properties) {
                String propName = prop.path("name").asText("");
                JsonNode propValue = prop.get("value");
                if (propValue == null) continue;

                switch (propName) {
                    case "focused" -> {
                        if (propValue.path("value").asBoolean(false))
                            node.put("isFocused", true);
                    }
                    case "disabled" -> {
                        if (propValue.path("value").asBoolean(false))
                            node.put("isEnabled", false);
                    }
                    case "checked" -> {
                        String checkedVal = propValue.path("value").asText("");
                        if (!"false".equals(checkedVal))
                            node.put("isChecked", checkedVal);
                    }
                    case "expanded" -> {
                        node.put("isExpanded", propValue.path("value").asBoolean(false));
                    }
                    case "url" -> {
                        String urlVal = propValue.path("value").asText(null);
                        if (urlVal != null && !urlVal.isEmpty())
                            node.put("url", urlVal);
                    }
                    default -> { /* skip other properties */ }
                }
            }
        }

        if (!children.isEmpty()) {
            node.put("children", children);
        }

        return List.of(node);
    }

    /**
     * Builds a hierarchical tree from the flat CDP AX node list, collecting
     * backendDOMNodeId mappings for subsequent bounds resolution.
     */
    private static List<Map<String, Object>> buildTreeWithBackendIds(
            JsonNode nodesArray, Map<Integer, Map<String, Object>> backendIdToNode) {

        Map<String, JsonNode> nodeById = new LinkedHashMap<>();
        for (JsonNode node : nodesArray) {
            String nodeId = node.path("nodeId").asText(null);
            if (nodeId != null) {
                nodeById.put(nodeId, node);
            }
        }

        Map<String, List<String>> childrenMap = new LinkedHashMap<>();
        for (JsonNode node : nodesArray) {
            String nodeId = node.path("nodeId").asText(null);
            if (nodeId == null) continue;
            JsonNode childIds = node.get("childIds");
            if (childIds != null && childIds.isArray()) {
                List<String> children = new ArrayList<>();
                for (JsonNode childId : childIds) {
                    children.add(childId.asText());
                }
                childrenMap.put(nodeId, children);
            }
        }

        if (nodeById.isEmpty()) return List.of();

        String rootId = nodeById.keySet().iterator().next();
        return convertNodesWithBackendIds(rootId, nodeById, childrenMap, backendIdToNode);
    }

    /**
     * Recursively converts CDP AX nodes, tracking backendDOMNodeId for bounds resolution.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> convertNodesWithBackendIds(
            String nodeId,
            Map<String, JsonNode> nodeById,
            Map<String, List<String>> childrenMap,
            Map<Integer, Map<String, Object>> backendIdToNode) {

        JsonNode axNode = nodeById.get(nodeId);
        if (axNode == null) return List.of();

        String role = extractPropertyValue(axNode, "role");
        String name = extractPropertyValue(axNode, "name");
        String value = extractPropertyValue(axNode, "value");
        String description = extractPropertyValue(axNode, "description");

        JsonNode ignoredNode = axNode.get("ignored");
        boolean ignored = ignoredNode != null && ignoredNode.asBoolean(false);

        List<String> childIds = childrenMap.getOrDefault(nodeId, List.of());
        List<Map<String, Object>> children = new ArrayList<>();
        for (String childId : childIds) {
            children.addAll(convertNodesWithBackendIds(childId, nodeById, childrenMap, backendIdToNode));
        }

        boolean isNoise = NOISE_ROLES.contains(role) && isBlank(name) && isBlank(value);
        if (ignored || isNoise) {
            return children;
        }

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("nodeType", "web");

        if (role != null && !role.isEmpty()) node.put("role", role);
        if (!isBlank(name)) node.put("text", name);
        if (!isBlank(value)) node.put("value", value);
        if (!isBlank(description)) node.put("description", description);

        // Extract additional properties (same as convertNodes)
        JsonNode properties = axNode.get("properties");
        if (properties != null && properties.isArray()) {
            for (JsonNode prop : properties) {
                String propName = prop.path("name").asText("");
                JsonNode propValue = prop.get("value");
                if (propValue == null) continue;
                switch (propName) {
                    case "focused" -> {
                        if (propValue.path("value").asBoolean(false)) node.put("isFocused", true);
                    }
                    case "disabled" -> {
                        if (propValue.path("value").asBoolean(false)) node.put("isEnabled", false);
                    }
                    case "checked" -> {
                        String cv = propValue.path("value").asText("");
                        if (!"false".equals(cv)) node.put("isChecked", cv);
                    }
                    case "expanded" -> node.put("isExpanded", propValue.path("value").asBoolean(false));
                    case "url" -> {
                        String u = propValue.path("value").asText(null);
                        if (u != null && !u.isEmpty()) node.put("url", u);
                    }
                    default -> {}
                }
            }
        }

        if (!children.isEmpty()) node.put("children", children);

        // Track backendDOMNodeId for bounds resolution
        int backendId = axNode.path("backendDOMNodeId").asInt(0);
        if (backendId > 0) {
            backendIdToNode.put(backendId, node);
        }

        return List.of(node);
    }

    /**
     * Resolves CSS bounding boxes for nodes using {@code DOM.getBoxModel}.
     * Adds {@code cssBounds} (left, top, right, bottom in CSS pixels) to each node.
     */
    private static void resolveBounds(ChromeDevToolsClient client,
                                       Map<Integer, Map<String, Object>> backendIdToNode) {
        if (backendIdToNode.isEmpty()) return;

        for (var entry : backendIdToNode.entrySet()) {
            int backendId = entry.getKey();
            Map<String, Object> node = entry.getValue();

            try {
                JsonNode result = client.send("DOM.getBoxModel",
                        Map.of("backendNodeId", backendId))
                    .get(2000, TimeUnit.MILLISECONDS);

                JsonNode model = result.get("model");
                if (model != null) {
                    // content quad: [x1,y1, x2,y1, x2,y2, x1,y2]
                    JsonNode content = model.get("content");
                    if (content != null && content.isArray() && content.size() >= 6) {
                        double left = content.get(0).asDouble();
                        double top = content.get(1).asDouble();
                        double right = content.get(2).asDouble();
                        double bottom = content.get(5).asDouble();

                        Map<String, Object> cssBounds = new LinkedHashMap<>();
                        cssBounds.put("left", left);
                        cssBounds.put("top", top);
                        cssBounds.put("right", right);
                        cssBounds.put("bottom", bottom);
                        node.put("cssBounds", cssBounds);
                    }
                }
            } catch (Exception e) {
                // Node may be invisible or off-screen — skip silently
            }
        }
    }

    /**
     * Extracts the string value from an AX node property (role, name, value, description).
     */
    private static String extractPropertyValue(JsonNode axNode, String propertyName) {
        JsonNode prop = axNode.get(propertyName);
        if (prop == null) return null;

        // CDP returns {type: "...", value: "..."}
        JsonNode valueNode = prop.get("value");
        if (valueNode != null) {
            return valueNode.asText(null);
        }

        // Fallback: direct string
        return prop.asText(null);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
