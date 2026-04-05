package io.yamsergey.dta.daemon.cdp;

import io.yamsergey.dta.daemon.sidekick.SidekickClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Manages CDP watchers for Custom Tabs network monitoring.
 *
 * <p>This singleton ensures only one watcher runs per package/device combination,
 * preventing duplicate monitoring when multiple components (inspector-web, MCP)
 * might try to start watching the same app.</p>
 *
 * <p>In event-driven mode, the watcher does not poll for Custom Tabs.
 * Instead, it waits for SSE events from sidekick and attaches CDP on demand.</p>
 */
public class CdpWatcherManager {

    private static final Logger log = LoggerFactory.getLogger(CdpWatcherManager.class);

    private static final CdpWatcherManager INSTANCE = new CdpWatcherManager();

    private final Map<String, WatcherContext> activeWatchers = new ConcurrentHashMap<>();

    private CdpWatcherManager() {}

    public static CdpWatcherManager getInstance() {
        return INSTANCE;
    }

    /**
     * Information about an active watcher.
     */
    public record WatcherInfo(
        String packageName,
        String deviceSerial,
        String currentTabUrl,
        boolean isConnected,
        long startTime
    ) {}

    /**
     * Starts a CDP watcher for the given package if not already running.
     *
     * <p>In event-driven mode, the watcher simply registers the context.
     * Actual CDP attach happens when {@link #onCustomTabWillLaunch} is called.</p>
     *
     * @param packageName the Android package name
     * @param deviceSerial the device serial (or null for default)
     * @param cdpPort the local port for Chrome DevTools
     * @param sidekickPort the local port for the sidekick server
     * @param sidekickClient the sidekick client for posting transactions
     * @param eventCallback optional callback for network events
     * @return true if watcher was started, false if already running
     */
    public synchronized boolean startWatcher(
            String packageName,
            String deviceSerial,
            int cdpPort,
            int sidekickPort,
            SidekickClient sidekickClient,
            Consumer<CustomTabsNetworkMonitor.CustomTabNetworkEvent> eventCallback) {

        String key = makeKey(packageName, deviceSerial);

        if (activeWatchers.containsKey(key)) {
            // Already watching
            return false;
        }

        WatcherContext context = new WatcherContext(
            packageName, deviceSerial, cdpPort, sidekickPort, sidekickClient, eventCallback
        );
        activeWatchers.put(key, context);
        context.start();

        return true;
    }

    /**
     * Stops the CDP watcher for the given package.
     *
     * @param packageName the Android package name
     * @param deviceSerial the device serial (or null for default)
     * @return true if watcher was stopped, false if not running
     */
    public synchronized boolean stopWatcher(String packageName, String deviceSerial) {
        String key = makeKey(packageName, deviceSerial);
        WatcherContext context = activeWatchers.remove(key);

        if (context != null) {
            context.stop();
            return true;
        }
        return false;
    }

    /**
     * Checks if a watcher is active for the given package.
     */
    public boolean isWatching(String packageName, String deviceSerial) {
        return activeWatchers.containsKey(makeKey(packageName, deviceSerial));
    }

    /**
     * Gets info about an active watcher.
     */
    public WatcherInfo getWatcherInfo(String packageName, String deviceSerial) {
        WatcherContext context = activeWatchers.get(makeKey(packageName, deviceSerial));
        if (context == null) {
            return null;
        }
        return context.getInfo();
    }

    /**
     * Returns the active CDP client for the given package, if one is connected.
     * Used for enriching the layout tree with Custom Tab accessibility data.
     *
     * @param packageName the Android package name
     * @param deviceSerial the device serial (or null for default)
     * @return the connected ChromeDevToolsClient, or null if not available
     */
    public ChromeDevToolsClient getCdpClient(String packageName, String deviceSerial) {
        WatcherContext context = activeWatchers.get(makeKey(packageName, deviceSerial));
        if (context != null && context.currentClient != null && context.currentClient.isConnected()) {
            return context.currentClient;
        }
        return null;
    }

    /**
     * Returns the current Custom Tab URL for the given package, if tracked.
     *
     * @param packageName the Android package name
     * @param deviceSerial the device serial (or null for default)
     * @return the current tab URL, or null
     */
    public String getCurrentTabUrl(String packageName, String deviceSerial) {
        WatcherContext context = activeWatchers.get(makeKey(packageName, deviceSerial));
        return context != null ? context.currentTabUrl : null;
    }

    /**
     * Gets info about all active watchers.
     */
    public List<WatcherInfo> getAllWatchers() {
        return activeWatchers.values().stream()
            .map(WatcherContext::getInfo)
            .toList();
    }

    /**
     * Stops all active watchers.
     */
    public synchronized void stopAll() {
        activeWatchers.values().forEach(WatcherContext::stop);
        activeWatchers.clear();
    }

    /**
     * Forwards an SSE Custom Tab launch event to the appropriate watcher.
     *
     * @param packageName the Android package name
     * @param deviceSerial the device serial (or null for default)
     * @param eventId the SSE event ID for ack
     * @param url the URL being opened in the Custom Tab
     */
    public void onCustomTabWillLaunch(String packageName, String deviceSerial, String eventId, String url) {
        String key = makeKey(packageName, deviceSerial);
        WatcherContext context = activeWatchers.get(key);
        if (context != null) {
            context.onCustomTabWillLaunch(eventId, url);
        } else {
            log.warn("No active watcher for {} to handle Custom Tab event", key);
        }
    }

    private String makeKey(String packageName, String deviceSerial) {
        return (deviceSerial != null ? deviceSerial : "default") + ":" + packageName;
    }

    /**
     * Internal context for a running watcher.
     */
    private static class WatcherContext {
        private final String packageName;
        private final String deviceSerial;
        private final int cdpPort;
        private final int sidekickPort;
        private final SidekickClient sidekickClient;
        private final Consumer<CustomTabsNetworkMonitor.CustomTabNetworkEvent> eventCallback;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final long startTime;
        private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "cdp-attach-worker");
            t.setDaemon(true);
            return t;
        });

        private CustomTabsNetworkMonitor monitor;
        private volatile ChromeDevToolsClient currentClient;
        private volatile String currentTabUrl;

        WatcherContext(
                String packageName,
                String deviceSerial,
                int cdpPort,
                int sidekickPort,
                SidekickClient sidekickClient,
                Consumer<CustomTabsNetworkMonitor.CustomTabNetworkEvent> eventCallback) {
            this.packageName = packageName;
            this.deviceSerial = deviceSerial;
            this.cdpPort = cdpPort;
            this.sidekickPort = sidekickPort;
            this.sidekickClient = sidekickClient;
            this.eventCallback = eventCallback;
            this.startTime = System.currentTimeMillis();
        }

        void start() {
            if (running.compareAndSet(false, true)) {
                monitor = new CustomTabsNetworkMonitor(deviceSerial, eventCallback);
                monitor.setSidekickClient(sidekickClient);
                log.info("CDP watcher started for {} (event-driven, cdpPort={}, sidekickPort={})",
                    packageName, cdpPort, sidekickPort);
            }
        }

        void stop() {
            running.set(false);
            closeCurrentClient();
            if (monitor != null) {
                monitor.close();
            }
            executor.shutdownNow();
            log.info("CDP watcher stopped for {}", packageName);
        }

        WatcherInfo getInfo() {
            return new WatcherInfo(
                packageName,
                deviceSerial,
                currentTabUrl,
                currentClient != null && currentClient.isConnected(),
                startTime
            );
        }

        /**
         * Handles a Custom Tab launch event from SSE.
         *
         * <p>The JVMTI hook on the device blocks launchUrl() until we ACK. Since the
         * Chrome tab doesn't exist yet (Chrome hasn't launched), we can't attach CDP
         * before ACKing. Instead we:</p>
         * <ol>
         *   <li>Pre-create the CDP client connected to Chrome's HTTP endpoint</li>
         *   <li>ACK sidekick — Chrome launches, tab appears</li>
         *   <li>Poll aggressively (50ms) to find the new tab</li>
         *   <li>Attach WebSocket + enable Network as fast as possible</li>
         * </ol>
         *
         * @param eventId the SSE event ID for ack
         * @param targetUrl the URL being opened (used to match the correct Chrome tab)
         */
        void onCustomTabWillLaunch(String eventId, String targetUrl) {
            executor.submit(() -> {
                try {
                    String urlBase = extractUrlBase(targetUrl);

                    // ACK immediately — must not delay Chrome launch.
                    // The sidekick has a 2s timeout; if the snapshot below blocks
                    // (e.g. Chrome not running), we'd miss the ACK window.
                    ackSidekick(eventId);

                    // Pre-create the client for polling
                    ChromeDevToolsClient pollClient = new ChromeDevToolsClient("localhost", cdpPort);

                    // Snapshot existing tab IDs to distinguish new tab from stale ones.
                    // Best-effort — if Chrome isn't running yet, we use empty set
                    // and rely on URL matching alone.
                    java.util.Set<String> existingTabIds = java.util.Collections.emptySet();
                    try {
                        existingTabIds = pollClient.listTargets().stream()
                            .map(CdpTarget::id)
                            .collect(java.util.stream.Collectors.toSet());
                    } catch (Exception e) {
                        // Chrome not running yet — empty set is fine, URL match will work
                    }
                    final java.util.Set<String> knownIds = existingTabIds;

                    // Poll aggressively for the new tab (50ms intervals, 5s deadline)
                    ChromeDevToolsClient client = null;
                    CdpTarget tab = null;
                    long deadline = System.currentTimeMillis() + 5000;

                    try {
                        while (System.currentTimeMillis() < deadline) {
                            try {
                                List<CdpTarget> targets = pollClient.listTargets();
                                // Find a NEW tab — match about:blank (blank page trick)
                                // or the target URL (fallback if blank page wasn't used)
                                tab = targets.stream()
                                    .filter(CdpTarget::isPage)
                                    .filter(t -> !knownIds.contains(t.id()))
                                    .filter(t -> "about:blank".equals(t.url())
                                            || matchesUrl(t.url(), targetUrl, urlBase))
                                    .findFirst()
                                    .orElse(null);
                                if (tab != null) {
                                    client = pollClient;
                                    pollClient = null; // prevent close in finally
                                    break;
                                }
                            } catch (Exception e) {
                                // Chrome not ready yet
                            }
                            Thread.sleep(50);
                        }
                    } finally {
                        if (pollClient != null) {
                            try { pollClient.close(); } catch (Exception ignored) {}
                        }
                    }

                    if (tab == null || client == null) {
                        log.warn("Could not find Custom Tab for URL {} within 5s", targetUrl);
                        return;
                    }

                    closeCurrentClient();
                    client.attachToTarget(tab);
                    currentClient = client;
                    monitor.setCdpClient(client);

                    currentTabUrl = targetUrl;
                    client.setNetworkEventListener(cdpEvent -> {
                        monitor.onCdpEvent(cdpEvent, currentTabUrl);
                    });

                    client.enableNetwork().join();

                    // Navigate to the real URL now that Network capture is active.
                    // The sidekick opened about:blank to give us time to attach CDP.
                    if ("about:blank".equals(tab.url())) {
                        log.info("CDP attached to blank tab, navigating to: {}", targetUrl);
                        client.navigate(targetUrl).join();
                    }

                    log.info("CDP attached to Custom Tab: {} ({})", tab.title(), targetUrl);

                } catch (Exception e) {
                    log.error("Failed to attach CDP for event {}: {}", eventId, e.getMessage(), e);
                }
            });
        }

        private static String extractUrlBase(String url) {
            if (url == null) return "";
            try {
                java.net.URI uri = java.net.URI.create(url);
                String path = uri.getPath();
                if (path == null || path.isEmpty()) path = "/";
                return uri.getScheme() + "://" + uri.getHost() + path;
            } catch (Exception e) {
                return url;
            }
        }

        private static boolean matchesUrl(String tabUrl, String targetUrl, String urlBase) {
            if (tabUrl == null || targetUrl == null) return false;
            return tabUrl.equals(targetUrl)
                || tabUrl.startsWith(urlBase)
                || targetUrl.startsWith(extractUrlBase(tabUrl));
        }

        private void ackSidekick(String eventId) {
            try {
                var url = new java.net.URL("http://localhost:" + sidekickPort + "/customtabs/ack/" + eventId);
                var conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setDoOutput(true);
                conn.getOutputStream().close();
                int code = conn.getResponseCode();
                conn.disconnect();
                log.debug("CDP ack sent for event {}: HTTP {}", eventId, code);
            } catch (Exception e) {
                log.warn("Failed to ack CDP event {}: {}", eventId, e.getMessage());
            }
        }

        private void closeCurrentClient() {
            if (currentClient != null) {
                try {
                    currentClient.close();
                } catch (Exception ignored) {}
                currentClient = null;
            }
            if (monitor != null) {
                monitor.setCdpClient(null);
            }
        }
    }
}
