package io.yamsergey.dta.daemon.cdp;

import io.yamsergey.dta.daemon.sidekick.SidekickClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-device browser-level CDP connection that captures network traffic from
 * standalone Chrome (i.e. tabs the host app launched via {@code Intent.ACTION_VIEW},
 * not Chrome Custom Tabs).
 *
 * <p>The flow is:</p>
 * <ol>
 *   <li>First {@code chrome_will_launch} SSE from sidekick triggers
 *       {@link #onChromeWillLaunch}, which lazily establishes a browser-CDP
 *       connection to {@code @chrome_devtools_remote} on the device.</li>
 *   <li>The browser connection issues
 *       {@code Target.setDiscoverTargets({discover: true})} and
 *       {@code Target.setAutoAttach({autoAttach: true, waitForDebuggerOnStart: true,
 *       flatten: true})} so every new tab Chrome creates pauses before any
 *       network or script activity.</li>
 *   <li>For each {@code Target.attachedToTarget} event the manager extracts
 *       the {@code sessionId} and uses the CDP <i>flat protocol</i> to send
 *       {@code Network.enable} and {@code Runtime.runIfWaitingForDebugger} via
 *       the existing browser WebSocket. Network.* events arrive on the same
 *       browser connection wrapped with the matching {@code sessionId} and are
 *       routed by {@link ChromeDevToolsClient#setSessionEventListener}. We
 *       deliberately don't open a per-target WebSocket: Chrome doesn't reliably
 *       surface paused tabs in {@code /json/list} and the {@code id} returned
 *       there isn't the same as the CDP {@code targetId} anyway.</li>
 *   <li>Captured events feed a {@link CustomTabsNetworkMonitor} configured
 *       with source tag {@code "Chrome"} so consumers can distinguish the
 *       two capture paths.</li>
 * </ol>
 *
 * <p>Correlation: pending {@code chrome_will_launch} events are matched
 * against new targets by URL plus a temporal window. Targets that match are
 * captured. Targets that don't match (user-initiated tabs the user opened
 * while DTA was running) are still resumed but capture is skipped on them —
 * the brief pause is the minimum-invasive cost; the network traffic stays
 * private.</p>
 */
public class ChromeBrowserCdpManager {

    private static final Logger log = LoggerFactory.getLogger(ChromeBrowserCdpManager.class);

    private static final ChromeBrowserCdpManager INSTANCE = new ChromeBrowserCdpManager();

    /** How long after a chrome_will_launch SSE we still consider it correlatable. */
    private static final long CORRELATION_WINDOW_MS = 3_000;

    private final Map<String, BrowserContext> contexts = new ConcurrentHashMap<>();

    private ChromeBrowserCdpManager() {}

    public static ChromeBrowserCdpManager getInstance() {
        return INSTANCE;
    }

    /**
     * Forwards a sidekick {@code chrome_will_launch} SSE event to the
     * appropriate per-device browser context, ensuring a CDP connection is
     * established lazily on first use.
     */
    public void onChromeWillLaunch(String deviceSerial,
                                   SidekickClient sidekickClient,
                                   String eventId,
                                   String url,
                                   long timestamp) {
        String key = deviceKey(deviceSerial);
        BrowserContext ctx = contexts.computeIfAbsent(key, k -> new BrowserContext(deviceSerial, sidekickClient));
        ctx.recordPendingEvent(eventId, url, timestamp);
        ctx.ensureConnectedAsync();
    }

    /**
     * Stops the browser CDP connection for the given device (e.g. on daemon
     * shutdown or when a device is disconnected).
     */
    public synchronized void stop(String deviceSerial) {
        BrowserContext ctx = contexts.remove(deviceKey(deviceSerial));
        if (ctx != null) {
            ctx.shutdown();
        }
    }

    public synchronized void stopAll() {
        contexts.values().forEach(BrowserContext::shutdown);
        contexts.clear();
    }

    private static String deviceKey(String deviceSerial) {
        return deviceSerial != null ? deviceSerial : "default";
    }

    // =========================================================================
    // Per-device state
    // =========================================================================

    private static final class BrowserContext {
        private final String deviceSerial;
        private final SidekickClient sidekickClient;
        private final AtomicBoolean connecting = new AtomicBoolean(false);
        private final AtomicBoolean shutdown = new AtomicBoolean(false);
        private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "chrome-cdp-" + deviceKey(null));
            t.setDaemon(true);
            return t;
        });

        // Pending chrome_will_launch events awaiting target correlation.
        private final List<PendingLaunch> pending = new ArrayList<>();
        private final Object pendingLock = new Object();

        // Captured target sessionIds. Keyed by targetId so duplicate
        // attachedToTarget events (which Chrome can emit) don't double-attach.
        private final Map<String, String> targetSessions = new ConcurrentHashMap<>();

        // The browser-level connection (browser CDP, the source of Target.* events).
        private volatile ChromeDevToolsClient browserClient;
        private volatile int cdpPort;

        // The shared monitor that ingests Network.* events and posts to sidekick.
        private volatile CustomTabsNetworkMonitor monitor;

        BrowserContext(String deviceSerial, SidekickClient sidekickClient) {
            this.deviceSerial = deviceSerial;
            this.sidekickClient = sidekickClient;
        }

        void recordPendingEvent(String eventId, String url, long timestamp) {
            synchronized (pendingLock) {
                pending.add(new PendingLaunch(eventId, url, timestamp));
                // Drop events that aged out of the correlation window. A user
                // typing a URL minutes after an app fired one shouldn't be
                // attributed to the app.
                long cutoff = System.currentTimeMillis() - CORRELATION_WINDOW_MS;
                pending.removeIf(p -> p.timestamp < cutoff);
            }
        }

        void ensureConnectedAsync() {
            if (browserClient != null && browserClient.isConnected()) {
                return;
            }
            if (!connecting.compareAndSet(false, true)) {
                return; // someone else is already connecting
            }
            executor.submit(() -> {
                try {
                    connectWithRetry();
                } catch (Exception e) {
                    log.error("Failed to connect Chrome browser CDP for device {}: {}",
                            deviceKey(deviceSerial), e.getMessage(), e);
                } finally {
                    connecting.set(false);
                }
            });
        }

        /**
         * Polls for {@code @chrome_devtools_remote} for a short deadline and
         * connects as soon as the socket is available. This matters because
         * the {@code chrome_will_launch} SSE event arrives <i>before</i> Chrome
         * has finished spawning (cold start). If we gave up on the first miss
         * we'd connect only on the second tap; setAutoAttach would then race
         * with new-tab creation and the first request of every Intent-launched
         * tab would slip past Network capture.
         */
        private void connectWithRetry() throws Exception {
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                if (shutdown.get()) return;
                String socket = ChromeSocketDiscovery.findChromeSocket(deviceSerial);
                if (socket != null) {
                    connect(socket);
                    return;
                }
                Thread.sleep(200);
            }
            log.warn("@chrome_devtools_remote not present on device {} after 10s — giving up. " +
                    "Capture will retry on next chrome_will_launch event.", deviceKey(deviceSerial));
        }

        private void connect(String socket) throws Exception {
            int port = ChromeSocketDiscovery.setupPortForwardAuto(deviceSerial, socket);
            this.cdpPort = port;
            log.info("Chrome browser CDP forwarding {} → localhost:{}", socket, port);

            // monitor lazily created on first use, fed by per-target session clients.
            // Pass deviceSerial=null because we only use this monitor's posting path
            // (it routes via SidekickClient), and source tag = "Chrome".
            monitor = new CustomTabsNetworkMonitor(deviceSerial, null, "Chrome");
            monitor.setSidekickClient(sidekickClient);

            browserClient = new ChromeDevToolsClient("localhost", port);
            String wsUrl = "ws://localhost:" + port + "/devtools/browser";
            browserClient.attachToWebSocket(wsUrl);
            browserClient.setRawEventListener(this::onRawBrowserEvent);

            browserClient.send("Target.setDiscoverTargets", Map.of("discover", true)).join();
            browserClient.send("Target.setAutoAttach", Map.of(
                    "autoAttach", true,
                    "waitForDebuggerOnStart", true,
                    "flatten", true
            )).join();

            log.info("Chrome browser CDP attached on {}: setDiscoverTargets + setAutoAttach armed",
                    deviceKey(deviceSerial));
        }

        /**
         * Dispatched on every CDP event received over the browser-level
         * connection. We only act on {@code Target.attachedToTarget}; the
         * autoAttach + waitForDebuggerOnStart settings make this fire for
         * every new tab, paused before any network/script activity.
         */
        private void onRawBrowserEvent(String method, tools.jackson.databind.JsonNode params) {
            if (!"Target.attachedToTarget".equals(method)) {
                return;
            }
            tools.jackson.databind.JsonNode targetInfo = params.path("targetInfo");
            String type = targetInfo.path("type").asString("");
            if (!"page".equals(type)) {
                // Service workers, shared workers etc. — not interesting for
                // standalone Chrome network capture today.
                return;
            }
            String targetId = targetInfo.path("targetId").asString("");
            String url = targetInfo.path("url").asString("");
            String sessionId = params.path("sessionId").asString(null);
            boolean waitingForDebugger = params.path("waitingForDebugger").asBoolean(false);
            executor.submit(() -> attachAndMaybeCapture(targetId, url, sessionId, waitingForDebugger));
        }

        private void attachAndMaybeCapture(String targetId, String initialUrl, String autoAttachSessionId,
                                           boolean waitingForDebugger) {
            if (shutdown.get()) return;
            if (targetSessions.containsKey(targetId)) {
                return; // already captured
            }
            if (autoAttachSessionId == null || autoAttachSessionId.isEmpty()) {
                log.warn("Target.attachedToTarget for {} had no sessionId — cannot capture via flat protocol", targetId);
                return;
            }

            try {
                // Decide attribution before enabling capture: only app-correlated
                // tabs get captured. Others are released unmonitored. Targets
                // paused by waitForDebuggerOnStart don't always surface in
                // /json/list, so we correlate purely on the URL we already have
                // from the Target.attachedToTarget event.
                PendingLaunch matched = correlate(initialUrl, initialUrl);
                if (matched == null) {
                    log.debug("Chrome target {} ({}) has no matching pending launch — releasing without capture",
                            targetId, initialUrl);
                    resumeViaBrowser(autoAttachSessionId);
                    return;
                }
                log.info("Chrome target {} matched chrome_will_launch event {} (url={})",
                        targetId, matched.eventId, matched.url);

                // Use the CDP flat protocol: send commands and receive Network.*
                // events on the existing browser WebSocket, scoped via sessionId.
                // No per-target WebSocket needed — Chrome's /json/list short-id
                // doesn't match the CDP targetId so direct attachment by id is
                // unreliable; the autoAttach session is the authoritative handle.
                final String sessionId = autoAttachSessionId;
                final String navigatedUrl = matched.url;
                browserClient.setSessionEventListener(sessionId, (method, params) -> {
                    CdpNetworkEvent ne = parseNetworkEvent(method, params);
                    if (ne != null) {
                        monitor.onCdpEvent(ne, navigatedUrl);
                    }
                });
                targetSessions.put(targetId, sessionId);

                browserClient.send("Network.enable", Map.of(), sessionId)
                        .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS).join();
                if (waitingForDebugger) {
                    // Release the pause. waitForDebuggerOnStart suspends the V8
                    // isolate until any session sends runIfWaitingForDebugger.
                    // Sending it on a tab that wasn't paused appears to make
                    // Chrome detach our session right after, so only call it
                    // when needed.
                    browserClient.send("Runtime.runIfWaitingForDebugger", Map.of(), sessionId)
                            .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS).join();
                }
                log.info("Chrome target {} capture armed (session {}, waitingForDebugger={})",
                        targetId, sessionId, waitingForDebugger);

            } catch (Exception e) {
                log.error("Chrome target {} attach failed: {}", targetId, e.getMessage(), e);
                resumeViaBrowser(autoAttachSessionId);
            }
        }

        /**
         * Parses a raw CDP event JSON into the strongly-typed
         * {@link CdpNetworkEvent} the monitor expects. Returns null for
         * non-Network events. Mirrors the parsing done by
         * {@link ChromeDevToolsClient#setNetworkEventListener} but for raw
         * session-scoped events that arrive on the browser WebSocket.
         */
        private static CdpNetworkEvent parseNetworkEvent(String method, tools.jackson.databind.JsonNode params) {
            return switch (method) {
                case "Network.requestWillBeSent" -> parseRequestWillBeSent(params);
                case "Network.responseReceived" -> parseResponseReceived(params);
                case "Network.loadingFinished" -> parseLoadingFinished(params);
                case "Network.loadingFailed" -> parseLoadingFailed(params);
                case "Network.dataReceived" -> parseDataReceived(params);
                default -> null;
            };
        }

        private static CdpNetworkEvent.RequestWillBeSent parseRequestWillBeSent(tools.jackson.databind.JsonNode params) {
            tools.jackson.databind.JsonNode request = params.path("request");
            tools.jackson.databind.JsonNode redirectResponseNode = params.path("redirectResponse");
            CdpNetworkEvent.RedirectResponse redirectResponse = null;
            if (!redirectResponseNode.isMissingNode()) {
                redirectResponse = new CdpNetworkEvent.RedirectResponse(
                    redirectResponseNode.path("url").asText(""),
                    redirectResponseNode.path("status").asInt(),
                    redirectResponseNode.path("statusText").asText(""),
                    parseHeaders(redirectResponseNode.path("headers"))
                );
            }
            String resourceType = params.path("type").asText("");
            if (resourceType.isEmpty()) resourceType = null;
            return new CdpNetworkEvent.RequestWillBeSent(
                params.path("requestId").asText(""),
                params.path("loaderId").asText(""),
                params.path("documentURL").asText(""),
                request.path("url").asText(""),
                request.path("method").asText(""),
                parseHeaders(request.path("headers")),
                request.path("postData").asText(null),
                (long) (params.path("timestamp").asDouble() * 1000),
                resourceType,
                redirectResponse
            );
        }

        private static CdpNetworkEvent.ResponseReceived parseResponseReceived(tools.jackson.databind.JsonNode params) {
            tools.jackson.databind.JsonNode response = params.path("response");
            return new CdpNetworkEvent.ResponseReceived(
                params.path("requestId").asText(""),
                params.path("loaderId").asText(""),
                response.path("url").asText(""),
                response.path("status").asInt(),
                response.path("statusText").asText(""),
                parseHeaders(response.path("headers")),
                response.path("mimeType").asText(""),
                (long) (params.path("timestamp").asDouble() * 1000),
                response.path("protocol").asText(""),
                response.path("encodedDataLength").asLong()
            );
        }

        private static CdpNetworkEvent.LoadingFinished parseLoadingFinished(tools.jackson.databind.JsonNode params) {
            return new CdpNetworkEvent.LoadingFinished(
                params.path("requestId").asText(""),
                (long) (params.path("timestamp").asDouble() * 1000),
                params.path("encodedDataLength").asLong()
            );
        }

        private static CdpNetworkEvent.LoadingFailed parseLoadingFailed(tools.jackson.databind.JsonNode params) {
            tools.jackson.databind.JsonNode blocked = params.path("blockedReason");
            String blockedReason = blocked.isMissingNode() || blocked.isNull() ? null : blocked.asText("");
            return new CdpNetworkEvent.LoadingFailed(
                params.path("requestId").asText(""),
                (long) (params.path("timestamp").asDouble() * 1000),
                params.path("errorText").asText(""),
                params.path("canceled").asBoolean(false),
                blockedReason
            );
        }

        private static CdpNetworkEvent.DataReceived parseDataReceived(tools.jackson.databind.JsonNode params) {
            return new CdpNetworkEvent.DataReceived(
                params.path("requestId").asText(""),
                (long) (params.path("timestamp").asDouble() * 1000),
                params.path("dataLength").asLong(),
                params.path("encodedDataLength").asLong()
            );
        }

        private static Map<String, String> parseHeaders(tools.jackson.databind.JsonNode headers) {
            Map<String, String> result = new HashMap<>();
            if (headers != null && headers.isObject()) {
                headers.properties().forEach(e -> result.put(e.getKey(), e.getValue().asText("")));
            }
            return result;
        }

        /**
         * Sends {@code Runtime.runIfWaitingForDebugger} on the browser-level
         * session as a fallback release path. With {@code flatten: true}, the
         * sessionId envelope is required — we fall back to sending the command
         * on the auto-attach session that came with the targetCreated event.
         */
        private void resumeViaBrowser(String sessionId) {
            if (browserClient == null || sessionId == null || sessionId.isEmpty()) return;
            try {
                Map<String, Object> params = new HashMap<>();
                params.put("sessionId", sessionId);
                // sendMessageToTarget is the legacy escape hatch; some Chrome
                // versions reject it under flatten:true. If this fails, the
                // tab may stay paused — but that's better than holding open
                // a hidden capture session indefinitely.
                browserClient.send("Target.sendMessageToTarget", Map.of(
                        "sessionId", sessionId,
                        "message", "{\"id\":99999,\"method\":\"Runtime.runIfWaitingForDebugger\"}"
                ));
            } catch (Exception ignored) {}
        }

        private PendingLaunch correlate(String initialUrl, String tabUrl) {
            synchronized (pendingLock) {
                long now = System.currentTimeMillis();
                long cutoff = now - CORRELATION_WINDOW_MS;
                pending.removeIf(p -> p.timestamp < cutoff);
                // Best match: exact URL equality. Fallback: same scheme+host+path.
                for (int i = 0; i < pending.size(); i++) {
                    PendingLaunch p = pending.get(i);
                    if (urlsMatch(p.url, initialUrl) || urlsMatch(p.url, tabUrl)) {
                        pending.remove(i);
                        return p;
                    }
                }
                return null;
            }
        }

        private static boolean urlsMatch(String a, String b) {
            if (a == null || b == null) return false;
            if (a.equals(b)) return true;
            try {
                java.net.URI ua = java.net.URI.create(a);
                java.net.URI ub = java.net.URI.create(b);
                return java.util.Objects.equals(ua.getScheme(), ub.getScheme())
                        && java.util.Objects.equals(ua.getHost(), ub.getHost())
                        && java.util.Objects.equals(ua.getPath(), ub.getPath());
            } catch (Exception e) {
                return false;
            }
        }

        void shutdown() {
            if (!shutdown.compareAndSet(false, true)) return;
            if (browserClient != null) {
                for (String sessionId : targetSessions.values()) {
                    browserClient.setSessionEventListener(sessionId, null);
                }
            }
            targetSessions.clear();
            if (browserClient != null) {
                try { browserClient.close(); } catch (Exception ignored) {}
                browserClient = null;
            }
            if (monitor != null) {
                monitor.close();
                monitor = null;
            }
            if (cdpPort > 0) {
                try { ChromeSocketDiscovery.removePortForward(deviceSerial, cdpPort); } catch (Exception ignored) {}
            }
            executor.shutdownNow();
        }

        private record PendingLaunch(String eventId, String url, long timestamp) {}
    }
}
