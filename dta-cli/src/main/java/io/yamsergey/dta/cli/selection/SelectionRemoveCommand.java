package io.yamsergey.dta.cli.selection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.yamsergey.dta.tools.android.inspect.compose.SidekickConnectionManager;
import io.yamsergey.dta.tools.sugar.Failure;
import io.yamsergey.dta.tools.sugar.Result;
import io.yamsergey.dta.tools.sugar.Success;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Remove items from selection.
 *
 * <p>Usage examples:</p>
 * <pre>
 * dta-cli selection remove --package com.example.app --element --x 100 --y 200
 * dta-cli selection remove --package com.example.app --request abc123
 * dta-cli selection remove --package com.example.app --message --connection ws123 --index 5
 * </pre>
 */
@Command(name = "remove",
         description = "Remove an element, network request, or WebSocket message from selection.")
public class SelectionRemoveCommand implements Callable<Integer> {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Option(names = {"--package", "-p"},
            required = true,
            description = "App package name")
    private String packageName;

    @Option(names = {"--device", "-d"},
            description = "Device serial number")
    private String deviceSerial;

    // Mutually exclusive selection types
    @ArgGroup(exclusive = true, multiplicity = "1")
    private SelectionType selectionType;

    static class SelectionType {
        @ArgGroup(exclusive = false)
        ElementSelection element;

        @Option(names = {"--request", "-r"},
                description = "Network request ID to remove")
        String requestId;

        @ArgGroup(exclusive = false)
        MessageSelection message;
    }

    static class ElementSelection {
        @Option(names = {"--element", "-e"}, required = true,
                description = "Remove element at coordinates")
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
                description = "Remove WebSocket message")
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
        var conn = SidekickConnectionManager.getInstance().getConnection(packageName, deviceSerial);
        var client = conn.client();

        Result<String> result;

        if (selectionType.element != null) {
            // Remove element by coordinates - first find it, then remove
            Result<String> hitResult = client.getElementAtCoordinates(
                selectionType.element.x, selectionType.element.y);

            if (hitResult instanceof Success<String> hit) {
                JsonNode hitNode = mapper.readTree(hit.value());
                if (hitNode.has("element") && !hitNode.get("element").isNull()) {
                    JsonNode element = hitNode.get("element");
                    result = client.removeSelectedElement(mapper.writeValueAsString(element));
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
            // Remove network request by ID
            String json = mapper.writeValueAsString(Map.of("id", selectionType.requestId));
            result = client.removeSelectedNetworkRequest(json);
        } else if (selectionType.message != null) {
            // Remove WebSocket message
            String json = mapper.writeValueAsString(Map.of(
                "connectionId", selectionType.message.connectionId,
                "messageIndex", selectionType.message.messageIndex
            ));
            result = client.removeSelectedWebSocketMessage(json);
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
    }
}
