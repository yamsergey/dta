package io.yamsergey.dta.cli.inspect;

import io.yamsergey.dta.cli.util.VersionChecker;
import io.yamsergey.dta.tools.android.inspect.compose.SidekickClient;
import io.yamsergey.dta.tools.sugar.Failure;
import io.yamsergey.dta.tools.sugar.Result;
import io.yamsergey.dta.tools.sugar.Success;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.Callable;

/**
 * CLI command for inspecting WebSocket connections from an Android application.
 *
 * <p>This command captures WebSocket connection and message data from a connected Android device
 * using the ADT Sidekick library. The target app must include the sidekick dependency
 * in debug builds.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * # List all WebSocket connections
 * dta-cli inspect websocket com.example.app
 *
 * # Get specific connection with messages
 * dta-cli inspect websocket com.example.app --connection-id abc123
 *
 * # Clear captured connections
 * dta-cli inspect websocket com.example.app --clear
 *
 * # Save to file
 * dta-cli inspect websocket com.example.app -o connections.json
 * </pre>
 */
@Command(name = "websocket",
         description = "Inspect WebSocket connections from Android application (requires dta-sidekick in app).")
public class WebSocketCommand implements Callable<Integer> {

    @Parameters(index = "0",
                description = "Package name of the target application (e.g., com.example.app)")
    private String packageName;

    @Option(names = {"-o", "--output"},
            description = "Output file path. If not specified, prints to stdout.")
    private String outputPath;

    @Option(names = {"-d", "--device"},
            description = "Device serial number. If not specified, uses first available device.")
    private String deviceSerial;

    @Option(names = {"--port"},
            defaultValue = "8642",
            description = "Port for sidekick server (default: 8642).")
    private int port;

    @Option(names = {"--adb-path"},
            defaultValue = "adb",
            description = "Path to ADB executable. If not specified, uses 'adb' from PATH.")
    private String adbPath;

    @Option(names = {"--timeout"},
            defaultValue = "30",
            description = "Timeout in seconds for operations (default: 30).")
    private int timeoutSeconds;

    @Option(names = {"--connection-id", "-c"},
            description = "Get details for a specific connection by ID (includes messages).")
    private String connectionId;

    @Option(names = {"--clear"},
            description = "Clear all captured connections.")
    private boolean clearConnections;

    @Override
    public Integer call() throws Exception {
        // Build the client
        SidekickClient client = SidekickClient.builder()
                .packageName(packageName)
                .port(port)
                .adbPath(adbPath)
                .deviceSerial(deviceSerial)
                .timeoutMs(timeoutSeconds * 1000)
                .build();

        try {
            // Set up port forwarding
            System.err.println("Setting up port forwarding to " + client.getSocketName() + "...");
            Result<Void> forwardResult = client.setupPortForwarding();
            if (forwardResult instanceof Failure<Void> failure) {
                System.err.println("Error: " + failure.description());
                return 1;
            }

            // Check health first
            System.err.println("Checking sidekick server...");
            Result<String> healthResult = client.checkHealth();
            if (healthResult instanceof Failure<String> failure) {
                printConnectionError();
                return 1;
            }

            // Check version compatibility
            VersionChecker.checkAndWarn(client, System.err);

            // Determine operation
            Result<String> dataResult;
            String operationType;

            if (clearConnections) {
                System.err.println("Clearing WebSocket connections...");
                dataResult = client.clearWebSocketConnections();
                operationType = "clear";
            } else if (connectionId != null && !connectionId.isEmpty()) {
                System.err.println("Fetching connection " + connectionId + "...");
                dataResult = client.getWebSocketConnection(connectionId);
                operationType = "connection";
            } else {
                System.err.println("Fetching WebSocket connections...");
                dataResult = client.getWebSocketConnections();
                operationType = "connections";
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
                System.err.println("Success: WebSocket " + operationType + " saved");
                System.err.println("Output: " + outputFile.getAbsolutePath());
            } else {
                System.out.println(outputContent);
            }

            return 0;

        } finally {
            client.removePortForwarding();
        }
    }

    private void printConnectionError() {
        System.err.println("Error: Cannot connect to sidekick server.");
        System.err.println("Make sure:");
        System.err.println("  1. The app " + packageName + " is running");
        System.err.println("  2. The app includes the dta-sidekick debug dependency");
        System.err.println("  3. No firewall is blocking port " + port);
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
