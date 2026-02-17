package io.yamsergey.dta.cli.inspect;

import io.yamsergey.dta.mcp.DaemonClient;
import io.yamsergey.dta.mcp.DaemonLauncher;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.Callable;

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

    @Option(names = {"--text", "-t"},
            description = "Filter by text content (case-insensitive substring match).")
    private String textFilter;

    @Option(names = {"--type"},
            description = "Filter by composable type (e.g., 'Button', 'Text').")
    private String typeFilter;

    @Override
    public Integer call() throws Exception {
        try {
            DaemonClient daemon = DaemonLauncher.ensureDaemonRunning();
            System.err.println("Fetching compose tree...");
            String json = daemon.composeTree(packageName, deviceSerial, textFilter, typeFilter);

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
                System.err.println("Success: Compose tree captured");
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
