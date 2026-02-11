package io.yamsergey.dta.cli.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.yamsergey.dta.tools.android.inspect.compose.SidekickClient;
import io.yamsergey.dta.tools.android.inspect.compose.SidekickConnectionManager;
import io.yamsergey.dta.tools.sugar.Failure;
import io.yamsergey.dta.tools.sugar.Result;
import io.yamsergey.dta.tools.sugar.Success;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Create a mock rule.
 *
 * <p>Usage:</p>
 * <ul>
 *   <li>From captured: dta-cli mock create -p com.example.app --from-request &lt;id&gt;</li>
 *   <li>From captured: dta-cli mock create -p com.example.app --from-message &lt;id&gt;</li>
 *   <li>From scratch:  dta-cli mock create -p com.example.app --type HTTP --url-pattern ".*api.*" --body '{"mock":true}'</li>
 *   <li>From scratch:  dta-cli mock create -p com.example.app --type WEBSOCKET --direction RECEIVED --text-payload "mock"</li>
 * </ul>
 */
@Command(name = "create",
         description = "Create a mock rule from captured data or from scratch.")
public class MockCreateCommand implements Callable<Integer> {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Option(names = {"--package", "-p"},
            required = true,
            description = "App package name")
    private String packageName;

    @Option(names = {"--device", "-d"},
            description = "Device serial number")
    private String deviceSerial;

    // === Create from captured ===

    @Option(names = {"--from-request"},
            description = "ID of the captured HTTP request to create mock from")
    private String requestId;

    @Option(names = {"--from-message"},
            description = "ID of the captured WebSocket message to create mock from")
    private String messageId;

    // === Create from scratch ===

    @Option(names = {"--type", "-t"},
            description = "Rule type: HTTP or WEBSOCKET")
    private String type;

    @Option(names = {"--name"},
            description = "Rule name")
    private String name;

    @Option(names = {"--url-pattern"},
            description = "URL pattern (regex)")
    private String urlPattern;

    @Option(names = {"--content-pattern"},
            description = "Content pattern (regex) for matching message/body")
    private String contentPattern;

    @Option(names = {"--direction"},
            description = "WebSocket direction: SENT, RECEIVED, or BOTH (default: RECEIVED)")
    private String direction;

    @Option(names = {"--body"},
            description = "Mock response body (for HTTP rules)")
    private String body;

    @Option(names = {"--status-code"},
            defaultValue = "200",
            description = "HTTP status code (default: 200)")
    private int statusCode;

    @Option(names = {"--text-payload"},
            description = "Mock text payload (for WebSocket rules)")
    private String textPayload;

    @Override
    public Integer call() throws Exception {
        boolean fromCaptured = requestId != null || messageId != null;
        boolean fromScratch = type != null;

        // Validate mode
        if (!fromCaptured && !fromScratch) {
            System.err.println("Error: Specify --from-request, --from-message, or --type to create a rule");
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

        var conn = SidekickConnectionManager.getInstance().getConnection(packageName, deviceSerial);
        var client = conn.client();

        if (fromCaptured) {
            return createFromCaptured(client);
        } else {
            return createFromScratch(client);
        }
    }

    private Integer createFromCaptured(SidekickClient client) {
        Result<String> result;
        String sourceType;

        if (requestId != null) {
            result = client.createMockFromRequest(requestId);
            sourceType = "HTTP request";
        } else {
            result = client.createMockFromMessage(messageId);
            sourceType = "WebSocket message";
        }

        return switch (result) {
            case Success<String> success -> {
                System.out.println(success.value());
                System.err.println("Mock rule created from " + sourceType);
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

    private Integer createFromScratch(SidekickClient client) throws Exception {
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

        if (contentPattern != null) {
            rule.put("contentPattern", contentPattern);
        }

        if (ruleType.equals("HTTP")) {
            Map<String, Object> mockResponse = new HashMap<>();
            mockResponse.put("statusCode", statusCode);
            mockResponse.put("statusMessage", "OK");
            mockResponse.put("body", body != null ? body : "{\"mocked\":true}");
            mockResponse.put("contentType", "application/json");
            rule.put("mockResponse", mockResponse);
        } else {
            // WEBSOCKET
            rule.put("direction", direction != null ? direction.toUpperCase() : "RECEIVED");
            Map<String, Object> mockMessage = new HashMap<>();
            mockMessage.put("textPayload", textPayload != null ? textPayload : "{\"mocked\":true}");
            rule.put("mockMessage", mockMessage);
        }

        String ruleJson = mapper.writeValueAsString(rule);
        Result<String> result = client.createMockRule(ruleJson);

        return switch (result) {
            case Success<String> success -> {
                System.out.println(success.value());
                System.err.println(ruleType + " mock rule created");
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
