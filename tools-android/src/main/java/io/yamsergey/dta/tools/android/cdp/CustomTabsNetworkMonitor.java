package io.yamsergey.dta.tools.android.cdp;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.yamsergey.dta.tools.android.inspect.compose.SidekickClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Monitors Chrome Custom Tabs network traffic via CDP.
 *
 * <p>This class orchestrates the connection between Custom Tab launch events
 * (detected by sidekick) and Chrome's DevTools Protocol to capture actual
 * network traffic from Custom Tabs.</p>
 *
 * <h3>Architecture</h3>
 * <pre>
 * Sidekick (in-app)              CustomTabsNetworkMonitor
 * ─────────────────              ───────────────────────
 * JVMTI detects           ──▶    onCustomTabOpened()
 *   CustomTab launch             │
 *                                ▼
 *                            Find Chrome DevTools socket
 *                                │
 *                                ▼
 *                            Poll /json/list for tab
 *                                │
 *                                ▼
 *                            Attach via WebSocket
 *                                │
 *                                ▼
 *                            Network.enable
 *                                │
 *                                ▼
 *                            Capture events ──▶ NetworkEventCallback
 * </pre>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * CustomTabsNetworkMonitor monitor = new CustomTabsNetworkMonitor(
 *     deviceSerial,
 *     event -> System.out.println("Network: " + event)
 * );
 *
 * // Pre-connect to Chrome (optional but recommended)
 * monitor.preConnect();
 *
 * // When sidekick reports Custom Tab opened
 * monitor.onCustomTabOpened(url, headers, timestamp);
 *
 * // Later: cleanup
 * monitor.close();
 * }</pre>
 */
public class CustomTabsNetworkMonitor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CustomTabsNetworkMonitor.class);

    private static final Duration TAB_POLL_INTERVAL = Duration.ofMillis(200);
    private static final Duration TAB_POLL_TIMEOUT = Duration.ofSeconds(10);
    private static final int DEFAULT_CDP_PORT = 9222;

    private final String deviceSerial;
    private final Consumer<CustomTabNetworkEvent> eventCallback;
    private final ScheduledExecutorService executor;
    private final Map<String, ChromeDevToolsClient> activeClients;

    // Transaction correlation - tracks in-flight requests by CDP requestId
    private final Map<String, InFlightTransaction> inFlightTransactions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Optional: SidekickClient for posting transactions to sidekick
    private volatile SidekickClient sidekickClient;

    // Optional: ChromeDevToolsClient for fetching response bodies
    private volatile ChromeDevToolsClient cdpClient;

    private volatile boolean preConnected;
    private volatile int forwardedPort;
    private volatile String chromeSocketName;

    /**
     * Creates a new Custom Tabs network monitor.
     *
     * @param deviceSerial   the Android device serial (or null for default)
     * @param eventCallback  callback for network events
     */
    public CustomTabsNetworkMonitor(String deviceSerial, Consumer<CustomTabNetworkEvent> eventCallback) {
        this.deviceSerial = deviceSerial;
        this.eventCallback = eventCallback;
        this.executor = Executors.newScheduledThreadPool(2);
        this.activeClients = new ConcurrentHashMap<>();
    }

    /**
     * Pre-connects to Chrome DevTools by setting up port forwarding.
     * Call this on startup to reduce latency when Custom Tabs are opened.
     *
     * @throws IOException if connection fails
     * @throws InterruptedException if interrupted
     */
    public void preConnect() throws IOException, InterruptedException {
        if (preConnected) {
            return;
        }

        // Find Chrome DevTools socket
        chromeSocketName = ChromeSocketDiscovery.findChromeSocket(deviceSerial);
        if (chromeSocketName == null) {
            throw new IOException("Chrome DevTools socket not found. " +
                "Make sure Chrome is running with debugging enabled.");
        }

        // Setup port forwarding
        forwardedPort = ChromeSocketDiscovery.setupPortForwardAuto(deviceSerial, chromeSocketName);
        if (forwardedPort < 0) {
            throw new IOException("Failed to setup port forwarding to " + chromeSocketName);
        }

        preConnected = true;
        log.info("Pre-connected to {} on port {}", chromeSocketName, forwardedPort);
    }

    /**
     * Called when a Custom Tab is opened.
     *
     * @param url       the URL being opened
     * @param headers   custom headers set by the app
     * @param timestamp when the Custom Tab was launched
     */
    public void onCustomTabOpened(String url, Map<String, String> headers, long timestamp) {
        executor.submit(() -> {
            try {
                captureCustomTabTraffic(url, headers, timestamp);
            } catch (Exception e) {
                log.error("Failed to capture Custom Tab traffic: {}", e.getMessage(), e);
            }
        });
    }

    /**
     * Returns whether the monitor is pre-connected to Chrome.
     */
    public boolean isPreConnected() {
        return preConnected;
    }

    /**
     * Returns the number of active CDP connections.
     */
    public int getActiveConnectionCount() {
        return activeClients.size();
    }

    /**
     * Sets the SidekickClient to use for posting transactions.
     *
     * <p>When set, completed transactions will be automatically posted to the
     * sidekick server, making them appear in the network inspector alongside
     * in-app HTTP requests.</p>
     *
     * @param client the sidekick client (may be null to disable posting)
     */
    public void setSidekickClient(SidekickClient client) {
        this.sidekickClient = client;
    }

    /**
     * Sets the Chrome DevTools client for fetching response bodies.
     *
     * <p>When set, the monitor will fetch response body content for completed
     * requests before posting them to sidekick.</p>
     *
     * @param client the CDP client (may be null to disable body fetching)
     */
    public void setCdpClient(ChromeDevToolsClient client) {
        this.cdpClient = client;
    }

    /**
     * Returns the number of in-flight transactions being tracked.
     */
    public int getInFlightTransactionCount() {
        return inFlightTransactions.size();
    }

    /**
     * Processes a CDP network event directly.
     *
     * <p>Use this method when you have a CDP client and want to feed events
     * through the monitor for correlation and sidekick posting.</p>
     *
     * @param cdpEvent the CDP network event
     * @param customTabUrl the URL that was opened in the Custom Tab
     */
    public void onCdpEvent(CdpNetworkEvent cdpEvent, String customTabUrl) {
        CustomTabNetworkEvent event = convertEvent(cdpEvent, customTabUrl);
        if (event != null) {
            // Notify callback
            if (eventCallback != null) {
                eventCallback.accept(event);
            }
            // Correlate and post to sidekick
            handleNetworkEvent(event);
        }
    }

    @Override
    public void close() {
        // Close all active clients
        for (ChromeDevToolsClient client : activeClients.values()) {
            try {
                client.close();
            } catch (Exception ignored) {}
        }
        activeClients.clear();

        // Remove port forwarding
        if (forwardedPort > 0) {
            ChromeSocketDiscovery.removePortForward(deviceSerial, forwardedPort);
        }

        // Shutdown executor
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}

        preConnected = false;
    }

    private void captureCustomTabTraffic(String url, Map<String, String> headers, long timestamp)
            throws IOException, InterruptedException {

        // Ensure we're connected
        if (!preConnected) {
            preConnect();
        }

        // Create CDP client
        ChromeDevToolsClient client = new ChromeDevToolsClient("localhost", forwardedPort);

        // Find the tab by URL
        CdpTarget target = waitForTab(client, url, TAB_POLL_TIMEOUT);
        if (target == null) {
            log.warn("Could not find Custom Tab for URL: {}", url);
            client.close();
            return;
        }

        log.info("Found Custom Tab: {} ({})", target.title(), target.url());

        // Track this client
        String clientId = target.id();
        activeClients.put(clientId, client);

        // Set up network event listener
        client.setNetworkEventListener(cdpEvent -> {
            CustomTabNetworkEvent event = convertEvent(cdpEvent, url);
            if (event != null) {
                // Callback for individual events
                if (eventCallback != null) {
                    eventCallback.accept(event);
                }

                // Correlate events into transactions
                handleNetworkEvent(event);
            }
        });

        // Attach and enable network
        client.attachToTarget(target);
        client.enableNetwork().join();

        log.info("Network capture enabled for Custom Tab: {}", url);
    }

    private CdpTarget waitForTab(ChromeDevToolsClient client, String url, Duration timeout)
            throws IOException, InterruptedException {

        long startTime = System.currentTimeMillis();
        long timeoutMs = timeout.toMillis();

        // Extract URL base for matching (handles redirects)
        String urlBase = extractUrlBase(url);

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            List<CdpTarget> targets = client.listTargets();

            for (CdpTarget target : targets) {
                if (!target.isPage()) {
                    continue;
                }

                // Check if URL matches (with some flexibility for redirects)
                String targetUrl = target.url();
                if (targetUrl != null && (
                    targetUrl.equals(url) ||
                    targetUrl.startsWith(urlBase) ||
                    url.startsWith(extractUrlBase(targetUrl))
                )) {
                    return target;
                }
            }

            Thread.sleep(TAB_POLL_INTERVAL.toMillis());
        }

        return null;
    }

    private String extractUrlBase(String url) {
        // Extract scheme + host from URL
        try {
            java.net.URI uri = java.net.URI.create(url);
            return uri.getScheme() + "://" + uri.getHost();
        } catch (Exception e) {
            return url;
        }
    }

    private CustomTabNetworkEvent convertEvent(CdpNetworkEvent cdpEvent, String customTabUrl) {
        return switch (cdpEvent) {
            case CdpNetworkEvent.RequestWillBeSent req -> {
                // Check if this is a redirect - if so, complete the previous request first
                if (req.redirectResponse() != null) {
                    var redirect = req.redirectResponse();
                    // Complete the redirected request as a separate transaction
                    InFlightTransaction redirectTx = inFlightTransactions.remove(req.requestId());
                    if (redirectTx == null) {
                        // No existing transaction - this happens when CDP attaches after initial request
                        // Create a synthetic transaction from the redirect response
                        redirectTx = new InFlightTransaction();
                        redirectTx.requestId = req.requestId() + "-redirect";
                        redirectTx.url = redirect.url();
                        redirectTx.method = req.method(); // Original method
                        redirectTx.resourceType = req.type(); // Resource type from new request
                        redirectTx.startTime = req.timestamp(); // Approximate
                        redirectTx.customTabUrl = customTabUrl;
                    }
                    redirectTx.statusCode = redirect.status();
                    redirectTx.statusText = redirect.statusText();
                    redirectTx.responseHeaders = redirect.headers();
                    redirectTx.endTime = req.timestamp();
                    // Post the redirect as completed (no body for redirects)
                    postTransactionToSidekick(redirectTx, null);
                }
                yield new CustomTabNetworkEvent(
                    CustomTabNetworkEvent.Type.REQUEST,
                    req.requestId(),
                    customTabUrl,
                    req.url(),
                    req.method(),
                    null,
                    null,
                    req.headers(),
                    null,
                    req.postData(),
                    req.type(),
                    req.timestamp()
                );
            }

            case CdpNetworkEvent.ResponseReceived resp -> new CustomTabNetworkEvent(
                CustomTabNetworkEvent.Type.RESPONSE,
                resp.requestId(),
                customTabUrl,
                resp.url(),
                null,
                resp.status(),
                resp.statusText(),
                null,
                resp.headers(),
                null,
                null,
                resp.timestamp()
            );

            case CdpNetworkEvent.LoadingFinished fin -> new CustomTabNetworkEvent(
                CustomTabNetworkEvent.Type.FINISHED,
                fin.requestId(),
                customTabUrl,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                fin.timestamp()
            );

            case CdpNetworkEvent.LoadingFailed fail -> new CustomTabNetworkEvent(
                CustomTabNetworkEvent.Type.FAILED,
                fail.requestId(),
                customTabUrl,
                null,
                null,
                null,
                fail.errorText(),
                null,
                null,
                null,
                null,
                fail.timestamp()
            );

            case CdpNetworkEvent.DataReceived data -> null; // Skip data events

            default -> null;
        };
    }

    /**
     * Handles a network event by correlating it with other events for the same request.
     */
    private void handleNetworkEvent(CustomTabNetworkEvent event) {
        String requestId = event.requestId();

        switch (event.type()) {
            case REQUEST -> {
                // Start tracking a new in-flight transaction
                InFlightTransaction tx = new InFlightTransaction();
                tx.requestId = requestId;
                tx.url = event.url();
                tx.method = event.method();
                tx.resourceType = event.resourceType();
                tx.requestHeaders = event.requestHeaders();
                tx.requestBody = event.requestBody();
                tx.startTime = event.timestamp();
                tx.customTabUrl = event.customTabUrl();
                inFlightTransactions.put(requestId, tx);
            }

            case RESPONSE -> {
                // Update with response data
                InFlightTransaction tx = inFlightTransactions.get(requestId);
                if (tx != null) {
                    tx.statusCode = event.statusCode();
                    tx.statusText = event.statusText();
                    tx.responseHeaders = event.responseHeaders();
                    // URL might change due to redirects
                    if (event.url() != null) {
                        tx.url = event.url();
                    }
                }
            }

            case FINISHED -> {
                // Complete the transaction and post to sidekick
                InFlightTransaction tx = inFlightTransactions.remove(requestId);
                if (tx != null) {
                    tx.endTime = event.timestamp();
                    // Try to fetch response body before posting
                    fetchResponseBodyAndPost(tx, requestId, null);
                }
            }

            case FAILED -> {
                // Complete with error - no body fetch for failed requests
                InFlightTransaction tx = inFlightTransactions.remove(requestId);
                if (tx != null) {
                    tx.endTime = event.timestamp();
                    postTransactionToSidekick(tx, event.statusText()); // statusText contains error for FAILED
                }
            }
        }
    }

    /**
     * Fetches response body from CDP and then posts the transaction to sidekick.
     */
    private void fetchResponseBodyAndPost(InFlightTransaction tx, String requestId, String error) {
        ChromeDevToolsClient client = cdpClient;
        if (client == null) {
            // No CDP client available, post without body
            postTransactionToSidekick(tx, error);
            return;
        }

        // Fetch response body asynchronously
        client.getResponseBody(requestId)
            .orTimeout(5, TimeUnit.SECONDS)
            .whenComplete((body, ex) -> {
                if (ex == null && body != null && !body.base64Encoded()) {
                    // Only store text content (non-base64)
                    // Skip binary content and large bodies (>500KB) to avoid JSON issues
                    String content = body.body();
                    if (content != null && content.length() <= 500_000) {
                        tx.responseBody = content;
                        tx.responseBodyBase64 = false;
                    }
                }
                // Post even if body fetch failed or was skipped
                postTransactionToSidekick(tx, error);
            });
    }

    /**
     * Posts a completed transaction to sidekick for storage.
     */
    private void postTransactionToSidekick(InFlightTransaction tx, String error) {
        SidekickClient client = sidekickClient;
        if (client == null) {
            return;
        }

        executor.submit(() -> {
            try {
                Map<String, Object> data = new HashMap<>();
                data.put("url", tx.url);
                data.put("method", tx.method);
                data.put("source", "CustomTab");
                if (tx.resourceType != null) {
                    data.put("resourceType", tx.resourceType);
                }
                data.put("startTime", tx.startTime);

                if (tx.requestHeaders != null && !tx.requestHeaders.isEmpty()) {
                    data.put("requestHeaders", tx.requestHeaders);
                }
                if (tx.requestBody != null) {
                    data.put("requestBody", tx.requestBody);
                }

                if (tx.statusCode != null) {
                    data.put("statusCode", tx.statusCode);
                    data.put("statusMessage", tx.statusText);
                }
                if (tx.responseHeaders != null && !tx.responseHeaders.isEmpty()) {
                    data.put("responseHeaders", tx.responseHeaders);
                }
                if (tx.responseBody != null && !tx.responseBody.isEmpty()) {
                    data.put("responseBody", tx.responseBody);
                    if (tx.responseBodyBase64) {
                        data.put("responseBodyBase64", true);
                    }
                }

                if (tx.endTime > 0 && tx.startTime > 0) {
                    data.put("duration", tx.endTime - tx.startTime);
                }

                if (error != null) {
                    data.put("error", error);
                }

                String json = objectMapper.writeValueAsString(data);
                var result = client.recordTransaction(json);
                if (!result.isSuccess()) {
                    log.error("Failed to post transaction to sidekick: {}", result.description());
                }
            } catch (JsonProcessingException e) {
                log.error("Error serializing transaction: {}", e.getMessage(), e);
            } catch (Exception e) {
                log.error("Error posting transaction to sidekick: {}", e.getMessage(), e);
            }
        });
    }

    /**
     * Tracks an in-flight HTTP transaction being assembled from CDP events.
     */
    private static class InFlightTransaction {
        String requestId;
        String customTabUrl;
        String url;
        String method;
        String resourceType;
        Map<String, String> requestHeaders;
        String requestBody;
        long startTime;

        Integer statusCode;
        String statusText;
        Map<String, String> responseHeaders;
        String responseBody;
        boolean responseBodyBase64;
        long endTime;
    }

    /**
     * Represents a network event from a Custom Tab.
     *
     * @param type            event type
     * @param requestId       CDP request ID
     * @param customTabUrl    the URL that was opened in Custom Tab
     * @param url             the actual request URL
     * @param method          HTTP method (for requests)
     * @param statusCode      HTTP status code (for responses)
     * @param statusText      HTTP status text (for responses) or error text (for failures)
     * @param requestHeaders  request headers
     * @param responseHeaders response headers
     * @param requestBody     request body (POST data)
     * @param resourceType    CDP resource type (Document, XHR, Fetch, Script, Stylesheet, Image, Font, Media, etc.)
     * @param timestamp       event timestamp
     */
    public record CustomTabNetworkEvent(
        Type type,
        String requestId,
        String customTabUrl,
        String url,
        String method,
        Integer statusCode,
        String statusText,
        Map<String, String> requestHeaders,
        Map<String, String> responseHeaders,
        String requestBody,
        String resourceType,
        long timestamp
    ) {
        public enum Type {
            REQUEST,
            RESPONSE,
            FINISHED,
            FAILED
        }

        /**
         * Returns the source identifier for this event.
         */
        public String source() {
            return "customtab";
        }
    }
}
