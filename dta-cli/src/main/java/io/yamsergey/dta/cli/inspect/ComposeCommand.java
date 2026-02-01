package io.yamsergey.dta.cli.inspect;

import io.yamsergey.dta.cli.util.VersionChecker;
import io.yamsergey.dta.tools.android.inspect.compose.ComposeNodeFilter;
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
 * CLI command for dumping the Compose UI hierarchy from an Android application.
 *
 * <p>This command captures the Compose UI tree from a connected Android device
 * using the ADT Sidekick library. The target app must include the sidekick dependency
 * in debug builds.</p>
 *
 * <p>Returns a unified tree with composable names, bounds, text, and semantic info.
 * Use filters to find specific elements (recommended to reduce output size).</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * # Get full UI tree
 * dta-cli inspect compose com.example.app
 *
 * # Filter by text content (recommended)
 * dta-cli inspect compose com.example.app --text "Login"
 *
 * # Filter by composable type
 * dta-cli inspect compose com.example.app --type Button
 *
 * # Combine filters
 * dta-cli inspect compose com.example.app --text "Submit" --type Button
 *
 * # Save to file
 * dta-cli inspect compose com.example.app -o tree.json
 *
 * # Specify device
 * dta-cli inspect compose com.example.app -d emulator-5554
 * </pre>
 */
@Command(name = "compose",
         description = "Dump Compose UI hierarchy from Android application (requires dta-sidekick in app).")
public class ComposeCommand implements Callable<Integer> {

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

    // Filter options - aligned with MCP approach
    @Option(names = {"--text", "-t"},
            description = "Filter by text content (case-insensitive substring match).")
    private String textFilter;

    @Option(names = {"--type"},
            description = "Filter by composable type (e.g., 'Button', 'Text').")
    private String typeFilter;

    @Option(names = {"--role"},
            description = "Filter by semantic role (e.g., 'Button', 'Image', 'Checkbox').")
    private String roleFilter;

    @Option(names = {"--test-tag"},
            description = "Filter by test tag.")
    private String testTagFilter;

    @Option(names = {"--include-parents"},
            description = "Include parent chain for each matching node.")
    private boolean includeParents;

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

            // Fetch compose tree
            System.err.println("Fetching compose tree...");
            Result<String> dataResult = client.getComposeTree();

            if (dataResult instanceof Failure<String> failure) {
                System.err.println("Error: " + failure.description());
                return 1;
            }

            String outputContent = ((Success<String>) dataResult).value();

            // Build filter if any filter options specified
            ComposeNodeFilter filter = ComposeNodeFilter.builder()
                    .composablePattern(typeFilter)
                    .textPattern(textFilter)
                    .rolePattern(roleFilter)
                    .testTagPattern(testTagFilter)
                    .includeParents(includeParents)
                    .build();

            // Apply filter if any filters are specified
            if (filter.hasFilters()) {
                try {
                    outputContent = filter.filter(outputContent);
                    // Count results
                    int count = outputContent.split("\"node\"").length - 1;
                    System.err.println("Filter matched " + count + " node(s)");
                } catch (Exception e) {
                    System.err.println("Warning: Filter failed, returning unfiltered output: " + e.getMessage());
                    outputContent = prettyPrintJson(outputContent);
                }
            } else {
                // Pretty-print the JSON
                outputContent = prettyPrintJson(outputContent);
            }

            // Output to file or stdout
            if (outputPath != null && !outputPath.isEmpty()) {
                File outputFile = new File(outputPath);

                // Create output directory if needed
                File outputDir = outputFile.getParentFile();
                if (outputDir != null && !outputDir.exists()) {
                    if (!outputDir.mkdirs()) {
                        System.err.println("Error: Failed to create output directory: " + outputDir.getAbsolutePath());
                        return 1;
                    }
                }

                Files.writeString(outputFile.toPath(), outputContent);
                System.err.println("Success: Compose tree captured");
                System.err.println("Output: " + outputFile.getAbsolutePath());
            } else {
                // Print to stdout
                System.out.println(outputContent);
            }

            return 0;

        } finally {
            // Clean up port forwarding
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

    /**
     * Pretty-prints JSON with indentation.
     */
    private String prettyPrintJson(String json) {
        // Simple pretty-printing without external dependencies
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
