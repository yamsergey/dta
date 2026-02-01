package io.yamsergey.dta.cli.selection;

import io.yamsergey.dta.tools.android.inspect.compose.SidekickClient;
import io.yamsergey.dta.tools.sugar.Failure;
import io.yamsergey.dta.tools.sugar.Result;
import io.yamsergey.dta.tools.sugar.Success;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * List all current selections.
 *
 * <p>Usage: dta-cli selection list --package com.example.app [--elements] [--requests] [--messages]</p>
 */
@Command(name = "list",
         description = "List selected elements, network requests, and/or WebSocket messages.")
public class SelectionListCommand implements Callable<Integer> {

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

    @Option(names = {"--elements", "-e"},
            description = "List selected UI elements")
    private boolean listElements;

    @Option(names = {"--requests", "-r"},
            description = "List selected network requests")
    private boolean listRequests;

    @Option(names = {"--messages", "-m"},
            description = "List selected WebSocket messages")
    private boolean listMessages;

    @Override
    public Integer call() throws Exception {
        // If no specific flags, list all
        boolean listAll = !listElements && !listRequests && !listMessages;

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

            if (listAll || listElements) {
                System.out.println("=== Selected Elements ===");
                Result<String> result = client.getSelectedElements();
                if (result instanceof Success<String> success) {
                    System.out.println(success.value());
                } else if (result instanceof Failure<String> failure) {
                    System.err.println("Error: " + failure.description());
                    hasError = true;
                }
                System.out.println();
            }

            if (listAll || listRequests) {
                System.out.println("=== Selected Network Requests ===");
                Result<String> result = client.getSelectedNetworkRequests();
                if (result instanceof Success<String> success) {
                    System.out.println(success.value());
                } else if (result instanceof Failure<String> failure) {
                    System.err.println("Error: " + failure.description());
                    hasError = true;
                }
                System.out.println();
            }

            if (listAll || listMessages) {
                System.out.println("=== Selected WebSocket Messages ===");
                Result<String> result = client.getSelectedWebSocketMessages();
                if (result instanceof Success<String> success) {
                    System.out.println(success.value());
                } else if (result instanceof Failure<String> failure) {
                    System.err.println("Error: " + failure.description());
                    hasError = true;
                }
            }

            return hasError ? 1 : 0;
        } finally {
            client.removePortForwarding();
        }
    }
}
