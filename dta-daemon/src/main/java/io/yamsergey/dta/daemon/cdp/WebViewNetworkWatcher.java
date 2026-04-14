package io.yamsergey.dta.daemon.cdp;

import io.yamsergey.dta.daemon.sidekick.SidekickClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent CDP connections to in-app WebViews for network capture and
 * accessibility tree inspection.
 *
 * <p>Unlike {@link WebViewCdpManager} (short-lived, per-request), this watcher
 * maintains connections across polling cycles. On each {@link #refresh} call it:</p>
 * <ol>
 *   <li>Discovers current WebView debug sockets via ADB</li>
 *   <li>Connects to new sockets — enables {@code Network.enable} and sets up
 *       event listeners that post captured requests to sidekick</li>
 *   <li>Removes connections to sockets that no longer exist</li>
 *   <li>Returns accessibility trees from all active connections (for layout
 *       enrichment, replacing the throwaway {@link WebViewCdpManager})</li>
 * </ol>
 *
 * <p>Network events are posted to sidekick via {@link SidekickClient#recordTransaction},
 * appearing alongside OkHttp and CustomTab requests with {@code source: "WebView"}.</p>
 */
public class WebViewNetworkWatcher {

    private static final Logger log = LoggerFactory.getLogger(WebViewNetworkWatcher.class);

    private final String deviceSerial;
    private final Map<String, WatchedWebView> activeConnections = new ConcurrentHashMap<>();

    public WebViewNetworkWatcher(String deviceSerial) {
        this.deviceSerial = deviceSerial;
    }

    /**
     * Result of fetching a WebView's accessibility tree (same shape as
     * {@link WebViewCdpManager.WebViewTreeResult}).
     */
    public record WebViewTreeResult(
        String url,
        List<Map<String, Object>> children,
        double viewportWidth,
        double viewportHeight
    ) {}

    /**
     * Refreshes the set of monitored WebViews, enables network capture on
     * new ones, and returns accessibility trees for layout enrichment.
     *
     * @param sidekickClient the client for posting captured network events
     *                       (nullable — if null, network capture is skipped)
     * @return accessibility trees from all active WebViews
     */
    public List<WebViewTreeResult> refresh(SidekickClient sidekickClient) {
        List<WebViewTreeResult> trees = new ArrayList<>();

        try {
            // Discover current WebView debug sockets
            List<String> currentSockets = ChromeSocketDiscovery.findWebViewSockets(deviceSerial);
            Set<String> currentSet = new HashSet<>(currentSockets);

            // Remove dead connections
            var deadKeys = new ArrayList<String>();
            for (String socketName : activeConnections.keySet()) {
                if (!currentSet.contains(socketName)) {
                    deadKeys.add(socketName);
                }
            }
            for (String key : deadKeys) {
                WatchedWebView removed = activeConnections.remove(key);
                if (removed != null) {
                    removed.close();
                    log.info("WebView closed: {}", key);
                }
            }

            // Connect to new sockets
            for (String socketName : currentSockets) {
                if (!activeConnections.containsKey(socketName)) {
                    try {
                        WatchedWebView wv = connectToWebView(socketName, sidekickClient);
                        if (wv != null) {
                            activeConnections.put(socketName, wv);
                            log.info("WebView network capture enabled: {} ({} targets)",
                                socketName, wv.clients.size());
                        }
                    } catch (Exception e) {
                        log.debug("Failed to connect to WebView {}: {}", socketName, e.getMessage());
                    }
                }
            }

            // Fetch accessibility trees from all active connections
            for (var entry : activeConnections.entrySet()) {
                WatchedWebView wv = entry.getValue();
                for (var tc : wv.clients) {
                    try {
                        CdpAccessibilityInspector.AccessibilityTreeResult treeResult =
                            CdpAccessibilityInspector.fetchAccessibilityTreeWithUrl(tc.client);
                        if (!treeResult.nodes().isEmpty()) {
                            String url = treeResult.url() != null ? treeResult.url() : tc.url;
                            trees.add(new WebViewTreeResult(
                                url, treeResult.nodes(),
                                treeResult.viewportWidth(), treeResult.viewportHeight()));
                        }
                    } catch (Exception e) {
                        log.debug("Failed to fetch tree from WebView target: {}", e.getMessage());
                    }
                }
            }

        } catch (Exception e) {
            log.debug("WebView refresh failed: {}", e.getMessage());
        }

        return trees;
    }

    /**
     * Shuts down all connections.
     */
    public void shutdown() {
        for (WatchedWebView wv : activeConnections.values()) {
            wv.close();
        }
        activeConnections.clear();
    }

    public int getActiveCount() {
        return activeConnections.size();
    }

    // ========================================================================
    // Internal
    // ========================================================================

    private WatchedWebView connectToWebView(String socketName, SidekickClient sidekickClient)
            throws Exception {

        int localPort = ChromeSocketDiscovery.setupPortForwardAuto(deviceSerial, socketName);
        if (localPort < 0) return null;

        ChromeDevToolsClient listClient = new ChromeDevToolsClient("localhost", localPort);
        List<CdpTarget> targets = listClient.listTargets();
        listClient.close();

        List<TargetClient> targetClients = new ArrayList<>();

        for (CdpTarget target : targets) {
            if (!target.isPage()) continue;
            try {
                ChromeDevToolsClient client = new ChromeDevToolsClient("localhost", localPort);
                client.attachToTarget(target);

                // Enable network capture — events get correlated and posted to sidekick
                if (sidekickClient != null) {
                    CustomTabsNetworkMonitor monitor = new CustomTabsNetworkMonitor(
                        deviceSerial, event -> {});
                    monitor.setSidekickClient(sidekickClient);
                    monitor.setCdpClient(client);
                    monitor.setSourceLabel("WebView");

                    client.setNetworkEventListener(cdpEvent ->
                        monitor.onCdpEvent(cdpEvent, target.url()));

                    client.enableNetwork().join();
                    log.debug("Network.enable on WebView target: {}", target.url());
                }

                targetClients.add(new TargetClient(client, target.url()));
            } catch (Exception e) {
                log.debug("Failed to attach to WebView target {}: {}", target.url(), e.getMessage());
            }
        }

        if (targetClients.isEmpty()) {
            ChromeSocketDiscovery.removePortForward(deviceSerial, localPort);
            return null;
        }

        return new WatchedWebView(localPort, socketName, targetClients);
    }

    private record TargetClient(ChromeDevToolsClient client, String url) {}

    private static class WatchedWebView {
        final int localPort;
        final String socketName;
        final List<TargetClient> clients;

        WatchedWebView(int localPort, String socketName, List<TargetClient> clients) {
            this.localPort = localPort;
            this.socketName = socketName;
            this.clients = clients;
        }

        void close() {
            for (TargetClient tc : clients) {
                try { tc.client.close(); } catch (Exception ignored) {}
            }
            clients.clear();
            ChromeSocketDiscovery.removePortForward(null, localPort);
        }
    }
}
