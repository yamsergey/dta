package io.yamsergey.dta.cli.mock;

import tools.jackson.databind.ObjectMapper;
import io.yamsergey.dta.daemon.DaemonClient;
import io.yamsergey.dta.daemon.DaemonLauncher;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "create",
         description = "Create a mock rule from captured data or from scratch.")
public class MockCreateCommand implements Callable<Integer> {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Option(names = {"--package", "-p"}, required = true, description = "App package name")
    private String packageName;

    @Option(names = {"--device", "-d"}, description = "Device serial number")
    private String deviceSerial;

    @Option(names = {"--from-request"}, description = "ID of the captured HTTP request to create mock from")
    private String requestId;

    @Option(names = {"--from-message"}, description = "ID of the captured WebSocket message to create mock from")
    private String messageId;

    @Option(names = {"--type", "-t"}, description = "Rule type: HTTP or WEBSOCKET")
    private String type;

    @Option(names = {"--name"}, description = "Rule name")
    private String name;

    @Option(names = {"--url-pattern"}, description = "URL pattern (regex)")
    private String urlPattern;

    @Option(names = {"--content-pattern"}, description = "Content pattern (regex)")
    private String contentPattern;

    @Option(names = {"--direction"}, description = "WebSocket direction: SENT, RECEIVED, or BOTH")
    private String direction;

    @Option(names = {"--body"}, description = "Mock response body (for HTTP rules)")
    private String body;

    @Option(names = {"--status-code"}, defaultValue = "200", description = "HTTP status code (default: 200)")
    private int statusCode;

    @Option(names = {"--text-payload"}, description = "Mock text payload (for WebSocket rules)")
    private String textPayload;

    @Override
    public Integer call() throws Exception {
        boolean fromCaptured = requestId != null || messageId != null;
        boolean fromScratch = type != null;

        if (!fromCaptured && !fromScratch) {
            System.err.println("Error: Specify --from-request, --from-message, or --type");
            return 1;
        }
        if (fromCaptured && fromScratch) {
            System.err.println("Error: Cannot mix --from-request/--from-message with --type");
            return 1;
        }
        if (requestId != null && messageId != null) {
            System.err.println("Error: Cannot specify both --from-request and --from-message");
            return 1;
        }

        try {
            DaemonClient daemon = DaemonLauncher.ensureDaemonRunning();

            if (fromCaptured) {
                String result;
                if (requestId != null) {
                    result = daemon.createMockFromRequest(packageName, requestId, deviceSerial);
                    System.err.println("Mock rule created from HTTP request");
                } else {
                    result = daemon.createMockFromMessage(packageName, messageId, deviceSerial);
                    System.err.println("Mock rule created from WebSocket message");
                }
                System.out.println(result);
            } else {
                String ruleType = type.toUpperCase();
                if (!ruleType.equals("HTTP") && !ruleType.equals("WEBSOCKET")) {
                    System.err.println("Error: --type must be HTTP or WEBSOCKET");
                    return 1;
                }

                Map<String, Object> rule = new HashMap<>();
                rule.put("type", ruleType);
                rule.put("enabled", true);
                rule.put("name", name != null ? name : "New " + ruleType + " Rule");
                rule.put("urlPattern", urlPattern != null ? urlPattern : ".*");
                if (contentPattern != null) rule.put("contentPattern", contentPattern);

                if (ruleType.equals("HTTP")) {
                    Map<String, Object> mockResponse = new HashMap<>();
                    mockResponse.put("statusCode", statusCode);
                    mockResponse.put("statusMessage", "OK");
                    mockResponse.put("body", body != null ? body : "{\"mocked\":true}");
                    mockResponse.put("contentType", "application/json");
                    rule.put("mockResponse", mockResponse);
                } else {
                    rule.put("direction", direction != null ? direction.toUpperCase() : "RECEIVED");
                    Map<String, Object> mockMessage = new HashMap<>();
                    mockMessage.put("textPayload", textPayload != null ? textPayload : "{\"mocked\":true}");
                    rule.put("mockMessage", mockMessage);
                }

                String result = daemon.createMockRule(packageName, deviceSerial, mapper.writeValueAsString(rule));
                System.out.println(result);
                System.err.println(ruleType + " mock rule created");
            }
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }
}
