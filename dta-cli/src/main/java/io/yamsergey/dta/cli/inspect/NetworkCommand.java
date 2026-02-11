package io.yamsergey.dta.cli.inspect;

import io.yamsergey.dta.cli.util.VersionChecker;
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
import java.util.concurrent.Callable;

/**
 * CLI command for inspecting HTTP network traffic from an Android application.
 *
 * <p>This command captures HTTP request/response data from a connected Android device
 * using the ADT Sidekick library. The target app must include the sidekick dependency
 * in debug builds.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * # List all captured requests
 * dta-cli inspect network com.example.app
 *
 * # Get specific request details
 * dta-cli inspect network com.example.app --request-id abc123
 *
 * # Get response body for a request
 * dta-cli inspect network com.example.app --request-id abc123 --body
 *
 * # Get network statistics
 * dta-cli inspect network com.example.app --stats
 *
 * # Clear captured requests
 * dta-cli inspect network com.example.app --clear
 *
 * # Save to file
 * dta-cli inspect network com.example.app -o requests.json
 * </pre>
 */
@Command(name = "network",
         description = "Inspect HTTP network traffic from Android application (requires dta-sidekick in app).")
public class NetworkCommand implements Callable<Integer> {

    @Parameters(index = "0",
                description = "Package name of the target application (e.g., com.example.app)")
    private String packageName;

    @Option(names = {"-o", "--output"},
            description = "Output file path. If not specified, prints to stdout.")
    private String outputPath;

    @Option(names = {"-d", "--device"},
            description = "Device serial number. If not specified, uses first available device.")
    private String deviceSerial;

    @Option(names = {"--request-id", "-r"},
            description = "Get details for a specific request by ID.")
    private String requestId;

    @Option(names = {"--body", "-b"},
            description = "Get response body for the request specified by --request-id.")
    private boolean getBody;

    @Option(names = {"--stats"},
            description = "Show network statistics instead of requests.")
    private boolean showStats;

    @Option(names = {"--clear"},
            description = "Clear all captured requests.")
    private boolean clearRequests;

    @Override
    public Integer call() throws Exception {
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

        if (clearRequests) {
            System.err.println("Clearing network requests...");
            dataResult = client.clearNetworkRequests();
            operationType = "clear";
        } else if (showStats) {
            System.err.println("Fetching network statistics...");
            dataResult = client.getNetworkStats();
            operationType = "stats";
        } else if (getBody) {
            if (requestId == null || requestId.isEmpty()) {
                System.err.println("Error: --body requires --request-id to be specified");
                return 1;
            }
            System.err.println("Fetching body for request " + requestId + "...");
            dataResult = client.getNetworkRequestBody(requestId);
            operationType = "body";
        } else if (requestId != null && !requestId.isEmpty()) {
            System.err.println("Fetching request " + requestId + "...");
            dataResult = client.getNetworkRequest(requestId);
            operationType = "request";
        } else {
            System.err.println("Fetching network requests...");
            dataResult = client.getNetworkRequests();
            operationType = "requests";
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
            System.err.println("Success: Network " + operationType + " saved");
            System.err.println("Output: " + outputFile.getAbsolutePath());
        } else {
            System.out.println(outputContent);
        }

        return 0;
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
