package io.yamsergey.dta.cli.mock;

import tools.jackson.databind.ObjectMapper;
import io.yamsergey.dta.mcp.DaemonClient;
import io.yamsergey.dta.mcp.DaemonLauncher;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "update",
         description = "Update a mock rule.")
public class MockUpdateCommand implements Callable<Integer> {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Parameters(index = "0", description = "Mock rule ID to update")
    private String ruleId;

    @Option(names = {"--package", "-p"}, required = true, description = "App package name")
    private String packageName;

    @Option(names = {"--device", "-d"}, description = "Device serial number")
    private String deviceSerial;

    @Option(names = {"--name"}, description = "New rule name")
    private String name;

    @Option(names = {"--url-pattern"}, description = "URL pattern (regex)")
    private String urlPattern;

    @Option(names = {"--content-pattern"}, description = "Content pattern (regex)")
    private String contentPattern;

    @Option(names = {"--direction"}, description = "WebSocket direction: SENT, RECEIVED, or BOTH")
    private String direction;

    @Option(names = {"--body"}, description = "Mock response body (for HTTP rules)")
    private String body;

    @Option(names = {"--status-code"}, description = "HTTP status code (for HTTP rules)")
    private Integer statusCode;

    @Option(names = {"--text-payload"}, description = "Mock text payload (for WebSocket rules)")
    private String textPayload;

    @Option(names = {"--drop"}, description = "Drop WebSocket message instead of modifying")
    private Boolean drop;

    @Option(names = {"--delay"}, description = "Response delay in milliseconds")
    private Integer delayMs;

    @Override
    public Integer call() throws Exception {
        Map<String, Object> update = new HashMap<>();
        if (name != null) update.put("name", name);
        if (urlPattern != null) update.put("urlPattern", urlPattern);
        if (contentPattern != null) update.put("contentPattern", contentPattern);
        if (direction != null) update.put("direction", direction.toUpperCase());
        if (delayMs != null) update.put("delayMs", delayMs);

        if (body != null || statusCode != null) {
            Map<String, Object> mockResponse = new HashMap<>();
            if (body != null) mockResponse.put("body", body);
            if (statusCode != null) mockResponse.put("statusCode", statusCode);
            update.put("mockResponse", mockResponse);
        }

        if (textPayload != null || drop != null) {
            Map<String, Object> mockMessage = new HashMap<>();
            if (textPayload != null) mockMessage.put("textPayload", textPayload);
            if (drop != null) mockMessage.put("drop", drop);
            update.put("mockMessage", mockMessage);
        }

        if (update.isEmpty()) {
            System.err.println("Error: No update options specified. Use --help to see available options.");
            return 1;
        }

        try {
            DaemonClient daemon = DaemonLauncher.ensureDaemonRunning();
            String result = daemon.updateMockRule(packageName, ruleId, deviceSerial, mapper.writeValueAsString(update));
            System.out.println(result);
            System.err.println("Mock rule updated");
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }
}
