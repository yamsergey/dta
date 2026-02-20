package io.yamsergey.dta.cli.inspect;

import io.yamsergey.dta.mcp.DaemonClient;
import io.yamsergey.dta.mcp.DaemonLauncher;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.Callable;

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
        try {
            DaemonClient daemon = DaemonLauncher.ensureDaemonRunning();
            String result;

            if (clearRequests) {
                System.err.println("Clearing network requests...");
                result = daemon.clearNetworkRequests(packageName, deviceSerial);
            } else if (showStats) {
                System.err.println("Fetching network statistics...");
                result = daemon.networkStats(packageName, deviceSerial);
            } else if (getBody) {
                if (requestId == null || requestId.isEmpty()) {
                    System.err.println("Error: --body requires --request-id to be specified");
                    return 1;
                }
                System.err.println("Fetching body for request " + requestId + "...");
                result = daemon.networkRequestBody(packageName, requestId, deviceSerial);
            } else if (requestId != null && !requestId.isEmpty()) {
                System.err.println("Fetching request " + requestId + "...");
                result = daemon.networkRequest(packageName, requestId, deviceSerial);
            } else {
                System.err.println("Fetching network requests...");
                result = daemon.networkRequests(packageName, deviceSerial);
            }

            outputResult(result);
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private void outputResult(String json) throws Exception {
        if (outputPath != null && !outputPath.isEmpty()) {
            File outputFile = new File(outputPath);
            File outputDir = outputFile.getParentFile();
            if (outputDir != null && !outputDir.exists()) {
                if (!outputDir.mkdirs()) {
                    System.err.println("Error: Failed to create output directory");
                    return;
                }
            }
            Files.writeString(outputFile.toPath(), json);
            System.err.println("Success: Output saved to " + outputFile.getAbsolutePath());
        } else {
            System.out.println(json);
        }
    }
}
