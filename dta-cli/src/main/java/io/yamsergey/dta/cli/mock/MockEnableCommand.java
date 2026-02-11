package io.yamsergey.dta.cli.mock;

import io.yamsergey.dta.tools.android.inspect.compose.SidekickConnectionManager;
import io.yamsergey.dta.tools.sugar.Failure;
import io.yamsergey.dta.tools.sugar.Result;
import io.yamsergey.dta.tools.sugar.Success;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

/**
 * Enable a mock rule.
 *
 * <p>Usage: dta-cli mock enable &lt;rule-id&gt; --package com.example.app</p>
 */
@Command(name = "enable",
         description = "Enable a mock rule.")
public class MockEnableCommand implements Callable<Integer> {

    @Parameters(index = "0",
                description = "Mock rule ID to enable")
    private String ruleId;

    @Option(names = {"--package", "-p"},
            required = true,
            description = "App package name")
    private String packageName;

    @Option(names = {"--device", "-d"},
            description = "Device serial number")
    private String deviceSerial;

    @Override
    public Integer call() throws Exception {
        var conn = SidekickConnectionManager.getInstance().getConnection(packageName, deviceSerial);
        var client = conn.client();

        Result<String> result = client.updateMockRule(ruleId, "{\"enabled\":true}");

        return switch (result) {
            case Success<String> success -> {
                System.out.println(success.value());
                System.err.println("Mock rule enabled");
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
