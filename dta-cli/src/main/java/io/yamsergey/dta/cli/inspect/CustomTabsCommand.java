package io.yamsergey.dta.cli.inspect;

import io.yamsergey.dta.mcp.DaemonClient;
import io.yamsergey.dta.mcp.DaemonLauncher;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.Callable;

@Command(name = "customtabs",
         description = "Manage Chrome Custom Tab CDP watching (requires dta-sidekick in app).")
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

    @Option(names = {"--watch"},
            description = "Start CDP watcher for Custom Tabs network traffic.")
    private boolean watchMode;

    @Option(names = {"--stop"},
            description = "Stop CDP watcher.")
    private boolean stopWatch;

    @Option(names = {"--status"},
            description = "Check CDP watcher status.")
    private boolean checkStatus;

    @Override
    public Integer call() throws Exception {
        try {
            DaemonClient daemon = DaemonLauncher.ensureDaemonRunning();
            String result;

            if (watchMode) {
                System.err.println("Starting CDP watcher for Custom Tabs...");
                result = daemon.cdpWatchStart(packageName, deviceSerial);
            } else if (stopWatch) {
                System.err.println("Stopping CDP watcher...");
                result = daemon.cdpWatchStop(packageName, deviceSerial);
            } else if (checkStatus) {
                result = daemon.cdpWatchStatus(packageName, deviceSerial);
            } else {
                // Default: show status
                result = daemon.cdpWatchStatus(packageName, deviceSerial);
            }

            if (outputPath != null && !outputPath.isEmpty()) {
                File outputFile = new File(outputPath);
                File outputDir = outputFile.getParentFile();
                if (outputDir != null && !outputDir.exists()) outputDir.mkdirs();
                Files.writeString(outputFile.toPath(), result);
                System.err.println("Output saved to " + outputFile.getAbsolutePath());
            } else {
                System.out.println(result);
            }
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }
}
