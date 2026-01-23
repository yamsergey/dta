package io.yamsergey.dta.cli.mock;

import io.yamsergey.dta.tools.android.inspect.compose.SidekickClient;
import io.yamsergey.dta.tools.sugar.Failure;
import io.yamsergey.dta.tools.sugar.Result;
import io.yamsergey.dta.tools.sugar.Success;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * Create a mock rule from a captured HTTP request or WebSocket message.
 *
 * <p>Usage:</p>
 * <ul>
 *   <li>dta-cli mock create --package com.example.app --from-request &lt;request-id&gt;</li>
 *   <li>dta-cli mock create --package com.example.app --from-message &lt;message-id&gt;</li>
 * </ul>
 */
@Command(name = "create",
         description = "Create a mock rule from a captured HTTP request or WebSocket message.")
public class MockCreateCommand implements Callable<Integer> {

    @Option(names = {"--package", "-p"},
            required = true,
            description = "App package name")
    private String packageName;

    @Option(names = {"--from-request"},
            description = "ID of the captured HTTP request to create mock from")
    private String requestId;

    @Option(names = {"--from-message"},
            description = "ID of the captured WebSocket message to create mock from")
    private String messageId;

    @Option(names = {"--device", "-d"},
            description = "Device serial number")
    private String deviceSerial;

    @Option(names = {"--port"},
            defaultValue = "18640",
            description = "Local port for ADB forwarding (default: 18640)")
    private int port;

    @Override
    public Integer call() throws Exception {
        // Validate that exactly one of --from-request or --from-message is specified
        if (requestId == null && messageId == null) {
            System.err.println("Error: Either --from-request or --from-message must be specified");
            return 1;
        }
        if (requestId != null && messageId != null) {
            System.err.println("Error: Cannot specify both --from-request and --from-message");
            return 1;
        }

        // Setup port forwarding
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
            String type;

            if (requestId != null) {
                result = client.createMockFromRequest(requestId);
                type = "HTTP request";
            } else {
                result = client.createMockFromMessage(messageId);
                type = "WebSocket message";
            }

            return switch (result) {
                case Success<String> success -> {
                    System.out.println(success.value());
                    System.err.println("Mock rule created from " + type);
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
