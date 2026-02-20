package io.yamsergey.dta.cli.selection;

import io.yamsergey.dta.mcp.DaemonClient;
import io.yamsergey.dta.mcp.DaemonLauncher;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

@Command(name = "clear",
         description = "Clear selected elements, network requests, and/or WebSocket messages.")
public class SelectionClearCommand implements Callable<Integer> {

    @Option(names = {"--package", "-p"}, required = true, description = "App package name")
    private String packageName;

    @Option(names = {"--device", "-d"}, description = "Device serial number")
    private String deviceSerial;

    @Option(names = {"--all", "-a"}, description = "Clear all selections")
    private boolean clearAll;

    @Option(names = {"--elements", "-e"}, description = "Clear selected UI elements")
    private boolean clearElements;

    @Option(names = {"--requests", "-r"}, description = "Clear selected network requests")
    private boolean clearRequests;

    @Option(names = {"--messages", "-m"}, description = "Clear selected WebSocket messages")
    private boolean clearMessages;

    @Override
    public Integer call() throws Exception {
        boolean doAll = clearAll || (!clearElements && !clearRequests && !clearMessages);

        try {
            DaemonClient daemon = DaemonLauncher.ensureDaemonRunning();
            boolean hasError = false;

            if (doAll || clearElements) {
                try {
                    daemon.clearSelectedElements(packageName, deviceSerial);
                    System.out.println("Cleared element selections");
                } catch (Exception e) {
                    System.err.println("Error clearing elements: " + e.getMessage());
                    hasError = true;
                }
            }

            if (doAll || clearRequests) {
                try {
                    daemon.clearSelectedNetworkRequests(packageName, deviceSerial);
                    System.out.println("Cleared network request selections");
                } catch (Exception e) {
                    System.err.println("Error clearing requests: " + e.getMessage());
                    hasError = true;
                }
            }

            if (doAll || clearMessages) {
                try {
                    daemon.clearSelectedWebSocketMessages(packageName, deviceSerial);
                    System.out.println("Cleared WebSocket message selections");
                } catch (Exception e) {
                    System.err.println("Error clearing messages: " + e.getMessage());
                    hasError = true;
                }
            }

            return hasError ? 1 : 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }
}
