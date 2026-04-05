package io.yamsergey.dta.cli.selection;

import tools.jackson.databind.ObjectMapper;
import io.yamsergey.dta.mcp.DaemonClient;
import io.yamsergey.dta.mcp.DaemonLauncher;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "add",
         description = "Add an element, network request, or WebSocket message to selection.")
public class SelectionAddCommand implements Callable<Integer> {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Option(names = {"--package", "-p"}, required = true, description = "App package name")
    private String packageName;

    @Option(names = {"--device", "-d"}, description = "Device serial number")
    private String deviceSerial;

    @ArgGroup(exclusive = true, multiplicity = "1")
    private SelectionType selectionType;

    static class SelectionType {
        @ArgGroup(exclusive = false)
        ElementSelection element;

        @Option(names = {"--request", "-r"}, description = "Network request ID to add")
        String requestId;

        @ArgGroup(exclusive = false)
        MessageSelection message;
    }

    static class ElementSelection {
        @Option(names = {"--element", "-e"}, required = true, description = "Add element at coordinates")
        boolean isElement;

        @Option(names = {"--x"}, required = true, description = "X coordinate")
        int x;

        @Option(names = {"--y"}, required = true, description = "Y coordinate")
        int y;
    }

    static class MessageSelection {
        @Option(names = {"--message", "-m"}, required = true, description = "Add WebSocket message")
        boolean isMessage;

        @Option(names = {"--connection", "-c"}, required = true, description = "WebSocket connection ID")
        String connectionId;

        @Option(names = {"--index", "-i"}, required = true, description = "Message index (0-based)")
        int messageIndex;
    }

    @Override
    public Integer call() throws Exception {
        try {
            DaemonClient daemon = DaemonLauncher.ensureDaemonRunning();
            String result;

            if (selectionType.element != null) {
                String elementJson = mapper.writeValueAsString(Map.of(
                    "x", selectionType.element.x, "y", selectionType.element.y));
                result = daemon.addSelectedElement(packageName, deviceSerial, elementJson);
            } else if (selectionType.requestId != null) {
                String json = mapper.writeValueAsString(Map.of("id", selectionType.requestId));
                result = daemon.addSelectedNetworkRequest(packageName, deviceSerial, json);
            } else if (selectionType.message != null) {
                String json = mapper.writeValueAsString(Map.of(
                    "connectionId", selectionType.message.connectionId,
                    "messageIndex", selectionType.message.messageIndex));
                result = daemon.addSelectedWebSocketMessage(packageName, deviceSerial, json);
            } else {
                System.err.println("Error: Must specify --element, --request, or --message");
                return 1;
            }

            System.out.println(result);
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }
}
