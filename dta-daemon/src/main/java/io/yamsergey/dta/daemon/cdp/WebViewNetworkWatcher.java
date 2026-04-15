package io.yamsergey.dta.daemon.cdp;

import io.yamsergey.dta.daemon.sidekick.SidekickClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Event-driven persistent CDP connections to in-app WebViews for network
 * capture and accessibility tree inspection.
 *
 * <p>Unlike the previous polling-driven design, this watcher only discovers
 * sockets in response to sidekick events:</p>
 * <ol>
 *   <li>{@link #onWebViewWillLoad} — called by {@code DtaOrchestrator} when a
 *       {@code webview_will_load} SSE event arrives. Finds the debug socket
 *       for that pid (tight-polled via {@link ChromeSocketDiscovery}), attaches
 *       CDP, enables Network capture, then ACKs sidekick so {@code loadUrl}
 *       proceeds with capture armed.</li>
 *   <li>{@link #bootstrapDiscovery} — runs once on sidekick SSE (re)connect to
 *       catch WebViews that were already live before the daemon connected.</li>
 * </ol>
 *
 * <p>{@link #refresh} on the layout-tree hot path does no ADB work: it just
 * walks already-attached connections, prunes dead ones via CDP liveness, and
 * returns accessibility trees.</p>
 *
 * <p>Network events from attached targets are posted to sidekick via
 * {@link SidekickClient#recordTransaction}, appearing alongside OkHttp and
 * CustomTab traffic with {@code source: "WebView"}.</p>
 */
public class WebViewNetworkWatcher {

    private static final Logger log = LoggerFactory.getLogger(WebViewNetworkWatcher.class);
    private static final long ACK_HTTP_TIMEOUT_MS = 2_000;
    // Per-pid attach lock: serialize onWebViewWillLoad calls for the same pid
    // so a burst of loadUrl from the same process doesn't duplicate attach work.
    private static final ConcurrentHashMap<Integer, Object> PID_ATTACH_LOCKS = new ConcurrentHashMap<>();

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
     * Layout-tree hot path. Discovers new WebViews, attaches CDP + Network
     * capture to them, prunes dead entries, and returns accessibility trees.
     *
     * <p>Socket discovery goes through {@link ChromeSocketDiscovery#findWebViewSockets}
     * which is per-device TTL-cached (2s) and semaphore-gated, so a burst of
     * plugin polls collapses to at most one {@code adb shell grep} per 2s.
     * That's the current defense against adbd saturation until a proper
     * event-driven hook lands (see {@code WebViewLoadUrlHook}).</p>
     */
    public List<WebViewTreeResult> refresh(SidekickClient sidekickClient) {
        List<WebViewTreeResult> trees = new ArrayList<>();

        try {
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
                for (TargetClient tc : wv.clients) {
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
     * Handles a {@code webview_will_load} SSE event. Ensures CDP is attached
     * and Network capture enabled on {@code webview_devtools_remote_<pid>},
     * then ACKs sidekick so the hooked {@code loadUrl} can proceed with
     * capture armed.
     *
     * <p>Always ACKs, even on failure — better to let the app proceed and
     * miss one capture than hang the UI thread for the full 2s latch.</p>
     *
     * @param eventId       SSE event ID, used in the ACK URL
     * @param pid           app process id (socket name is {@code webview_devtools_remote_<pid>})
     * @param url           URL about to be loaded (for logging)
     * @param sidekickClient sidekick client for network event forwarding + ACK (carries port)
     * @param sidekickPort  HTTP port of sidekick (for the ACK call)
     */
    public void onWebViewWillLoad(String eventId, int pid, String url,
                                   SidekickClient sidekickClient, int sidekickPort) {
        Object lock = PID_ATTACH_LOCKS.computeIfAbsent(pid, k -> new Object());
        synchronized (lock) {
            String socketName = "webview_devtools_remote_" + pid;
            try {
                WatchedWebView existing = activeConnections.get(socketName);
                if (existing != null && anyConnected(existing)) {
                    log.debug("WebView pid={} already attached, ACKing immediately (url={})", pid, url);
                    ackSidekick(sidekickPort, eventId);
                    return;
                }

                // Tight-poll for the socket. It should be open by now (the app
                // hook fires AFTER setWebContentsDebuggingEnabled via WebViewDebugHook),
                // but allow ~3s for the kernel/kernel-socket setup.
                String found = null;
                long deadline = System.currentTimeMillis() + 3_000;
                while (System.currentTimeMillis() < deadline) {
                    try {
                        List<String> sockets = ChromeSocketDiscovery.findWebViewSockets(deviceSerial);
                        if (sockets.contains(socketName)) {
                            found = socketName;
                            break;
                        }
                    } catch (Exception e) {
                        log.debug("Socket discovery failed during WebView attach: {}", e.getMessage());
                    }
                    try { Thread.sleep(100); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                if (found == null) {
                    log.warn("WebView socket {} did not appear within 3s; ACKing without capture", socketName);
                    ackSidekick(sidekickPort, eventId);
                    return;
                }

                WatchedWebView attached = connectToWebView(socketName, sidekickClient);
                if (attached != null) {
                    activeConnections.put(socketName, attached);
                    log.info("WebView attached on demand: {} (pid={}, targets={})",
                        socketName, pid, attached.clients.size());
                } else {
                    log.warn("connectToWebView returned null for {}", socketName);
                }
            } catch (Exception e) {
                log.warn("WebView attach failed for pid={}: {}", pid, e.getMessage());
            } finally {
                // ACK unconditionally — the app's loadUrl must not hang.
                ackSidekick(sidekickPort, eventId);
            }
        }
    }

    /**
     * One-shot discovery used on (re)connect to catch WebViews that were
     * already live before the daemon attached. Future loads come in via SSE.
     */
    public void bootstrapDiscovery(SidekickClient sidekickClient) {
        try {
            List<String> sockets = ChromeSocketDiscovery.findWebViewSockets(deviceSerial);
            if (sockets.isEmpty()) {
                log.debug("Bootstrap sweep: no WebView sockets");
                return;
            }
            for (String socketName : sockets) {
                if (activeConnections.containsKey(socketName)) continue;
                try {
                    WatchedWebView wv = connectToWebView(socketName, sidekickClient);
                    if (wv != null) {
                        activeConnections.put(socketName, wv);
                        log.info("Bootstrap discovered {} ({} targets)", socketName, wv.clients.size());
                    }
                } catch (Exception e) {
                    log.debug("Bootstrap attach failed for {}: {}", socketName, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("Bootstrap discovery failed: {}", e.getMessage());
        }
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

    private static boolean anyConnected(WatchedWebView wv) {
        for (TargetClient tc : wv.clients) {
            if (tc.client.isConnected()) return true;
        }
        return false;
    }

    private void ackSidekick(int sidekickPort, String eventId) {
        try {
            URL url = new URL("http://localhost:" + sidekickPort + "/webviews/ack/" + eventId);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout((int) ACK_HTTP_TIMEOUT_MS);
            conn.setReadTimeout((int) ACK_HTTP_TIMEOUT_MS);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                // Empty body — ACK endpoint only cares about the URL path
            }
            int code = conn.getResponseCode();
            conn.disconnect();
            log.debug("WebView ack sent for event {}: HTTP {}", eventId, code);
        } catch (Exception e) {
            log.warn("Failed to ACK WebView event {}: {}", eventId, e.getMessage());
        }
    }

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
