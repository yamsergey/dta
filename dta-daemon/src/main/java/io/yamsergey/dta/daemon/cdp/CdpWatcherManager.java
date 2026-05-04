package io.yamsergey.dta.daemon.cdp;

import io.yamsergey.dta.daemon.sidekick.SidekickClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
     * Returns any connected CDP client on the given device, regardless of
     * which package's watcher owns it. Used by the layout enrichment when
     * the foreground app is Chrome itself (a CCT launched from a different
     * host app — the watcher is registered under the launcher's package,
     * not {@code com.android.chrome}). Picks the first watcher with a
     * live client; in practice there is at most one CCT on screen at a
     * time so the choice is unambiguous.
     */
    public ChromeDevToolsClient getAnyActiveCdpClient(String deviceSerial) {
        for (Map.Entry<String, WatcherContext> e : activeWatchers.entrySet()) {
            // Keys are "<device>:<package>"
            String key = e.getKey();
            int colon = key.indexOf(':');
            String entryDevice = colon > 0 ? key.substring(0, colon) : "default";
            if (!entryDevice.equals(deviceSerial != null ? deviceSerial : "default")) continue;
            WatcherContext ctx = e.getValue();
            if (ctx.currentClient != null && ctx.currentClient.isConnected()) {
                return ctx.currentClient;
            }
        }
        return null;
    }

    /** @see #getAnyActiveCdpClient — same lookup, returns the tab URL. */
    public String getAnyActiveTabUrl(String deviceSerial) {
        for (Map.Entry<String, WatcherContext> e : activeWatchers.entrySet()) {
            String key = e.getKey();
            int colon = key.indexOf(':');
            String entryDevice = colon > 0 ? key.substring(0, colon) : "default";
            if (!entryDevice.equals(deviceSerial != null ? deviceSerial : "default")) continue;
            WatcherContext ctx = e.getValue();
            if (ctx.currentTabUrl != null && ctx.currentClient != null && ctx.currentClient.isConnected()) {
                return ctx.currentTabUrl;
            }
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
        // Open the trace as early as possible — even a no-watcher rejection
        // is information worth keeping. The first step records when the SSE
        // event landed in the daemon (vs. when the executor picks it up).
        CctLaunchTrace.Entry trace = CctLaunchTrace.getInstance()
            .begin(eventId, packageName, deviceSerial, url);
        trace.step("sse_received");
        log.info("[cct={}] SSE customtab_will_launch pkg={} url={}", eventId, packageName, url);

        String key = makeKey(packageName, deviceSerial);
        WatcherContext context = activeWatchers.get(key);
        if (context != null) {
            context.onCustomTabWillLaunch(eventId, url, trace);
        } else {
            trace.stepFailed("no_watcher", "No active watcher for " + key);
            trace.finish("failed_no_watcher");
            log.warn("[cct={}] No active watcher for {} to handle Custom Tab event", eventId, key);
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

        /**
         * Scheduler for stuck-detection timers. One thread is enough — the
         * timer task is short (one /json/list call, mark trace, return).
         */
        private final ScheduledExecutorService stuckChecker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cct-stuck-checker");
            t.setDaemon(true);
            return t;
        });

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
            stuckChecker.shutdownNow();
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
         * @param trace the diagnostic trace already opened by the manager
         */
        void onCustomTabWillLaunch(String eventId, String targetUrl, CctLaunchTrace.Entry trace) {
            executor.submit(() -> {
                try {
                    trace.step("polling_started");
                    String urlBase = extractUrlBase(targetUrl);

                    // ACK immediately — must not delay Chrome launch.
                    // The sidekick has a 2s timeout; if the snapshot below blocks
                    // (e.g. Chrome not running), we'd miss the ACK window.
                    ackSidekick(eventId);
                    trace.step("ack_sent");

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
                                    .filter(t -> isLoadingTab(t.url())
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
                        trace.recordChromeTargets(snapshotChromeTargets());
                        trace.stepFailed("tab_matched", "no matching new tab within 5s");
                        trace.finish("failed_no_tab");
                        log.warn("[cct={}] Could not find Custom Tab for URL {} within 5s",
                            eventId, targetUrl);
                        return;
                    }
                    Map<String, Object> tabDetails = new LinkedHashMap<>();
                    tabDetails.put("tabId", tab.id());
                    tabDetails.put("tabUrl", tab.url());
                    tabDetails.put("isLoadingTab", isLoadingTab(tab.url()));
                    trace.step("tab_matched", tabDetails);

                    closeCurrentClient();
                    client.attachToTarget(tab);
                    currentClient = client;
                    monitor.setCdpClient(client);
                    trace.step("cdp_attached");

                    currentTabUrl = targetUrl;
                    client.setNetworkEventListener(cdpEvent -> {
                        monitor.onCdpEvent(cdpEvent, currentTabUrl);
                    });

                    // Subscribe to Page events so we can mark frame_navigated
                    // accurately (instead of polling tab URL on a timer).
                    final ChromeDevToolsClient finalClient = client;
                    final String tabId = tab.id();
                    final String loadingUrl = tab.url();
                    client.setPageEventListener(pe -> {
                        if ("Page.frameNavigated".equals(pe.method())) {
                            // Only the top-level frame matters here — sub-frames
                            // can navigate independently while the page is still
                            // on about:blank.
                            String frameUrl = pe.params().path("frame").path("url").asText("");
                            String parentId = pe.params().path("frame").path("parentId").asText("");
                            boolean isTopFrame = parentId.isEmpty();
                            if (isTopFrame && !isLoadingTab(frameUrl)
                                    && !trace.hasStep("frame_navigated")) {
                                Map<String, Object> d = new LinkedHashMap<>();
                                d.put("frameUrl", frameUrl);
                                trace.step("frame_navigated", d);
                                trace.finish("ok");
                                log.info("[cct={}] frame_navigated -> {}", eventId, frameUrl);
                            }
                        }
                    });

                    client.enableNetwork().join();
                    trace.step("network_enabled");

                    // Flatten auto-attach so child targets — out-of-process
                    // iframes (Auth0/Okta/Cognito login pages, embedded
                    // captchas, federation login windows), service workers,
                    // and any other sub-targets created after navigation —
                    // emit Network.* events into our same listener. Without
                    // this, XHR/Fetch from those sub-targets is invisible.
                    // Best-effort: a setAutoAttach failure shouldn't kill
                    // the main capture session.
                    try {
                        client.enableAutoAttachFlatten().join();
                        trace.step("autoattach_enabled");
                    } catch (Exception e) {
                        trace.stepFailed("autoattach_enabled", e.getMessage());
                        log.warn("[cct={}] Target.setAutoAttach failed — sub-frame XHR/Fetch may be missed: {}",
                            eventId, e.getMessage());
                    }

                    // Page.enable is required for frameNavigated events.
                    try {
                        client.enablePage().join();
                        trace.step("page_enabled");
                    } catch (Exception e) {
                        trace.stepFailed("page_enabled", e.getMessage());
                    }

                    // Navigate to the real URL now that Network capture is active.
                    // The sidekick opened a loading tab (about:blank or our
                    // data:text/html escape page) to give us time to attach CDP.
                    if (isLoadingTab(tab.url())) {
                        log.info("[cct={}] CDP attached to loading tab, navigating to: {}",
                            eventId, targetUrl);
                        client.navigate(targetUrl).join();
                        trace.step("page_navigate_sent");

                        // If frameNavigated doesn't arrive within 5s, the page
                        // is stuck on the loading URL — snapshot Chrome state
                        // for triage. The trace listener marks frame_navigated
                        // independently if it does fire.
                        scheduleStuckCheck(eventId, trace, finalClient, tabId, loadingUrl);
                    } else {
                        // No navigation needed — tab already has the real URL.
                        // Don't schedule a stuck check because frame_navigated
                        // may have already fired before we attached.
                        trace.finish("ok");
                    }

                    log.info("[cct={}] CDP attached to Custom Tab: {} ({})",
                        eventId, tab.title(), targetUrl);

                } catch (Exception e) {
                    trace.stepFailed("attach_error", e.getMessage());
                    trace.finish("failed_exception");
                    log.error("[cct={}] Failed to attach CDP: {}", eventId, e.getMessage(), e);
                }
            });
        }

        /**
         * Snapshots {@code /json/list} for the trace's chromeTargetsAtFailure
         * field. Best-effort — Chrome may have died, port forward may be
         * gone; in that case we record an empty list rather than throw.
         */
        private List<Map<String, Object>> snapshotChromeTargets() {
            List<Map<String, Object>> out = new java.util.ArrayList<>();
            try (ChromeDevToolsClient c = new ChromeDevToolsClient("localhost", cdpPort)) {
                for (CdpTarget t : c.listTargets()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", t.id());
                    m.put("type", t.type());
                    m.put("url", t.url());
                    m.put("title", t.title());
                    out.add(m);
                }
            } catch (Exception e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("error", e.getMessage());
                out.add(err);
            }
            return out;
        }

        /**
         * Schedules a stuck check 5s after Page.navigate is sent. If by then
         * no frame_navigated step has fired, the trace is marked stuck and
         * a Chrome target snapshot is recorded for triage.
         */
        private void scheduleStuckCheck(String eventId, CctLaunchTrace.Entry trace,
                                         ChromeDevToolsClient client, String tabId, String loadingUrl) {
            stuckChecker.schedule(() -> {
                if (trace.hasStep("frame_navigated")) {
                    return; // already navigated
                }
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("tabId", tabId);
                details.put("loadingUrl", loadingUrl);
                details.put("wsConnected", client.isConnected());
                trace.recordWsState(client.isConnected() ? "connected" : "closed");
                trace.recordChromeTargets(snapshotChromeTargets());
                trace.stepFailed("stuck", "no frame_navigated within 5s after Page.navigate", details);
                trace.finish("stuck");
                log.warn("[cct={}] STUCK on loading tab — no frame_navigated within 5s. tabId={}",
                    eventId, tabId);
            }, 5, TimeUnit.SECONDS);
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

        /**
         * Recognises the placeholder tab the sidekick opens so the daemon
         * can attach before navigating to the real URL. Currently always
         * {@code about:blank}; will broaden to a sidekick-served loading
         * page (http://127.0.0.1:PORT/loading) once that landing surface
         * is wired up, so callers should use this helper rather than
         * comparing to {@code about:blank} directly.
         */
        private static boolean isLoadingTab(String url) {
            return "about:blank".equals(url);
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
