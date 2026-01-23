package io.yamsergey.dta.cli.mock;

import io.yamsergey.dta.tools.android.inspect.compose.SidekickClient;
import io.yamsergey.dta.tools.sugar.Failure;
import io.yamsergey.dta.tools.sugar.Result;
import io.yamsergey.dta.tools.sugar.Success;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

/**
 * Disable a mock rule.
 *
 * <p>Usage: dta-cli mock disable &lt;rule-id&gt; --package com.example.app</p>
 */
@Command(name = "disable",
         description = "Disable a mock rule.")
public class MockDisableCommand implements Callable<Integer> {

    @Parameters(index = "0",
                description = "Mock rule ID to disable")
    private String ruleId;

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
            Result<String> result = client.updateMockRule(ruleId, "{\"enabled\":false}");

            return switch (result) {
                case Success<String> success -> {
                    System.out.println(success.value());
                    System.err.println("Mock rule disabled");
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
