package io.yamsergey.dta.cli.mock;

import io.yamsergey.dta.tools.android.inspect.compose.SidekickClient;
import io.yamsergey.dta.tools.sugar.Failure;
import io.yamsergey.dta.tools.sugar.Result;
import io.yamsergey.dta.tools.sugar.Success;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * Get or update global mock configuration.
 *
 * <p>Usage:</p>
 * <ul>
 *   <li>dta-cli mock config --package com.example.app (show config)</li>
 *   <li>dta-cli mock config --package com.example.app --enable (enable mocking)</li>
 *   <li>dta-cli mock config --package com.example.app --disable (disable mocking)</li>
 * </ul>
 */
@Command(name = "config",
         description = "Get or update global mock configuration.")
public class MockConfigCommand implements Callable<Integer> {

    @Option(names = {"--package", "-p"},
            required = true,
            description = "App package name")
    private String packageName;

    @Option(names = {"--enable"},
            description = "Enable global mocking")
    private boolean enable;

    @Option(names = {"--disable"},
            description = "Disable global mocking")
    private boolean disable;

    @Option(names = {"--http"},
            description = "Enable/disable HTTP mocking (use with --enable or --disable)")
    private Boolean httpMocking;

    @Option(names = {"--websocket"},
            description = "Enable/disable WebSocket mocking (use with --enable or --disable)")
    private Boolean webSocketMocking;

    @Option(names = {"--device", "-d"},
            description = "Device serial number")
    private String deviceSerial;

    @Option(names = {"--port"},
            defaultValue = "18640",
            description = "Local port for ADB forwarding (default: 18640)")
    private int port;

    @Override
    public Integer call() throws Exception {
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

            // Check if we need to update config
            if (enable || disable || httpMocking != null || webSocketMocking != null) {
                StringBuilder json = new StringBuilder("{");
                boolean first = true;

                if (enable || disable) {
                    json.append("\"enabled\":").append(enable);
                    first = false;
                }
                if (httpMocking != null) {
                    if (!first) json.append(",");
                    json.append("\"httpMockingEnabled\":").append(httpMocking);
                    first = false;
                }
                if (webSocketMocking != null) {
                    if (!first) json.append(",");
                    json.append("\"webSocketMockingEnabled\":").append(webSocketMocking);
                }
                json.append("}");

                result = client.updateMockConfig(json.toString());
            } else {
                // Just get the current config
                result = client.getMockConfig();
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
