package io.yamsergey.dta.cli.selection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.yamsergey.dta.tools.android.inspect.compose.SidekickClient;
import io.yamsergey.dta.tools.sugar.Failure;
import io.yamsergey.dta.tools.sugar.Result;
import io.yamsergey.dta.tools.sugar.Success;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Add items to selection.
 *
 * <p>Usage examples:</p>
 * <pre>
 * dta-cli selection add --package com.example.app --element --x 100 --y 200
 * dta-cli selection add --package com.example.app --request abc123
 * dta-cli selection add --package com.example.app --message --connection ws123 --index 5
 * </pre>
 */
@Command(name = "add",
         description = "Add an element, network request, or WebSocket message to selection.")
public class SelectionAddCommand implements Callable<Integer> {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Option(names = {"--package", "-p"},
            required = true,
            description = "App package name")
    private String packageName;

    @Option(names = {"--device", "-d"},
            description = "Device serial number")
    private String deviceSerial;

    @Option(names = {"--port"},
            defaultValue = "18640",
            description = "Local port for ADB forwarding (default: 18640)")
    private int port;

    // Mutually exclusive selection types
    @ArgGroup(exclusive = true, multiplicity = "1")
    private SelectionType selectionType;

    static class SelectionType {
        @ArgGroup(exclusive = false)
        ElementSelection element;

        @Option(names = {"--request", "-r"},
                description = "Network request ID to add")
        String requestId;

        @ArgGroup(exclusive = false)
        MessageSelection message;
    }

    static class ElementSelection {
        @Option(names = {"--element", "-e"}, required = true,
                description = "Add element at coordinates")
        boolean isElement;

        @Option(names = {"--x"}, required = true,
                description = "X coordinate")
        int x;

        @Option(names = {"--y"}, required = true,
                description = "Y coordinate")
        int y;
    }

    static class MessageSelection {
        @Option(names = {"--message", "-m"}, required = true,
                description = "Add WebSocket message")
        boolean isMessage;

        @Option(names = {"--connection", "-c"}, required = true,
                description = "WebSocket connection ID")
        String connectionId;

        @Option(names = {"--index", "-i"}, required = true,
                description = "Message index (0-based)")
        int messageIndex;
    }

    @Override
    public Integer call() throws Exception {
        SidekickClient client = SidekickClient.builder()
                .packageName(packageName)
                .port(port)
                .deviceSerial(deviceSerial)
                .build();

        Result<Void> setup = client.setupPortForwarding();
        if (setup instanceof Failure) {
            System.err.println("Error: " + ((Failure<?>) setup).description());
            return 1;
        }

        try {
            Result<String> result;

            if (selectionType.element != null) {
                // Add element by coordinates - first find it, then add
                Result<String> hitResult = client.getElementAtCoordinates(
                    selectionType.element.x, selectionType.element.y);

                if (hitResult instanceof Success<String> hit) {
                    JsonNode hitNode = mapper.readTree(hit.value());
                    if (hitNode.has("element") && !hitNode.get("element").isNull()) {
                        JsonNode element = hitNode.get("element");
                        result = client.addSelectedElement(mapper.writeValueAsString(element));
                    } else {
                        System.err.println("No element found at coordinates (" +
                            selectionType.element.x + ", " + selectionType.element.y + ")");
                        return 1;
                    }
                } else {
                    System.err.println("Error: " + ((Failure<?>) hitResult).description());
                    return 1;
                }
            } else if (selectionType.requestId != null) {
                // Add network request by ID
                String json = mapper.writeValueAsString(Map.of("id", selectionType.requestId));
                result = client.addSelectedNetworkRequest(json);
            } else if (selectionType.message != null) {
                // Add WebSocket message
                String json = mapper.writeValueAsString(Map.of(
                    "connectionId", selectionType.message.connectionId,
                    "messageIndex", selectionType.message.messageIndex
                ));
                result = client.addSelectedWebSocketMessage(json);
            } else {
                System.err.println("Error: Must specify --element, --request, or --message");
                return 1;
            }

            return switch (result) {
                case Success<String> success -> {
                    System.out.println(success.value());
                    yield 0;
                }
                case Failure<String> failure -> {
                    System.err.println("Error: " + failure.description());
                    yield 1;
                }
                default -> {
                    System.err.println("Error: Unknown result type");
                    yield 1;
                }
            };
        } finally {
            client.removePortForwarding();
        }
    }
}
