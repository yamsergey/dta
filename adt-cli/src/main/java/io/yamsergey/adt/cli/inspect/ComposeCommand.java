package io.yamsergey.adt.cli.inspect;

import io.yamsergey.adt.tools.android.inspect.compose.SidekickClient;
import io.yamsergey.adt.tools.sugar.Failure;
import io.yamsergey.adt.tools.sugar.Result;
import io.yamsergey.adt.tools.sugar.Success;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.Callable;

/**
 * CLI command for dumping the Compose UI hierarchy from an Android application.
 *
 * <p>This command captures the Compose composable tree from a connected Android device
 * using the ADT Sidekick library. The target app must include the sidekick dependency
 * in debug builds.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * # Basic usage - unified tree (recommended)
 * adt-cli inspect compose io.yamsergey.example.compose.layout.example --tree
 *
 * # Raw hierarchy (layout nodes only)
 * adt-cli inspect compose io.yamsergey.example.compose.layout.example
 *
 * # Save to file
 * adt-cli inspect compose io.yamsergey.example.compose.layout.example \
 *     --tree -o compose-tree.json
 *
 * # Get semantics tree instead of layout
 * adt-cli inspect compose io.yamsergey.example.compose.layout.example \
 *     --semantics -o semantics.json
 *
 * # Specify device
 * adt-cli inspect compose io.yamsergey.example.compose.layout.example \
 *     -d emulator-5554 --tree -o tree.json
 * </pre>
 */
@Command(name = "compose",
         description = "Dump Compose UI hierarchy from Android application (requires adt-sidekick in app).")
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

    @Option(names = {"--semantics"},
            description = "Capture semantics tree (accessibility-focused).")
    private boolean captureSemantics;

    @Option(names = {"--tree"},
            description = "Capture unified tree with composable names, bounds, and semantic info (recommended).")
    private boolean captureTree;

    @Option(names = {"--timeout"},
            defaultValue = "30",
            description = "Timeout in seconds for operations (default: 30).")
    private int timeoutSeconds;

    @Override
    public Integer call() throws Exception {
        // Build the client
        SidekickClient client = SidekickClient.builder()
                .port(port)
                .adbPath(adbPath)
                .deviceSerial(deviceSerial)
                .timeoutMs(timeoutSeconds * 1000)
                .build();

        try {
            // Set up port forwarding
            System.err.println("Setting up port forwarding on port " + port + "...");
            Result<Void> forwardResult = client.setupPortForwarding();
            if (forwardResult instanceof Failure<Void> failure) {
                System.err.println("Error: " + failure.description());
                return 1;
            }

            // Check health first
            System.err.println("Checking sidekick server...");
            Result<String> healthResult = client.checkHealth();
            if (healthResult instanceof Failure<String> failure) {
                System.err.println("Error: Cannot connect to sidekick server.");
                System.err.println("Make sure:");
                System.err.println("  1. The app " + packageName + " is running");
                System.err.println("  2. The app includes the adt-sidekick debug dependency");
                System.err.println("  3. No firewall is blocking port " + port);
                System.err.println();
                System.err.println("To add sidekick to your app, add this to app/build.gradle:");
                System.err.println("  debugImplementation(\"io.yamsergey.adt:sidekick:1.0.0\")");
                return 1;
            }

            // Determine capture mode
            String captureMode = captureTree ? "tree" : (captureSemantics ? "semantics" : "hierarchy");
            System.err.println("Capturing " + captureMode + "...");

            Result<String> dataResult;
            if (captureTree) {
                dataResult = client.getComposeTree();
            } else if (captureSemantics) {
                dataResult = client.getComposeSemantics();
            } else {
                dataResult = client.getComposeHierarchy();
            }

            if (dataResult instanceof Failure<String> failure) {
                System.err.println("Error: " + failure.description());
                return 1;
            }

            String outputContent = ((Success<String>) dataResult).value();

            // Pretty-print the JSON
            outputContent = prettyPrintJson(outputContent);

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
                System.err.println("Success: Compose " + captureMode + " captured");
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
