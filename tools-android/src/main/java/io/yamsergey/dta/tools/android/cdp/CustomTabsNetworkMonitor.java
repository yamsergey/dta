package io.yamsergey.dta.tools.android.cdp;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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

    private static final Duration TAB_POLL_INTERVAL = Duration.ofMillis(200);
    private static final Duration TAB_POLL_TIMEOUT = Duration.ofSeconds(10);
    private static final int DEFAULT_CDP_PORT = 9222;

    private final String deviceSerial;
    private final Consumer<CustomTabNetworkEvent> eventCallback;
    private final ScheduledExecutorService executor;
    private final Map<String, ChromeDevToolsClient> activeClients;

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
        System.out.println("CustomTabsNetworkMonitor pre-connected to " +
            chromeSocketName + " on port " + forwardedPort);
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
                System.err.println("Failed to capture Custom Tab traffic: " + e.getMessage());
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
            System.err.println("Could not find Custom Tab for URL: " + url);
            client.close();
            return;
        }

        System.out.println("Found Custom Tab: " + target.title() + " (" + target.url() + ")");

        // Track this client
        String clientId = target.id();
        activeClients.put(clientId, client);

        // Set up network event listener
        client.setNetworkEventListener(cdpEvent -> {
            CustomTabNetworkEvent event = convertEvent(cdpEvent, url);
            if (eventCallback != null) {
                eventCallback.accept(event);
            }
        });

        // Attach and enable network
        client.attachToTarget(target);
        client.enableNetwork().join();

        System.out.println("Network capture enabled for Custom Tab: " + url);
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
            case CdpNetworkEvent.RequestWillBeSent req -> new CustomTabNetworkEvent(
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
                req.timestamp()
            );

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
                fail.timestamp()
            );

            case CdpNetworkEvent.DataReceived data -> null; // Skip data events

            default -> null;
        };
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
