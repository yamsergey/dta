package io.yamsergey.dta.cli.inspect;

import io.yamsergey.dta.cli.util.VersionChecker;
import io.yamsergey.dta.tools.android.cdp.CdpTarget;
import io.yamsergey.dta.tools.android.cdp.ChromeDevToolsClient;
import io.yamsergey.dta.tools.android.cdp.CustomTabsNetworkMonitor;
import io.yamsergey.dta.tools.android.inspect.compose.SidekickConnectionManager;
import io.yamsergey.dta.tools.android.inspect.compose.SidekickConnectionManager.ConnectionInfo;
import io.yamsergey.dta.tools.sugar.Failure;
import io.yamsergey.dta.tools.sugar.Result;
import io.yamsergey.dta.tools.sugar.Success;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * CLI command for inspecting Chrome Custom Tabs events from an Android application.
 *
 * <p>This command lists Custom Tab launch events captured by the sidekick library.
 * These events indicate when the app opened a URL in Chrome Custom Tabs.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * # List all Custom Tab events
 * dta-cli inspect customtabs com.example.app
 *
 * # Clear Custom Tab events
 * dta-cli inspect customtabs com.example.app --clear
 *
 * # Save to file
 * dta-cli inspect customtabs com.example.app -o events.json
 * </pre>
 *
 * <h3>Custom Tab Events</h3>
 * <p>Each event contains:</p>
 * <ul>
 *   <li><b>id</b> - Unique event ID</li>
 *   <li><b>url</b> - URL that was opened in Chrome Custom Tab</li>
 *   <li><b>headers</b> - Custom headers set by the app</li>
 *   <li><b>timestamp</b> - When the Custom Tab was launched</li>
 *   <li><b>packageName</b> - App package name</li>
 * </ul>
 *
 * <h3>Note on Network Capture</h3>
 * <p>Custom Tab events only capture the <i>intent</i> to open a URL - not the actual
 * HTTP traffic. To capture actual network requests from Custom Tabs, use the
 * inspector-web with Chrome DevTools Protocol support enabled.</p>
 */
@Command(name = "customtabs",
         description = "List Chrome Custom Tab events from Android application (requires dta-sidekick in app).")
public class CustomTabsCommand implements Callable<Integer> {

    @Parameters(index = "0",
                description = "Package name of the target application (e.g., com.example.app)")
    private String packageName;

    @Option(names = {"-o", "--output"},
            description = "Output file path. If not specified, prints to stdout.")
    private String outputPath;

    @Option(names = {"-d", "--device"},
            description = "Device serial number. If not specified, uses first available device.")
    private String deviceSerial;

    @Option(names = {"--clear"},
            description = "Clear all captured Custom Tab events.")
    private boolean clearEvents;

    @Option(names = {"--watch"},
            description = "Watch mode: monitor Custom Tab network traffic via Chrome DevTools Protocol (CDP).")
    private boolean watchMode;

    @Option(names = {"--cdp-port"},
            defaultValue = "9222",
            description = "Local port for Chrome DevTools Protocol (default: 9222).")
    private int cdpPort;

    @Override
    public Integer call() throws Exception {
        if (watchMode) {
            return runWatchMode();
        }

        // Get connection via shared manager
        ConnectionInfo conn;
        try {
            conn = SidekickConnectionManager.getInstance().getConnection(packageName, deviceSerial);
        } catch (Exception e) {
            printConnectionError();
            return 1;
        }
        var client = conn.client();

        // Check version compatibility
        VersionChecker.checkAndWarnFromConn(conn, System.err);

        // Determine operation
        Result<String> dataResult;
        String operationType;

        if (clearEvents) {
            System.err.println("Clearing Custom Tab events...");
            dataResult = client.clearCustomTabEvents();
            operationType = "clear";
        } else {
            System.err.println("Fetching Custom Tab events...");
            dataResult = client.getCustomTabEvents();
            operationType = "events";
        }

        if (dataResult instanceof Failure<String> failure) {
            System.err.println("Error: " + failure.description());
            return 1;
        }

        String outputContent = ((Success<String>) dataResult).value();
        outputContent = prettyPrintJson(outputContent);

        // Output to file or stdout
        if (outputPath != null && !outputPath.isEmpty()) {
            File outputFile = new File(outputPath);

            File outputDir = outputFile.getParentFile();
            if (outputDir != null && !outputDir.exists()) {
                if (!outputDir.mkdirs()) {
                    System.err.println("Error: Failed to create output directory: " + outputDir.getAbsolutePath());
                    return 1;
                }
            }

            Files.writeString(outputFile.toPath(), outputContent);
            System.err.println("Success: Custom Tab " + operationType + " saved");
            System.err.println("Output: " + outputFile.getAbsolutePath());
        } else {
            System.out.println(outputContent);
        }

        return 0;
    }

    /**
     * Runs in watch mode: monitors Custom Tab network traffic via CDP.
     * Automatically detects and attaches to Custom Tabs when they open.
     */
    private Integer runWatchMode() throws Exception {
        System.err.println("=== Custom Tabs Network Monitor (CDP) ===");
        System.err.println();

        // Get connection via shared manager
        ConnectionInfo conn;
        try {
            conn = SidekickConnectionManager.getInstance().getConnection(packageName, deviceSerial);
        } catch (Exception e) {
            printConnectionError();
            return 1;
        }
        var sidekickClient = conn.client();
        System.err.println("Sidekick: OK");

        // Set up port forwarding to Chrome DevTools
        System.err.println("Setting up port forwarding to Chrome DevTools...");
        SidekickConnectionManager.getInstance().setupCdpPortForward(deviceSerial, cdpPort);

        System.err.println();
        System.err.println("=== Watching for Custom Tabs (Ctrl+C to stop) ===");
        System.err.println("Open a Custom Tab in your app to start capturing traffic.");
        System.err.println();

        // Track currently attached tab
        final String[] currentTabId = {null};
        final ChromeDevToolsClient[] currentClient = {null};

        // Create network monitor
        CustomTabsNetworkMonitor monitor = new CustomTabsNetworkMonitor(
            deviceSerial,
            event -> {
                if (event != null) {
                    String type = event.type().name();
                    System.out.println("[CDP] " + type + ": " + event.requestId());
                    if (event.url() != null) {
                        System.out.println("       URL: " + event.url());
                    }
                    if (event.method() != null) {
                        System.out.println("       Method: " + event.method());
                    }
                    if (event.statusCode() != null) {
                        System.out.println("       Status: " + event.statusCode() + " " + event.statusText());
                    }
                }
            }
        );
        monitor.setSidekickClient(sidekickClient);

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("\nStopping monitor...");
            if (currentClient[0] != null) {
                try {
                    currentClient[0].close();
                } catch (Exception ignored) {}
            }
        }));

        // Poll for Custom Tabs
        while (!Thread.currentThread().isInterrupted()) {
            try {
                CdpTarget tab = findCustomTab();

                if (tab != null && !tab.id().equals(currentTabId[0])) {
                    // New tab detected - attach to it
                    if (currentClient[0] != null) {
                        try {
                            currentClient[0].close();
                        } catch (Exception ignored) {}
                    }

                    System.err.println();
                    System.err.println(">>> Custom Tab detected: " + tab.title());
                    System.err.println("    URL: " + tab.url());

                    ChromeDevToolsClient cdpClient = new ChromeDevToolsClient("localhost", cdpPort);
                    cdpClient.attachToTarget(tab);
                    monitor.setCdpClient(cdpClient);

                    final String tabUrl = tab.url();
                    cdpClient.setNetworkEventListener(cdpEvent -> {
                        monitor.onCdpEvent(cdpEvent, tabUrl);
                    });

                    cdpClient.enableNetwork().join();

                    currentTabId[0] = tab.id();
                    currentClient[0] = cdpClient;

                    System.err.println("    Capturing network traffic...");
                    System.err.println();
                } else if (tab == null && currentTabId[0] != null) {
                    // Tab closed
                    System.err.println();
                    System.err.println(">>> Custom Tab closed");
                    System.err.println("    Waiting for new Custom Tab...");
                    System.err.println();

                    if (currentClient[0] != null) {
                        try {
                            currentClient[0].close();
                        } catch (Exception ignored) {}
                    }
                    currentTabId[0] = null;
                    currentClient[0] = null;
                    monitor.setCdpClient(null);
                }

                Thread.sleep(500); // Poll every 500ms
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                // Connection errors are expected when Chrome isn't running
                if (currentTabId[0] != null) {
                    currentTabId[0] = null;
                    currentClient[0] = null;
                    monitor.setCdpClient(null);
                }
                Thread.sleep(1000); // Wait longer on errors
            }
        }

        return 0;
    }

    /**
     * Finds the first Custom Tab page target.
     */
    private CdpTarget findCustomTab() {
        try {
            ChromeDevToolsClient client = new ChromeDevToolsClient("localhost", cdpPort);
            List<CdpTarget> targets = client.listTargets();
            return targets.stream()
                    .filter(CdpTarget::isPage)
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private void printConnectionError() {
        System.err.println("Error: Cannot connect to sidekick server.");
        System.err.println("Make sure:");
        System.err.println("  1. The app " + packageName + " is running");
        System.err.println("  2. The app includes the dta-sidekick debug dependency");
        System.err.println();
        System.err.println("To add sidekick to your app, add this to app/build.gradle:");
        System.err.println("  debugImplementation 'com.github.yamsergey.yamsergey.dta:dta-sidekick:1.0.8'");
    }

    private String prettyPrintJson(String json) {
        StringBuilder sb = new StringBuilder();
        int indent = 0;
        boolean inString = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);

            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inString = !inString;
                sb.append(c);
            } else if (!inString) {
                switch (c) {
                    case '{', '[' -> {
                        sb.append(c);
                        sb.append('\n');
                        indent++;
                        sb.append("  ".repeat(indent));
                    }
                    case '}', ']' -> {
                        sb.append('\n');
                        indent--;
                        sb.append("  ".repeat(indent));
                        sb.append(c);
                    }
                    case ',' -> {
                        sb.append(c);
                        sb.append('\n');
                        sb.append("  ".repeat(indent));
                    }
                    case ':' -> sb.append(": ");
                    case ' ', '\n', '\r', '\t' -> {
                        // Skip whitespace
                    }
                    default -> sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }

        return sb.toString();
    }
}
