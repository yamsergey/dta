package io.yamsergey.dta.cli.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.yamsergey.dta.tools.android.inspect.compose.SidekickConnectionManager;
import io.yamsergey.dta.tools.sugar.Failure;
import io.yamsergey.dta.tools.sugar.Result;
import io.yamsergey.dta.tools.sugar.Success;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Update a mock rule.
 *
 * <p>Usage: dta-cli mock update &lt;rule-id&gt; --package com.example.app [options]</p>
 */
@Command(name = "update",
         description = "Update a mock rule.")
public class MockUpdateCommand implements Callable<Integer> {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Parameters(index = "0",
                description = "Mock rule ID to update")
    private String ruleId;

    @Option(names = {"--package", "-p"},
            required = true,
            description = "App package name")
    private String packageName;

    @Option(names = {"--device", "-d"},
            description = "Device serial number")
    private String deviceSerial;

    @Option(names = {"--name"},
            description = "New rule name")
    private String name;

    @Option(names = {"--url-pattern"},
            description = "URL pattern (regex)")
    private String urlPattern;

    @Option(names = {"--content-pattern"},
            description = "Content pattern (regex) for matching message/body")
    private String contentPattern;

    @Option(names = {"--direction"},
            description = "WebSocket direction: SENT, RECEIVED, or BOTH")
    private String direction;

    @Option(names = {"--body"},
            description = "Mock response body (for HTTP rules)")
    private String body;

    @Option(names = {"--status-code"},
            description = "HTTP status code (for HTTP rules)")
    private Integer statusCode;

    @Option(names = {"--text-payload"},
            description = "Mock text payload (for WebSocket rules)")
    private String textPayload;

    @Option(names = {"--drop"},
            description = "Drop WebSocket message instead of modifying")
    private Boolean drop;

    @Option(names = {"--delay"},
            description = "Response delay in milliseconds")
    private Integer delayMs;

    @Override
    public Integer call() throws Exception {
        // Build update JSON
        Map<String, Object> update = new HashMap<>();

        if (name != null) {
            update.put("name", name);
        }
        if (urlPattern != null) {
            update.put("urlPattern", urlPattern);
        }
        if (contentPattern != null) {
            update.put("contentPattern", contentPattern);
        }
        if (direction != null) {
            update.put("direction", direction.toUpperCase());
        }
        if (delayMs != null) {
            update.put("delayMs", delayMs);
        }

        // HTTP response fields
        if (body != null || statusCode != null) {
            Map<String, Object> mockResponse = new HashMap<>();
            if (body != null) {
                mockResponse.put("body", body);
            }
            if (statusCode != null) {
                mockResponse.put("statusCode", statusCode);
            }
            update.put("mockResponse", mockResponse);
        }

        // WebSocket message fields
        if (textPayload != null || drop != null) {
            Map<String, Object> mockMessage = new HashMap<>();
            if (textPayload != null) {
                mockMessage.put("textPayload", textPayload);
            }
            if (drop != null) {
                mockMessage.put("drop", drop);
            }
            update.put("mockMessage", mockMessage);
        }

        if (update.isEmpty()) {
            System.err.println("Error: No update options specified. Use --help to see available options.");
            return 1;
        }

        var conn = SidekickConnectionManager.getInstance().getConnection(packageName, deviceSerial);
        var client = conn.client();

        String updateJson = mapper.writeValueAsString(update);
        Result<String> result = client.updateMockRule(ruleId, updateJson);

        return switch (result) {
            case Success<String> success -> {
                System.out.println(success.value());
                System.err.println("Mock rule updated");
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
