package io.yamsergey.dta.cli.selection;

import io.yamsergey.dta.tools.android.inspect.compose.SidekickClient;
import io.yamsergey.dta.tools.sugar.Failure;
import io.yamsergey.dta.tools.sugar.Result;
import io.yamsergey.dta.tools.sugar.Success;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * Clear selections.
 *
 * <p>Usage examples:</p>
 * <pre>
 * dta-cli selection clear --package com.example.app --all
 * dta-cli selection clear --package com.example.app --elements
 * dta-cli selection clear --package com.example.app --requests
 * dta-cli selection clear --package com.example.app --messages
 * </pre>
 */
@Command(name = "clear",
         description = "Clear selected elements, network requests, and/or WebSocket messages.")
public class SelectionClearCommand implements Callable<Integer> {

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

    @Option(names = {"--all", "-a"},
            description = "Clear all selections")
    private boolean clearAll;

    @Option(names = {"--elements", "-e"},
            description = "Clear selected UI elements")
    private boolean clearElements;

    @Option(names = {"--requests", "-r"},
            description = "Clear selected network requests")
    private boolean clearRequests;

    @Option(names = {"--messages", "-m"},
            description = "Clear selected WebSocket messages")
    private boolean clearMessages;

    @Override
    public Integer call() throws Exception {
        // If no specific flags, clear all
        boolean doAll = clearAll || (!clearElements && !clearRequests && !clearMessages);

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
            boolean hasError = false;

            if (doAll || clearElements) {
                Result<String> result = client.clearSelectedElements();
                if (result instanceof Success<String>) {
                    System.out.println("Cleared element selections");
                } else if (result instanceof Failure<String> failure) {
                    System.err.println("Error clearing elements: " + failure.description());
                    hasError = true;
                }
            }

            if (doAll || clearRequests) {
                Result<String> result = client.clearSelectedNetworkRequests();
                if (result instanceof Success<String>) {
                    System.out.println("Cleared network request selections");
                } else if (result instanceof Failure<String> failure) {
                    System.err.println("Error clearing requests: " + failure.description());
                    hasError = true;
                }
            }

            if (doAll || clearMessages) {
                Result<String> result = client.clearSelectedWebSocketMessages();
                if (result instanceof Success<String>) {
                    System.out.println("Cleared WebSocket message selections");
                } else if (result instanceof Failure<String> failure) {
                    System.err.println("Error clearing messages: " + failure.description());
                    hasError = true;
                }
            }

            return hasError ? 1 : 0;
        } finally {
            client.removePortForwarding();
        }
    }
}
