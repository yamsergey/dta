package io.yamsergey.dta.tools.android.cdp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Manages CDP connections to WebView debug sockets for accessibility tree inspection.
 *
 * <p>Discovers WebView debug sockets via ADB, port-forwards them,
 * connects CDP clients, and fetches accessibility trees for each page target.
 * Results are keyed by target URL for matching to WebView nodes in the layout tree.</p>
 *
 * <p>This class is designed for short-lived, per-request usage in the
 * layout tree enrichment flow. Call {@link #close()} after use to clean up
 * port forwards and connections.</p>
 */
public class WebViewCdpManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WebViewCdpManager.class);

    private final String deviceSerial;
    private final List<PortForwardEntry> portForwards = new ArrayList<>();
    private final List<ChromeDevToolsClient> clients = new ArrayList<>();

    /**
     * Result of fetching WebView accessibility trees.
     * Includes viewport dimensions for CSS→screen coordinate conversion.
     */
    public record WebViewTreeResult(
        String url,
        List<Map<String, Object>> children,
        double viewportWidth,
        double viewportHeight
    ) {}

    private record PortForwardEntry(int localPort, String socketName) {}

    public WebViewCdpManager(String deviceSerial) {
        this.deviceSerial = deviceSerial;
    }

    /**
     * Discovers all WebView debug sockets, connects to each, and fetches
     * accessibility trees from their page targets.
     *
     * @return list of results, each containing a URL and its accessibility children
     */
    public List<WebViewTreeResult> fetchWebViewTrees() {
        List<WebViewTreeResult> results = new ArrayList<>();

        try {
            List<String> sockets = ChromeSocketDiscovery.findWebViewSockets(deviceSerial);
            if (sockets.isEmpty()) {
                log.debug("No WebView debug sockets found");
                return results;
            }

            log.debug("Found {} WebView debug socket(s)", sockets.size());

            for (String socketName : sockets) {
                try {
                    fetchTreesFromSocket(socketName, results);
                } catch (Exception e) {
                    log.debug("Failed to fetch trees from socket {}: {}", socketName, e.getMessage());
                }
            }

        } catch (Exception e) {
            log.warn("Failed to discover WebView sockets: {}", e.getMessage());
        }

        return results;
    }

    private void fetchTreesFromSocket(String socketName, List<WebViewTreeResult> results)
            throws Exception {

        // Set up port forwarding
        int localPort = ChromeSocketDiscovery.setupPortForwardAuto(deviceSerial, socketName);
        if (localPort < 0) {
            log.debug("Failed to set up port forward for {}", socketName);
            return;
        }
        portForwards.add(new PortForwardEntry(localPort, socketName));

        // Create CDP client and list targets
        ChromeDevToolsClient client = new ChromeDevToolsClient("localhost", localPort);
        clients.add(client);

        List<CdpTarget> targets = client.listTargets();

        for (CdpTarget target : targets) {
            if (!target.isPage()) continue;

            try {
                // Create a separate client per target (each needs its own WebSocket)
                ChromeDevToolsClient targetClient = new ChromeDevToolsClient("localhost", localPort);
                clients.add(targetClient);

                targetClient.attachToTarget(target);

                CdpAccessibilityInspector.AccessibilityTreeResult treeResult =
                    CdpAccessibilityInspector.fetchAccessibilityTreeWithUrl(targetClient);

                if (!treeResult.nodes().isEmpty()) {
                    String url = treeResult.url() != null ? treeResult.url() : target.url();
                    results.add(new WebViewTreeResult(
                        url, treeResult.nodes(),
                        treeResult.viewportWidth(), treeResult.viewportHeight()));
                    log.debug("Fetched accessibility tree for WebView target: {} ({} nodes)",
                        url, treeResult.nodes().size());
                }

            } catch (Exception e) {
                log.debug("Failed to fetch accessibility tree for target {}: {}",
                    target.url(), e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        // Close all CDP clients
        for (ChromeDevToolsClient client : clients) {
            try {
                client.close();
            } catch (Exception ignored) {}
        }
        clients.clear();

        // Remove port forwards
        for (PortForwardEntry entry : portForwards) {
            ChromeSocketDiscovery.removePortForward(deviceSerial, entry.localPort());
        }
        portForwards.clear();
    }
}
