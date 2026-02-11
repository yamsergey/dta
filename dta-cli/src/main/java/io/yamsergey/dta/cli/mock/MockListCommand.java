package io.yamsergey.dta.cli.mock;

import io.yamsergey.dta.tools.android.inspect.compose.SidekickConnectionManager;
import io.yamsergey.dta.tools.sugar.Failure;
import io.yamsergey.dta.tools.sugar.Result;
import io.yamsergey.dta.tools.sugar.Success;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * List all mock rules.
 *
 * <p>Usage: dta-cli mock list --package com.example.app</p>
 */
@Command(name = "list",
         description = "List all mock rules.")
public class MockListCommand implements Callable<Integer> {

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

        Result<String> result = client.getMockRules();

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
