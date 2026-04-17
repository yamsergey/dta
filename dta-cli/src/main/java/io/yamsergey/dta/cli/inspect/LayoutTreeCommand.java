package io.yamsergey.dta.cli.inspect;

import io.yamsergey.dta.daemon.DaemonClient;
import io.yamsergey.dta.daemon.DaemonLauncher;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.Callable;

/**
 * CLI command for dumping the unified layout hierarchy (View + Compose) from an Android app.
 *
 * <p>Uses the dta-sidekick in-process agent to capture rich view properties and
 * a unified tree combining both traditional Android Views and Jetpack Compose nodes.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * # Dump unified layout tree to stdout
 * dta-cli inspect layout-tree com.example.app
 *
 * # Save to file
 * dta-cli inspect layout-tree com.example.app -o layout.json
 *
 * # Filter by text
 * dta-cli inspect layout-tree com.example.app --text "Login"
 *
 * # Filter by type
 * dta-cli inspect layout-tree com.example.app --type Button
 * </pre>
 */
@Command(name = "layout-tree",
         description = "Dump unified layout hierarchy (View + Compose) from Android app (requires dta-sidekick).")
public class LayoutTreeCommand implements Callable<Integer> {

    @Parameters(index = "0",
                description = "Package name of the target application (e.g., com.example.app)")
    private String packageName;

    @Option(names = {"-o", "--output"},
            description = "Output file path. If not specified, prints to stdout.")
    private String outputPath;

    @Option(names = {"-d", "--device"},
            description = "Device serial number. If not specified, uses first available device.")
    private String deviceSerial;

    @Option(names = {"--text", "-t"},
            description = "Filter by text content (case-insensitive substring match).")
    private String textFilter;

    @Option(names = {"--type"},
            description = "Filter by type (e.g., 'Button', 'TextView', 'LinearLayout', 'Scaffold').")
    private String typeFilter;

    @Option(names = {"--resource-id"},
            description = "Filter by resource ID (e.g., 'com.example:id/button').")
    private String resourceIdFilter;

    @Option(names = {"--view-id"},
            description = "Get subtree rooted at specific view drawing ID.")
    private String viewIdFilter;

    @Override
    public Integer call() throws Exception {
        try {
            DaemonClient daemon = DaemonLauncher.ensureDaemonRunning();
            System.err.println("Fetching unified layout tree...");
            String json = daemon.layoutTree(packageName, deviceSerial,
                textFilter, typeFilter, resourceIdFilter, viewIdFilter);

            if (outputPath != null && !outputPath.isEmpty()) {
                File outputFile = new File(outputPath);
                File outputDir = outputFile.getParentFile();
                if (outputDir != null && !outputDir.exists()) {
                    if (!outputDir.mkdirs()) {
                        System.err.println("Error: Failed to create output directory: " + outputDir.getAbsolutePath());
                        return 1;
                    }
                }
                Files.writeString(outputFile.toPath(), json);
                System.err.println("Success: Layout tree captured");
                System.err.println("Output: " + outputFile.getAbsolutePath());
            } else {
                System.out.println(json);
            }
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }
}
