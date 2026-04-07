package io.yamsergey.dta.cli.selection;

import io.yamsergey.dta.daemon.DaemonClient;
import io.yamsergey.dta.daemon.DaemonLauncher;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

@Command(name = "list",
         description = "List selected elements, network requests, and/or WebSocket messages.")
public class SelectionListCommand implements Callable<Integer> {

    @Option(names = {"--package", "-p"}, required = true, description = "App package name")
    private String packageName;

    @Option(names = {"--device", "-d"}, description = "Device serial number")
    private String deviceSerial;

    @Option(names = {"--elements", "-e"}, description = "List selected UI elements")
    private boolean listElements;

    @Option(names = {"--requests", "-r"}, description = "List selected network requests")
    private boolean listRequests;

    @Option(names = {"--messages", "-m"}, description = "List selected WebSocket messages")
    private boolean listMessages;

    @Override
    public Integer call() throws Exception {
        boolean listAll = !listElements && !listRequests && !listMessages;

        try {
            DaemonClient daemon = DaemonLauncher.ensureDaemonRunning();
            boolean hasError = false;

            if (listAll || listElements) {
                System.out.println("=== Selected Elements ===");
                try {
                    System.out.println(daemon.getSelectedElements(packageName, deviceSerial));
                } catch (Exception e) {
                    System.err.println("Error: " + e.getMessage());
                    hasError = true;
                }
                System.out.println();
            }

            if (listAll || listRequests) {
                System.out.println("=== Selected Network Requests ===");
                try {
                    System.out.println(daemon.getSelectedNetworkRequests(packageName, deviceSerial));
                } catch (Exception e) {
                    System.err.println("Error: " + e.getMessage());
                    hasError = true;
                }
                System.out.println();
            }

            if (listAll || listMessages) {
                System.out.println("=== Selected WebSocket Messages ===");
                try {
                    System.out.println(daemon.getSelectedWebSocketMessages(packageName, deviceSerial));
                } catch (Exception e) {
                    System.err.println("Error: " + e.getMessage());
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
