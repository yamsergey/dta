package io.yamsergey.dta.cli.inspect;

import io.yamsergey.dta.daemon.DaemonClient;
import io.yamsergey.dta.daemon.DaemonLauncher;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.Callable;

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

    @Option(names = {"--connection-id", "-c"},
            description = "Get details for a specific connection by ID (includes messages).")
    private String connectionId;

    @Option(names = {"--clear"},
            description = "Clear all captured connections.")
    private boolean clearConnections;

    @Override
    public Integer call() throws Exception {
        try {
            DaemonClient daemon = DaemonLauncher.ensureDaemonRunning();
            String result;

            if (clearConnections) {
                System.err.println("Clearing WebSocket connections...");
                result = daemon.clearWebsocketConnections(packageName, deviceSerial);
            } else if (connectionId != null && !connectionId.isEmpty()) {
                System.err.println("Fetching connection " + connectionId + "...");
                result = daemon.websocketConnection(packageName, connectionId, deviceSerial);
            } else {
                System.err.println("Fetching WebSocket connections...");
                result = daemon.websocketConnections(packageName, deviceSerial);
            }

            if (outputPath != null && !outputPath.isEmpty()) {
                File outputFile = new File(outputPath);
                File outputDir = outputFile.getParentFile();
                if (outputDir != null && !outputDir.exists()) outputDir.mkdirs();
                Files.writeString(outputFile.toPath(), result);
                System.err.println("Success: Output saved to " + outputFile.getAbsolutePath());
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
