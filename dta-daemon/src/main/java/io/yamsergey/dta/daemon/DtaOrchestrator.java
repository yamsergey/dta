package io.yamsergey.dta.daemon;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.yamsergey.dta.daemon.cdp.CdpAccessibilityInspector;
import io.yamsergey.dta.daemon.cdp.CdpWatcherManager;
import io.yamsergey.dta.daemon.cdp.ChromeBrowserCdpManager;
import io.yamsergey.dta.daemon.cdp.ChromeDevToolsClient;
import io.yamsergey.dta.daemon.cdp.WebViewCdpManager;
import io.yamsergey.dta.daemon.runner.AppRunner;
import io.yamsergey.dta.daemon.sidekick.SidekickConnectionManager;
import io.yamsergey.dta.daemon.sidekick.SidekickConnectionManager.ConnectionInfo;
import io.yamsergey.dta.daemon.sidekick.SidekickConnectionManager.Device;
import io.yamsergey.dta.daemon.sidekick.SidekickConnectionManager.SidekickSocket;
import io.yamsergey.dta.daemon.sidekick.SidekickSseListener;
import io.yamsergey.dta.tools.sugar.Failure;
import io.yamsergey.dta.tools.sugar.Result;
import io.yamsergey.dta.tools.sugar.Success;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Central orchestrator for DTA operations. Manages device connections, CDP watchers,
 * SSE event listeners, and data fetching with WebView/Custom Tab enrichment.
 *
 * <p>This is the shared brain used by both the Spring Boot server (dta-server)
 * and the Android Studio plugin (dta-plugin). It has no dependency on Spring
 * or any web framework.</p>
 *
 * <p>Use {@link #getInstance()} to obtain the singleton instance.</p>
 */
public class DtaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DtaOrchestrator.class);

    private static final int DEFAULT_CDP_PORT = 9222;

    private static final DtaOrchestrator INSTANCE = new DtaOrchestrator();

    private final SidekickConnectionManager connectionManager = SidekickConnectionManager.getInstance();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, SidekickSseListener> sseListeners = new ConcurrentHashMap<>();

    private DtaOrchestrator() {}

    public static DtaOrchestrator getInstance() {
        return INSTANCE;
    }

    // ========================================================================
    // Connection lifecycle
    // ========================================================================

    /**
     * Returns a sidekick connection and auto-enables CDP watcher (best-effort).
     * This is the primary entry point for all data-fetching operations.
     */
    public ConnectionInfo getConnectionWithCdp(String packageName, String device) throws Exception {
        ConnectionInfo conn = connectionManager.getConnection(packageName, device);
        ensureCdpWatcher(packageName, device, conn);
        return conn;
    }

    /**
     * Returns an existing connection without starting CDP watcher.
     */
    public ConnectionInfo getConnection(String packageName, String device) throws Exception {
        return connectionManager.getConnection(packageName, device);
    }

    /**
     * Disconnects a package/device: stops CDP watcher, SSE listener, and releases CDP capture.
     */
    public void disconnect(String packageName, String device) {
        stopCdpWatcher(packageName, device);
    }

    // ========================================================================
    // Device/app discovery
    // ========================================================================

    public List<Device> listDevices() throws Exception {
        return connectionManager.listDevices();
    }

    public List<SidekickSocket> listApps(String device) throws Exception {
        return connectionManager.findSidekickSockets(device);
    }

    // ========================================================================
    // App build/install/launch
    // ========================================================================

    /**
     * Builds, installs, and launches an Android app with dta-sidekick auto-injected.
     * Wraps {@link AppRunner} so that all processes (MCP, plugin, CLI) drive
     * build/install/launch through the same shared daemon instead of holding
     * their own AppRunner instances.
     *
     * @param projectPath absolute project root containing {@code ./gradlew}
     * @param device device serial (nullable — uses default device if null)
     * @param variant Gradle build variant (must be a debug variant)
     * @param module Gradle module path (default {@code :app})
     * @param activity activity to launch (nullable — uses aapt2 launchable-activity if null)
     */
    public AppRunner.RunResult runApp(String projectPath, String device, String variant,
                                      String module, String activity) {
        AppRunner runner = new AppRunner();
        AppRunner.RunRequest req = new AppRunner.RunRequest(
            projectPath, device, variant, module, activity);
        return runner.run(req, (stage, message) -> log.debug("[{}] {}", stage, message));
    }

    // ========================================================================
    // Layout tree with WebView/Custom Tab enrichment
    // ========================================================================

    /**
     * Fetches the layout tree from sidekick. When filters are provided, returns
     * a flattened matches array. When unfiltered, enriches WebView and Custom Tab
     * nodes with web accessibility trees.
     *
     * @return the layout tree as a Jackson {@link JsonNode}
     */
    public JsonNode getLayoutTree(String packageName, String device,
                                  String text, String type, String resourceId, String viewId) throws Exception {
        ConnectionInfo conn = getConnectionWithCdp(packageName, device);
        Result<String> result = conn.client().getLayoutTree(text, type, resourceId, viewId);

        if (result instanceof Success<String> success) {
            JsonNode tree = mapper.readTree(success.value());
            boolean hasFilters = text != null || type != null || resourceId != null || viewId != null;

            if (hasFilters) {
                return adaptFilteredResponse(tree);
            }

            JsonNode adapted = adaptUnfilteredResponse(tree);

            // Fallback to uiautomator when sidekick returned an empty tree.
            // Triggers only on the unfiltered path; filter queries that match
            // nothing are legitimate "no matches", not empty-tree cases. Common
            // case: the host app is backgrounded because the user is looking at
            // Chrome (Custom Tab or Intent.ACTION_VIEW) — uiautomator gives us
            // the system surface, then enrichment grafts in the page content.
            if (isEmptyTree(adapted)) {
                JsonNode fallback = UiAutomatorLayoutFallback.convert(
                    device, SidekickConnectionManager.getAdbPath(), packageName, mapper);
                if (fallback != null) {
                    log.info("Layout tree empty for {} — using uiautomator fallback", packageName);
                    enrichWebViewNodes(fallback, packageName, device);
                    return fallback;
                }
            }

            enrichWebViewNodes(adapted, packageName, device);
            return adapted;
        }
        String desc = result instanceof Failure<?> f ? f.description() : "Unknown error";
        throw new RuntimeException("Failed to get layout tree: " + desc);
    }

    /**
     * Fetches layout properties for a specific view.
     *
     * @return raw JSON string from sidekick
     */
    public String getLayoutProperties(String packageName, String device, String viewId) throws Exception {
        ConnectionInfo conn = getConnectionWithCdp(packageName, device);
        Result<String> result = conn.client().getLayoutProperties(viewId);
        if (result instanceof Success<String> success) {
            return success.value();
        }
        String desc = result instanceof Failure<?> f ? f.description() : "Unknown error";
        throw new RuntimeException("Failed to get layout properties: " + desc);
    }

    // ========================================================================
    // Runtime Data Inspection
    // ========================================================================

    public String navigationBackstack(String packageName, String device) throws Exception {
        return unwrap(getConnection(packageName, device).client().navigationBackstack(), "Failed");
    }
    public String navigationGraph(String packageName, String device) throws Exception {
        return unwrap(getConnection(packageName, device).client().navigationGraph(), "Failed");
    }
    public String lifecycle(String packageName, String device) throws Exception {
        return unwrap(getConnection(packageName, device).client().lifecycle(), "Failed");
    }
    public String memory(String packageName, String device) throws Exception {
        return unwrap(getConnection(packageName, device).client().memory(), "Failed");
    }
    public String threads(String packageName, String device, boolean stackTraces) throws Exception {
        return unwrap(getConnection(packageName, device).client().threads(stackTraces), "Failed");
    }
    public String viewModels(String packageName, String device) throws Exception {
        return unwrap(getConnection(packageName, device).client().viewModels(), "Failed");
    }
    public String viewModelSavedState(String packageName, String device, String viewModelId) throws Exception {
        return unwrap(getConnection(packageName, device).client().viewModelSavedState(viewModelId), "Failed");
    }

    public String listFiles(String packageName, String device, String path) throws Exception {
        ConnectionInfo conn = getConnection(packageName, device);
        return unwrap(conn.client().listFiles(path), "Failed to list files");
    }

    public String authenticate(String packageName, String device) throws Exception {
        ConnectionInfo conn = getConnection(packageName, device);
        return unwrap(conn.client().authenticate(), "Authentication failed");
    }

    public String listDatabases(String packageName, String device) throws Exception {
        ConnectionInfo conn = getConnection(packageName, device);
        return unwrap(conn.client().listDatabases(), "Failed to list databases");
    }

    public String databaseSchema(String packageName, String device, String dbName) throws Exception {
        ConnectionInfo conn = getConnection(packageName, device);
        return unwrap(conn.client().databaseSchema(dbName), "Failed to get schema");
    }

    public String databaseQuery(String packageName, String device, String dbName, String body) throws Exception {
        ConnectionInfo conn = getConnection(packageName, device);
        return unwrap(conn.client().databaseQuery(dbName, body), "Query failed");
    }

    public String listSharedPrefs(String packageName, String device) throws Exception {
        ConnectionInfo conn = getConnection(packageName, device);
        return unwrap(conn.client().listSharedPrefs(), "Failed to list prefs");
    }

    public String readSharedPrefs(String packageName, String device, String name) throws Exception {
        ConnectionInfo conn = getConnection(packageName, device);
        return unwrap(conn.client().readSharedPrefs(name), "Failed to read prefs");
    }

    public String writeSharedPrefs(String packageName, String device, String name, String body) throws Exception {
        ConnectionInfo conn = getConnection(packageName, device);
        return unwrap(conn.client().writeSharedPrefs(name, body), "Failed to write prefs");
    }

    // ========================================================================
    // Network requests
    // ========================================================================

    public String getNetworkRequests(String packageName, String device) throws Exception {
        ConnectionInfo conn = getConnectionWithCdp(packageName, device);
        return unwrap(conn.client().getNetworkRequests(), "Failed to get network requests");
    }

    public String getNetworkRequest(String packageName, String device, String requestId) throws Exception {
        ConnectionInfo conn = getConnectionWithCdp(packageName, device);
        return unwrap(conn.client().getNetworkRequest(requestId), "Failed to get network request");
    }

    public String getNetworkRequestBody(String packageName, String device, String requestId) throws Exception {
        ConnectionInfo conn = getConnectionWithCdp(packageName, device);
        return unwrap(conn.client().getNetworkRequestBody(requestId), "Failed to get network request body");
    }

    public String getNetworkStats(String packageName, String device) throws Exception {
        ConnectionInfo conn = getConnectionWithCdp(packageName, device);
        return unwrap(conn.client().getNetworkStats(), "Failed to get network stats");
    }

    public String clearNetworkRequests(String packageName, String device) throws Exception {
        ConnectionInfo conn = getConnectionWithCdp(packageName, device);
        return unwrap(conn.client().clearNetworkRequests(), "Failed to clear network requests");
    }

    // ========================================================================
    // WebSocket connections
    // ========================================================================

    public String getWebSocketConnections(String packageName, String device) throws Exception {
        ConnectionInfo conn = getConnectionWithCdp(packageName, device);
        return unwrap(conn.client().getWebSocketConnections(), "Failed to get websocket connections");
    }

    public String getWebSocketConnection(String packageName, String device, String connectionId) throws Exception {
        ConnectionInfo conn = getConnectionWithCdp(packageName, device);
        return unwrap(conn.client().getWebSocketConnection(connectionId), "Failed to get websocket connection");
    }

    public String clearWebSocketConnections(String packageName, String device) throws Exception {
        ConnectionInfo conn = getConnectionWithCdp(packageName, device);
        return unwrap(conn.client().clearWebSocketConnections(), "Failed to clear websocket connections");
    }

    // ========================================================================
    // Screenshot
    // ========================================================================

    public Result<byte[]> getScreenshot(String packageName, String device) throws Exception {
        ConnectionInfo conn = getConnectionWithCdp(packageName, device);
        return conn.client().getScreenshot();
    }

    // ========================================================================
    // Element selection
    // ========================================================================

    public String getSelectedElements(String packageName, String device) throws Exception {
        ConnectionInfo conn = getConnectionWithCdp(packageName, device);
        return unwrap(conn.client().getSelectedElements(), "Failed to get selected elements");
    }

    public String addSelectedElement(String packageName, String device, String elementJson) throws Exception {
        ConnectionInfo conn = getConnectionWithCdp(packageName, device);
        return unwrap(conn.client().addSelectedElement(elementJson), "Failed to add element to selection");
    }

    public String removeSelectedElement(String packageName, String device, String elementJson) throws Exception {
        ConnectionInfo conn = getConnectionWithCdp(packageName, device);
        return unwrap(conn.client().removeSelectedElement(elementJson), "Failed to remove element from selection");
    }

    public String clearSelectedElements(String packageName, String device) throws Exception {
        ConnectionInfo conn = getConnectionWithCdp(packageName, device);
        return unwrap(conn.client().clearSelectedElements(), "Failed to clear element selection");
    }

    /**
     * Hit-tests the layout tree at (x,y) and adds the deepest element to the selection.
     *
     * @return the selection result as a raw JSON string
     */
    public String selectElementAt(String packageName, String device, int x, int y) throws Exception {
        ConnectionInfo conn = getConnectionWithCdp(packageName, device);

        Result<String> treeResult = conn.client().getLayoutTree(null, null, null, null);
        if (!(treeResult instanceof Success<String> treeSuccess)) {
            throw new RuntimeException("Failed to fetch layout tree for hit-testing");
        }
        JsonNode tree = mapper.readTree(treeSuccess.value());

        JsonNode root = tree.has("root") ? tree.get("root") : null;
        if (root == null && tree.has("windows")) {
            JsonNode windows = tree.get("windows");
            if (windows.isArray() && !windows.isEmpty()) {
                root = windows.get(0).get("tree");
            }
        }
        if (root == null) {
            throw new RuntimeException("No layout tree available");
        }

        JsonNode hit = hitTest(x, y, root);
        if (hit == null) {
            throw new RuntimeException("No element found at (" + x + ", " + y + ")");
        }

        String elementJson = mapper.writeValueAsString(hit);
        return unwrap(conn.client().addSelectedElement(elementJson), "Failed to add element to selection");
    }

    // ========================================================================
    // Network selection
    // ========================================================================

    public String getSelectedNetworkRequests(String packageName, String device) throws Exception {
        ConnectionInfo conn = getConnectionWithCdp(packageName, device);
        return unwrap(conn.client().getSelectedNetworkRequests(), "Failed to get selected network requests");
    }

    public String addSelectedNetworkRequest(String packageName, String device, String requestJson) throws Exception {
        ConnectionInfo conn = getConnectionWithCdp(packageName, device);
        return unwrap(conn.client().addSelectedNetworkRequest(requestJson), "Failed to add network request to selection");
    }

    public String removeSelectedNetworkRequest(String packageName, String device, String requestJson) throws Exception {
        ConnectionInfo conn = getConnectionWithCdp(packageName, device);
        return unwrap(conn.client().removeSelectedNetworkRequest(requestJson), "Failed to remove network request from selection");
    }

    public String clearSelectedNetworkRequests(String packageName, String device) throws Exception {
        ConnectionInfo conn = getConnectionWithCdp(packageName, device);
        return unwrap(conn.client().clearSelectedNetworkRequests(), "Failed to clear network request selection");
    }

    // ========================================================================
    // WebSocket message selection
    // ========================================================================

    public String getSelectedWebSocketMessages(String packageName, String device) throws Exception {
        ConnectionInfo conn = getConnectionWithCdp(packageName, device);
        return unwrap(conn.client().getSelectedWebSocketMessages(), "Failed to get selected websocket messages");
    }

    public String addSelectedWebSocketMessage(String packageName, String device, String selectionJson) throws Exception {
        ConnectionInfo conn = getConnectionWithCdp(packageName, device);
        return unwrap(conn.client().addSelectedWebSocketMessage(selectionJson), "Failed to add websocket message to selection");
    }

    public String removeSelectedWebSocketMessage(String packageName, String device, String selectionJson) throws Exception {
        ConnectionInfo conn = getConnectionWithCdp(packageName, device);
        return unwrap(conn.client().removeSelectedWebSocketMessage(selectionJson), "Failed to remove websocket message from selection");
    }

    public String clearSelectedWebSocketMessages(String packageName, String device) throws Exception {
        ConnectionInfo conn = getConnectionWithCdp(packageName, device);
        return unwrap(conn.client().clearSelectedWebSocketMessages(), "Failed to clear websocket message selection");
    }

    // ========================================================================
    // Mock endpoints
    // ========================================================================

    public String getMockRules(String packageName, String device) throws Exception {
        ConnectionInfo conn = getConnectionWithCdp(packageName, device);
        return unwrap(conn.client().getMockRules(), "Failed to get mock rules");
    }

    public String getMockRule(String packageName, String device, String ruleId) throws Exception {
        ConnectionInfo conn = getConnectionWithCdp(packageName, device);
        return unwrap(conn.client().getMockRule(ruleId), "Failed to get mock rule");
    }

    public String createMockRule(String packageName, String device, String ruleJson) throws Exception {
        ConnectionInfo conn = getConnectionWithCdp(packageName, device);
        return unwrap(conn.client().createMockRule(ruleJson), "Failed to create mock rule");
    }

    public String updateMockRule(String packageName, String device, String ruleId, String updateJson) throws Exception {
        ConnectionInfo conn = getConnectionWithCdp(packageName, device);
        return unwrap(conn.client().updateMockRule(ruleId, updateJson), "Failed to update mock rule");
    }

    public String deleteMockRule(String packageName, String device, String ruleId) throws Exception {
        ConnectionInfo conn = getConnectionWithCdp(packageName, device);
        return unwrap(conn.client().deleteMockRule(ruleId), "Failed to delete mock rule");
    }

    public String getMockConfig(String packageName, String device) throws Exception {
        ConnectionInfo conn = getConnectionWithCdp(packageName, device);
        return unwrap(conn.client().getMockConfig(), "Failed to get mock config");
    }

    public String updateMockConfig(String packageName, String device, String configJson) throws Exception {
        ConnectionInfo conn = getConnectionWithCdp(packageName, device);
        return unwrap(conn.client().updateMockConfig(configJson), "Failed to update mock config");
    }

    public String createMockFromRequest(String packageName, String device, String requestId) throws Exception {
        ConnectionInfo conn = getConnectionWithCdp(packageName, device);
        return unwrap(conn.client().createMockFromRequest(requestId), "Failed to create mock from request");
    }

    public String createMockFromMessage(String packageName, String device, String messageId) throws Exception {
        ConnectionInfo conn = getConnectionWithCdp(packageName, device);
        return unwrap(conn.client().createMockFromMessage(messageId), "Failed to create mock from message");
    }

    // ========================================================================
    // CDP watcher management
    // ========================================================================

    /**
     * Explicitly starts the CDP watcher for a package/device.
     *
     * @return true if the watcher is running after this call
     */
    public boolean startCdpWatcher(String packageName, String device) throws Exception {
        ConnectionInfo conn = connectionManager.getConnection(packageName, device);
        ensureCdpWatcher(packageName, device, conn);
        String listenerKey = makeListenerKey(packageName, device);
        return sseListeners.containsKey(listenerKey);
    }

    /**
     * Stops the CDP watcher, SSE listener, and releases CDP capture for a package/device.
     *
     * @return a status map with "status" and "message" keys
     */
    public Map<String, Object> stopCdpWatcher(String packageName, String device) {
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
            return Map.of("status", "stopped", "message", "CDP capture disarmed");
        } else {
            return Map.of("status", "not_running", "message", "No CDP capture was active");
        }
    }

    /**
     * Returns the CDP watcher status for a package/device.
     */
    public Map<String, Object> getCdpWatcherStatus(String packageName, String device) {
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

        return status;
    }

    // ========================================================================
    // CDP response body
    // ========================================================================

    /**
     * Fetches a CDP response body for a Custom Tab network request.
     *
     * @return a map with "body" and "base64Encoded" keys, or "error" on failure
     */
    public Map<String, Object> getCdpResponseBody(String packageName, String device, String requestId) {
        try {
            ChromeDevToolsClient client = CdpWatcherManager.getInstance().getCdpClient(packageName,
                    device != null ? device : "emulator-5554");
            if (client == null || !client.isConnected()) {
                return Map.of("error", "CDP not connected. Open a Custom Tab first.");
            }
            var body = client.getResponseBody(requestId)
                    .orTimeout(5, TimeUnit.SECONDS)
                    .join();
            if (body != null) {
                return Map.of(
                        "body", body.body() != null ? body.body() : "",
                        "base64Encoded", body.base64Encoded());
            }
            return Map.of("error", "No body returned");
        } catch (Exception e) {
            String msg = e.getMessage();
            if (e.getCause() != null) msg = e.getCause().getMessage();
            return Map.of("error", "Failed to fetch body: " + msg);
        }
    }

    // ========================================================================
    // Connection status
    // ========================================================================

    /**
     * Checks connection health and returns status information.
     *
     * @param version the tool version to check compatibility against
     * @return a map with connection status, version info, and optional warnings
     */
    public Map<String, Object> getConnectionStatus(String packageName, String device, String version) {
        Map<String, Object> result = new java.util.HashMap<>();
        try {
            ConnectionInfo conn = connectionManager.getConnection(packageName, device);
            var healthResult = conn.client().checkHealthTyped();

            result.put("connected", true);
            result.put("toolVersion", version);

            if (healthResult instanceof Success<io.yamsergey.dta.daemon.sidekick.HealthResponse> success) {
                var health = success.value();
                result.put("sidekickVersion", health.version());
                result.put("sidekickName", health.name());

                if (!SidekickConnectionManager.isVersionCompatible(version, health.version())) {
                    result.put("warning", "Version mismatch: Inspector v" + version + ", Sidekick v" + health.version());
                }
            }
        } catch (Exception e) {
            result.put("connected", false);
            result.put("toolVersion", version);
            result.put("error", e.getMessage());
        }
        return result;
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    /**
     * Shuts down all SSE listeners and CDP watchers. Call this on application shutdown.
     */
    public void shutdown() {
        log.info("Shutting down DtaOrchestrator — cleaning up CDP watchers and forwards");
        sseListeners.forEach((key, listener) -> {
            try {
                listener.stop();
            } catch (Exception e) {
                log.debug("Error stopping SSE listener {}: {}", key, e.getMessage());
            }
            try {
                String[] parts = key.split(":", 2);
                String dev = "default".equals(parts[0]) ? null : parts[0];
                String pkg = parts[1];
                ConnectionInfo conn = connectionManager.getConnection(pkg, dev);
                conn.client().releaseCdpCapture();
            } catch (Exception e) {
                log.debug("Error releasing CDP capture for {}: {}", key, e.getMessage());
            }
        });
        sseListeners.clear();
        CdpWatcherManager.getInstance().stopAll();
        // Release every sidekick port forward this daemon created. Crash/kill-9
        // path is handled by the startup sweep in DtaDaemon.start().
        connectionManager.shutdown();
    }

    // ========================================================================
    // Internal: CDP watcher setup
    // ========================================================================

    /**
     * Idempotent, best-effort CDP watcher setup. If already running for this key — returns immediately.
     * On failure, logs a warning and continues — never breaks the calling operation.
     *
     * <p>Synchronized to prevent concurrent requests from racing to create/destroy watchers.
     * Multiple requests arrive simultaneously on first connection and each calls
     * getConnectionWithCdp -> ensureCdpWatcher. Without synchronization, losing threads
     * call stopWatcher() which kills the shared watcher the winning thread created.</p>
     */
    private synchronized void ensureCdpWatcher(String packageName, String device, ConnectionInfo conn) {
        String listenerKey = makeListenerKey(packageName, device);

        // Check if an SSE listener already exists for this key
        SidekickSseListener existing = sseListeners.get(listenerKey);
        if (existing != null) {
            if (existing.getPort() == conn.port() && existing.isRunning()) {
                // Same port and listener thread is alive — keep it.
                return;
            }
            // Either port changed (app restarted), or the SSE listener thread
            // died silently (connection dropped, sidekick restarted, etc.).
            // Tear down the stale listener + watcher and re-create below.
            if (existing.getPort() != conn.port()) {
                log.info("Sidekick port changed for {} ({} → {}), re-arming CDP capture",
                    listenerKey, existing.getPort(), conn.port());
            } else {
                log.info("SSE listener for {} is no longer running, re-arming CDP capture", listenerKey);
            }
            existing.stop();
            sseListeners.remove(listenerKey);
            CdpWatcherManager.getInstance().stopWatcher(packageName, device);
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
                    public void onChromeWillLaunch(String eventId, String url, long timestamp,
                                                   String pkg, String targetBrowserPackage) {
                        log.info("SSE: Chrome will launch: {} (event={}, target={})",
                                url, eventId, targetBrowserPackage);
                        ChromeBrowserCdpManager.getInstance().onChromeWillLaunch(
                                device, conn.client(), eventId, packageName, url, timestamp);
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
    // Internal: Layout tree adaptation and enrichment
    // ========================================================================

    /**
     * Considers a layout-tree response empty when there is no {@code root} or
     * the root has no children. Used to decide whether to engage the
     * uiautomator fallback. We only check the root's direct children — a tree
     * with deeply-nested empty wrappers is a separate edge case and not worth
     * the extra walk.
     */
    private static boolean isEmptyTree(JsonNode adapted) {
        JsonNode root = adapted.get("root");
        if (root == null || root.isNull()) return true;
        JsonNode children = root.get("children");
        return children == null || !children.isArray() || children.isEmpty();
    }

    /**
     * Adapts unfiltered layout_tree response: flattens {@code windows} into a single {@code root}.
     */
    JsonNode adaptUnfilteredResponse(JsonNode tree) {
        JsonNode windows = tree.get("windows");
        if (windows == null || !windows.isArray() || windows.isEmpty()) {
            return tree;
        }

        ObjectNode result = mapper.createObjectNode();
        tree.propertyNames().forEach(field -> {
            if (!"windows".equals(field)) {
                result.set(field, tree.get(field));
            }
        });

        if (windows.size() == 1) {
            JsonNode windowTree = windows.get(0).get("tree");
            result.set("root", windowTree != null ? windowTree : mapper.createObjectNode());
        } else {
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
     * a top-level {@code matches} array.
     */
    JsonNode adaptFilteredResponse(JsonNode tree) {
        ObjectNode result = mapper.createObjectNode();
        tree.propertyNames().forEach(field -> {
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
                        ObjectNode node = (ObjectNode) match.deepCopy();
                        node.remove("parentChain");
                        entry.set("node", node);

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
        try {
            enrichCustomTabNode(adapted, packageName, device);
        } catch (Exception e) {
            log.debug("Custom Tab enrichment failed (non-fatal): {}", e.getMessage());
        }

        // Stage 7: Enrich with Chrome-via-Intent if a tab was captured for this app
        try {
            enrichChromeIntentNode(adapted, packageName, device);
        } catch (Exception e) {
            log.debug("Chrome-via-Intent enrichment failed (non-fatal): {}", e.getMessage());
        }
    }

    /**
     * If the host app fired an {@code Intent.ACTION_VIEW} that landed in
     * standalone Chrome and the daemon captured a CDP session for it,
     * fetches the page's accessibility tree and injects a synthetic
     * {@code ChromeBrowser} node into the layout tree. The session lives
     * on the browser-level WebSocket addressed by sessionId (flat protocol),
     * so we route every CDP command through the session-aware inspector
     * variant.
     */
    private void enrichChromeIntentNode(JsonNode adapted, String packageName, String device) {
        try {
            ChromeBrowserCdpManager.CapturedSession session =
                ChromeBrowserCdpManager.getInstance().getCapturedSessionForPackage(device, packageName);
            if (session == null) return;

            CdpAccessibilityInspector.AccessibilityTreeResult treeResult =
                CdpAccessibilityInspector.fetchAccessibilityTreeWithUrl(
                    session.browserClient(), session.sessionId());

            if (treeResult.nodes().isEmpty()) return;

            Map<String, Object> chromeNode = new LinkedHashMap<>();
            chromeNode.put("nodeType", "web");
            chromeNode.put("className", "ChromeBrowser");
            String url = treeResult.url() != null ? treeResult.url() : session.url();
            if (url != null) {
                chromeNode.put("webViewUrl", url);
            }

            if (treeResult.viewportWidth() > 0) {
                double scale = 0;
                JsonNode screen = adapted.get("screen");
                if (screen != null) {
                    double screenWidth = screen.path("width").asDouble(0);
                    if (screenWidth > 0) {
                        scale = screenWidth / treeResult.viewportWidth();
                    }
                }
                if (scale == 0 && treeResult.devicePixelRatio() > 0) {
                    scale = treeResult.devicePixelRatio();
                }
                if (scale > 0) {
                    double offsetY = treeResult.screenOffsetY() + getStatusBarHeight(device);
                    transformCssBoundsToScreen(treeResult.nodes(), 0, offsetY, scale);
                }
            }

            chromeNode.put("children", treeResult.nodes());

            // The host app's compose hierarchy doesn't reflect Chrome's content,
            // so we inject under (or replace) the root the same way Custom Tabs do.
            JsonNode root = adapted.get("root");
            if (root instanceof ObjectNode rootObj) {
                ArrayNode children = (ArrayNode) root.get("children");
                if (children == null) {
                    children = mapper.createArrayNode();
                    rootObj.set("children", children);
                }
                children.add(mapper.valueToTree(chromeNode));
            } else if (adapted instanceof ObjectNode adaptedObj) {
                ObjectNode syntheticRoot = mapper.createObjectNode();
                syntheticRoot.put("nodeType", "view");
                syntheticRoot.put("className", "ChromeBrowserWindow");
                ArrayNode children = syntheticRoot.putArray("children");
                children.add(mapper.valueToTree(chromeNode));
                adaptedObj.set("root", syntheticRoot);
            }

            log.debug("Injected Chrome-via-Intent node (url={}, {} web nodes)",
                url, treeResult.nodes().size());
        } catch (Exception e) {
            log.debug("Chrome-via-Intent enrichment failed (non-fatal): {}", e.getMessage());
        }
    }

    private void collectWebViewNodes(JsonNode node, List<WebViewInfo> results) {
        if (node == null || !node.isObject()) return;

        if (node.path("isWebView").asBoolean(false)) {
            String url = node.path("webViewUrl").asText(null);
            if (node instanceof ObjectNode objNode) {
                results.add(new WebViewInfo(objNode, url));
            }
        }

        JsonNode children = node.get("children");
        if (children != null && children.isArray()) {
            for (JsonNode child : children) {
                collectWebViewNodes(child, results);
            }
        }
    }

    private void matchAndInjectWebViewTrees(
            List<WebViewInfo> webViewNodes,
            List<WebViewCdpManager.WebViewTreeResult> trees) {

        for (WebViewInfo webViewInfo : webViewNodes) {
            WebViewCdpManager.WebViewTreeResult bestMatch = null;

            if (webViewInfo.url() != null) {
                for (WebViewCdpManager.WebViewTreeResult tree : trees) {
                    if (urlsMatch(webViewInfo.url(), tree.url())) {
                        bestMatch = tree;
                        break;
                    }
                }
            }

            if (bestMatch == null && webViewNodes.size() == 1 && trees.size() == 1) {
                bestMatch = trees.get(0);
            }

            if (bestMatch != null && !bestMatch.children().isEmpty()) {
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

    @SuppressWarnings("unchecked")
    static void transformCssBoundsToScreen(
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
     * If a Chrome Custom Tab is active, fetches its accessibility tree and
     * injects a synthetic node into the layout tree.
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

            Map<String, Object> customTabNode = new LinkedHashMap<>();
            customTabNode.put("nodeType", "web");
            customTabNode.put("className", "ChromeCustomTab");
            String url = treeResult.url() != null ? treeResult.url() : tabUrl;
            if (url != null) {
                customTabNode.put("webViewUrl", url);
            }

            if (treeResult.viewportWidth() > 0) {
                double scale = 0;
                JsonNode screen = adapted.get("screen");
                if (screen != null) {
                    double screenWidth = screen.path("width").asDouble(0);
                    if (screenWidth > 0) {
                        scale = screenWidth / treeResult.viewportWidth();
                    }
                }
                if (scale == 0 && treeResult.devicePixelRatio() > 0) {
                    scale = treeResult.devicePixelRatio();
                }
                if (scale > 0) {
                    double offsetY = treeResult.screenOffsetY() + getStatusBarHeight(device);
                    transformCssBoundsToScreen(treeResult.nodes(), 0, offsetY, scale);
                }
            }

            customTabNode.put("children", treeResult.nodes());

            JsonNode root = adapted.get("root");
            if (root instanceof ObjectNode rootObj) {
                ArrayNode children = (ArrayNode) root.get("children");
                if (children == null) {
                    children = mapper.createArrayNode();
                    rootObj.set("children", children);
                }
                children.add(mapper.valueToTree(customTabNode));
            } else if (adapted instanceof ObjectNode adaptedObj) {
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
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return 0;
            }

            Pattern pattern = Pattern.compile(
                "type=statusBars.*insetsSize=Insets\\{[^}]*top=(\\d+)");
            Matcher matcher = pattern.matcher(output);
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

    // ========================================================================
    // Internal: Hit-testing
    // ========================================================================

    private JsonNode hitTest(int x, int y, JsonNode node) {
        JsonNode bounds = node.get("bounds");
        boolean hasArea = false;
        if (bounds != null) {
            int w = bounds.has("width") && bounds.get("width").asInt() > 0
                    ? bounds.get("width").asInt()
                    : bounds.path("right").asInt() - bounds.path("left").asInt();
            int h = bounds.has("height") && bounds.get("height").asInt() > 0
                    ? bounds.get("height").asInt()
                    : bounds.path("bottom").asInt() - bounds.path("top").asInt();
            hasArea = w > 0 && h > 0;
            if (hasArea) {
                if (x < bounds.path("left").asInt() || x > bounds.path("right").asInt()
                        || y < bounds.path("top").asInt() || y > bounds.path("bottom").asInt()) {
                    return null;
                }
            }
        }
        JsonNode children = node.get("children");
        if (children != null && children.isArray()) {
            for (int i = children.size() - 1; i >= 0; i--) {
                JsonNode found = hitTest(x, y, children.get(i));
                if (found != null) return found;
            }
        }
        return hasArea ? node : null;
    }

    // ========================================================================
    // Internal: Helpers
    // ========================================================================

    private String makeListenerKey(String packageName, String device) {
        return (device != null ? device : "default") + ":" + packageName;
    }

    static boolean urlsMatch(String url1, String url2) {
        if (url1 == null || url2 == null) return false;
        if (url1.equals(url2)) return true;

        String norm1 = url1.endsWith("/") ? url1.substring(0, url1.length() - 1) : url1;
        String norm2 = url2.endsWith("/") ? url2.substring(0, url2.length() - 1) : url2;
        if (norm1.equals(norm2)) return true;

        return norm1.startsWith(norm2) || norm2.startsWith(norm1);
    }

    /**
     * Unwraps a {@link Result} — returns the value on success, throws on failure.
     */
    private static String unwrap(Result<String> result, String errorPrefix) {
        if (result instanceof Success<String> success) {
            return success.value();
        }
        String desc = result instanceof Failure<?> f ? f.description() : "Unknown error";
        throw new RuntimeException(errorPrefix + ": " + desc);
    }
}
