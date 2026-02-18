package io.yamsergey.dta.tools.android.inspect.compose;

import io.yamsergey.dta.tools.sugar.Failure;
import io.yamsergey.dta.tools.sugar.Result;
import io.yamsergey.dta.tools.sugar.Success;
import lombok.Builder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * HTTP client for communicating with the ADT Sidekick server running in an Android app.
 *
 * <p>The sidekick server uses a Unix domain socket named {@code dta_sidekick_<package_name>}.
 * This client sets up ADB port forwarding to access the socket from the host.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * SidekickClient client = SidekickClient.builder()
 *     .packageName("com.example.app")
 *     .adbPath("adb")
 *     .deviceSerial("emulator-5554")
 *     .build();
 *
 * // Set up port forwarding first
 * client.setupPortForwarding();
 *
 * // Then make requests
 * Result&lt;String&gt; tree = client.getComposeTree();
 * </pre>
 */
@Builder
public class SidekickClient {

    private static final Logger log = LoggerFactory.getLogger(SidekickClient.class);
    private static final int DEFAULT_PORT = 8642;
    private static final int DEFAULT_TIMEOUT_MS = 30000;
    private static final String SOCKET_PREFIX = "dta_sidekick_";

    /**
     * Package name of the target application (required).
     * Used to construct the Unix socket name.
     */
    private final String packageName;

    /**
     * Local port for forwarding (default 8642).
     */
    @Builder.Default
    private final int port = DEFAULT_PORT;

    /**
     * Path to ADB executable.
     */
    @Builder.Default
    private final String adbPath = "adb";

    /**
     * Device serial number (optional).
     */
    private final String deviceSerial;

    /**
     * Connection timeout in milliseconds.
     */
    @Builder.Default
    private final int timeoutMs = DEFAULT_TIMEOUT_MS;

    /**
     * Gets the Unix socket name for the sidekick server.
     *
     * @return socket name in format "dta_sidekick_<package_name>"
     */
    public String getSocketName() {
        return SOCKET_PREFIX + packageName;
    }

    /**
     * Sets up ADB port forwarding to the sidekick server's Unix domain socket.
     *
     * <p>This forwards local TCP port to the app's Unix socket:
     * {@code adb forward tcp:<port> localabstract:dta_sidekick_<package>}</p>
     *
     * @return Result indicating success or failure
     */
    public Result<Void> setupPortForwarding() {
        if (packageName == null || packageName.isEmpty()) {
            return new Failure<>(null, "Package name is required for port forwarding");
        }

        try {
            String socketName = getSocketName();
            List<String> command = buildAdbCommand("forward", "tcp:" + port, "localabstract:" + socketName);
            log.debug("Setting up port forward: tcp:{} -> localabstract:{}", port, socketName);
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.error("Port forwarding timed out: tcp:{} -> {}", port, socketName);
                return new Failure<>(null, "ADB port forwarding timed out after 30 seconds");
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String output = readStream(process.getInputStream());
                log.error("Port forwarding failed (exit {}): {}", exitCode, output);
                return new Failure<>(null, "Port forwarding failed: " + output);
            }

            log.info("Port forward established: tcp:{} -> {}", port, socketName);
            return new Success<>(null, "Port forwarding established: tcp:" + port + " -> " + socketName);

        } catch (Exception e) {
            log.error("Port forwarding error: {}", e.getMessage());
            return new Failure<>(null, "Failed to set up port forwarding: " + e.getMessage());
        }
    }

    /**
     * Removes ADB port forwarding.
     */
    public void removePortForwarding() {
        try {
            List<String> command = buildAdbCommand("forward", "--remove", "tcp:" + port);
            log.debug("Removing port forward: tcp:{}", port);
            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("Port forward removal timed out: tcp:{}", port);
            }
        } catch (Exception e) {
            log.debug("Port forward removal error (ignored): {}", e.getMessage());
        }
    }

    /**
     * Checks if the sidekick server is reachable.
     *
     * @return Result containing health status JSON on success
     */
    public Result<String> checkHealth() {
        return httpGet("/health");
    }

    /**
     * Checks the sidekick server health and parses the response.
     *
     * @return Result containing typed HealthResponse on success
     */
    public Result<HealthResponse> checkHealthTyped() {
        Result<String> result = httpGet("/health");
        if (result instanceof Success<String> success) {
            try {
                String json = success.value();
                // Simple JSON parsing without external dependencies
                String status = extractJsonString(json, "status");
                String name = extractJsonString(json, "name");
                String version = extractJsonString(json, "version");
                String socketName = extractJsonString(json, "socketName");
                String pkgName = extractJsonString(json, "packageName");
                int sseClients = extractJsonInt(json, "sseClients", 0);

                return new Success<>(
                    new HealthResponse(status, name, version, socketName, pkgName, sseClients),
                    "OK"
                );
            } catch (Exception e) {
                return new Failure<>(null, "Failed to parse health response: " + e.getMessage());
            }
        }
        return new Failure<>(null, result instanceof Failure<String> f ? f.description() : "Unknown error");
    }

    /**
     * Extracts a string value from simple JSON.
     */
    private String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]*)\"";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Extracts an int value from simple JSON.
     */
    private int extractJsonInt(String json, String key, int defaultValue) {
        String pattern = "\"" + key + "\"\\s*:\\s*(\\d+)";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : defaultValue;
    }

    /**
     * Gets the unified Compose tree combining layout and semantics.
     *
     * <p>This provides a cleaner, source-like representation with:
     * <ul>
     *   <li>Composable type names (Button, Text, Column, etc.)</li>
     *   <li>Full class names for debugging</li>
     *   <li>Absolute bounds (left, top, right, bottom)</li>
     *   <li>Semantic properties inline (text, role, testTag)</li>
     *   <li>Screen context (activity name, root composable)</li>
     * </ul>
     *
     * @return Result containing unified tree JSON on success
     */
    public Result<String> getComposeTree() {
        return httpGet("/compose/tree");
    }

    /**
     * Takes a screenshot of the current Compose UI.
     *
     * @return Result containing PNG image bytes on success
     */
    public Result<byte[]> getScreenshot() {
        return httpGetBytes("/compose/screenshot");
    }

    // ========================================================================
    // Network inspection endpoints
    // ========================================================================

    /**
     * Gets all captured HTTP requests.
     *
     * @return Result containing requests JSON array on success
     */
    public Result<String> getNetworkRequests() {
        return httpGet("/network/requests");
    }

    /**
     * Gets a specific HTTP request by ID.
     *
     * @param requestId the request ID
     * @return Result containing request details JSON on success
     */
    public Result<String> getNetworkRequest(String requestId) {
        return httpGet("/network/requests/" + requestId);
    }

    /**
     * Gets the response body for a specific HTTP request.
     * Returns base64-encoded content for binary responses.
     *
     * @param requestId the request ID
     * @return Result containing body JSON on success
     */
    public Result<String> getNetworkRequestBody(String requestId) {
        return httpGet("/network/requests/" + requestId + "/body");
    }

    /**
     * Gets network statistics.
     *
     * @return Result containing stats JSON on success
     */
    public Result<String> getNetworkStats() {
        return httpGet("/network/stats");
    }

    /**
     * Clears all captured network requests.
     *
     * @return Result indicating success or failure
     */
    public Result<String> clearNetworkRequests() {
        return httpDelete("/network/clear");
    }

    // ========================================================================
    // WebSocket inspection endpoints
    // ========================================================================

    /**
     * Gets all WebSocket connections.
     *
     * @return Result containing connections JSON array on success
     */
    public Result<String> getWebSocketConnections() {
        return httpGet("/websocket/connections");
    }

    /**
     * Gets a specific WebSocket connection by ID, including messages.
     *
     * @param connectionId the connection ID
     * @return Result containing connection details with messages on success
     */
    public Result<String> getWebSocketConnection(String connectionId) {
        return httpGet("/websocket/connections/" + connectionId);
    }

    /**
     * Clears all captured WebSocket connections.
     *
     * @return Result indicating success or failure
     */
    public Result<String> clearWebSocketConnections() {
        return httpDelete("/websocket/clear");
    }

    // ========================================================================
    // Element selection / hit-testing
    // ========================================================================

    /**
     * Finds the UI element at the given screen coordinates.
     *
     * <p>Performs a hit-test to find the deepest (most specific) element
     * at the given coordinates. Useful for identifying what element is
     * at a specific position on the screen.</p>
     *
     * @param x the X coordinate
     * @param y the Y coordinate
     * @return Result containing element JSON on success
     */
    public Result<String> getElementAtCoordinates(int x, int y) {
        return httpGet("/compose/select?x=" + x + "&y=" + y);
    }

    /**
     * Finds all UI elements at the given screen coordinates.
     *
     * <p>Returns all elements that contain the given point, from
     * outermost (root) to innermost (deepest). Useful for understanding
     * the full element hierarchy at a specific position.</p>
     *
     * @param x the X coordinate
     * @param y the Y coordinate
     * @return Result containing elements JSON array on success
     */
    public Result<String> getAllElementsAtCoordinates(int x, int y) {
        return httpGet("/compose/select-all?x=" + x + "&y=" + y);
    }

    /**
     * Gets all selected UI elements.
     *
     * @return Result containing JSON with { count, elements[] }
     */
    public Result<String> getSelectedElements() {
        return httpGet("/selection/element");
    }

    /**
     * Adds an element to the selection.
     *
     * @param elementJson JSON representation of the element to add
     * @return Result containing updated selection
     */
    public Result<String> addSelectedElement(String elementJson) {
        return httpPost("/selection/element?action=add", elementJson);
    }

    /**
     * Removes an element from the selection.
     *
     * @param elementJson JSON representation of the element to remove
     * @return Result containing updated selection
     */
    public Result<String> removeSelectedElement(String elementJson) {
        return httpPost("/selection/element?action=remove", elementJson);
    }

    /**
     * Clears all element selections.
     *
     * @return Result indicating success or failure
     */
    public Result<String> clearSelectedElements() {
        return httpPost("/selection/element?action=clear", "null");
    }

    // ========================================================================
    // Network request selection (multi-selection)
    // ========================================================================

    /**
     * Gets all selected network requests.
     *
     * @return Result containing JSON with { count, requests[] }
     */
    public Result<String> getSelectedNetworkRequests() {
        return httpGet("/selection/network");
    }

    /**
     * Adds a network request to the selection.
     *
     * @param requestJson JSON representation of the request to add
     * @return Result containing updated selection
     */
    public Result<String> addSelectedNetworkRequest(String requestJson) {
        return httpPost("/selection/network?action=add", requestJson);
    }

    /**
     * Removes a network request from the selection.
     *
     * @param requestJson JSON representation of the request to remove
     * @return Result containing updated selection
     */
    public Result<String> removeSelectedNetworkRequest(String requestJson) {
        return httpPost("/selection/network?action=remove", requestJson);
    }

    /**
     * Clears all network request selections.
     *
     * @return Result indicating success or failure
     */
    public Result<String> clearSelectedNetworkRequests() {
        return httpPost("/selection/network?action=clear", "null");
    }

    // ========================================================================
    // WebSocket message selection (multi-selection)
    // ========================================================================

    /**
     * Gets all selected WebSocket messages.
     *
     * @return Result containing JSON with { count, messages[] }
     */
    public Result<String> getSelectedWebSocketMessages() {
        return httpGet("/selection/websocket-message");
    }

    /**
     * Adds a WebSocket message to the selection.
     *
     * @param selectionJson JSON with { connectionId, messageIndex, message }
     * @return Result containing updated selection
     */
    public Result<String> addSelectedWebSocketMessage(String selectionJson) {
        return httpPost("/selection/websocket-message?action=add", selectionJson);
    }

    /**
     * Removes a WebSocket message from the selection.
     *
     * @param selectionJson JSON with { connectionId, messageIndex }
     * @return Result containing updated selection
     */
    public Result<String> removeSelectedWebSocketMessage(String selectionJson) {
        return httpPost("/selection/websocket-message?action=remove", selectionJson);
    }

    /**
     * Clears all WebSocket message selections.
     *
     * @return Result indicating success or failure
     */
    public Result<String> clearSelectedWebSocketMessages() {
        return httpPost("/selection/websocket-message?action=clear", "null");
    }

    // ========================================================================
    // Custom Tabs inspection endpoints
    // ========================================================================

    /**
     * Gets all captured Custom Tab events.
     *
     * <p>Custom Tab events are recorded when the app opens a URL
     * in Chrome Custom Tabs.</p>
     *
     * @return Result containing events JSON on success
     */
    public Result<String> getCustomTabEvents() {
        return httpGet("/customtabs/events");
    }

    /**
     * Clears all captured Custom Tab events.
     *
     * @return Result indicating success or failure
     */
    public Result<String> clearCustomTabEvents() {
        return httpDelete("/customtabs/events");
    }

    /**
     * Records an external HTTP transaction to the sidekick's network inspector.
     *
     * <p>This method allows recording network events from external sources
     * (like Chrome Custom Tabs via CDP) into the sidekick's transaction store,
     * so they appear alongside in-app HTTP requests.</p>
     *
     * @param transactionJson JSON representation of the transaction
     * @return Result containing the recorded transaction ID on success
     */
    public Result<String> recordTransaction(String transactionJson) {
        return httpPost("/network/transactions", transactionJson);
    }

    // ========================================================================
    // Mock API endpoints
    // ========================================================================

    /**
     * Gets all mock rules.
     *
     * @return Result containing mock rules JSON on success
     */
    public Result<String> getMockRules() {
        return httpGet("/mock/rules");
    }

    /**
     * Gets a specific mock rule by ID.
     *
     * @param ruleId the rule ID
     * @return Result containing rule details JSON on success
     */
    public Result<String> getMockRule(String ruleId) {
        return httpGet("/mock/rules/" + ruleId);
    }

    /**
     * Creates a new mock rule.
     *
     * @param ruleJson JSON representation of the rule to create
     * @return Result containing created rule JSON on success
     */
    public Result<String> createMockRule(String ruleJson) {
        return httpPost("/mock/rules", ruleJson);
    }

    /**
     * Updates an existing mock rule.
     *
     * @param ruleId the rule ID
     * @param updateJson JSON with fields to update
     * @return Result containing updated rule JSON on success
     */
    public Result<String> updateMockRule(String ruleId, String updateJson) {
        return httpPut("/mock/rules/" + ruleId, updateJson);
    }

    /**
     * Deletes a mock rule.
     *
     * @param ruleId the rule ID
     * @return Result indicating success or failure
     */
    public Result<String> deleteMockRule(String ruleId) {
        return httpDelete("/mock/rules/" + ruleId);
    }

    /**
     * Gets the global mock configuration.
     *
     * @return Result containing config JSON on success
     */
    public Result<String> getMockConfig() {
        return httpGet("/mock/config");
    }

    /**
     * Updates the global mock configuration.
     *
     * @param configJson JSON with config fields to update
     * @return Result containing updated config JSON on success
     */
    public Result<String> updateMockConfig(String configJson) {
        return httpPut("/mock/config", configJson);
    }

    /**
     * Creates a mock rule from a captured HTTP request.
     * The rule is pre-filled with the captured response data.
     *
     * @param requestId the captured request ID
     * @return Result containing created rule JSON on success
     */
    public Result<String> createMockFromRequest(String requestId) {
        return httpPost("/mock/from-request/" + requestId, null);
    }

    /**
     * Creates a mock rule from a captured WebSocket message.
     * The rule is pre-filled with the captured message data.
     *
     * @param messageId the captured message ID
     * @return Result containing created rule JSON on success
     */
    public Result<String> createMockFromMessage(String messageId) {
        return httpPost("/mock/from-message/" + messageId, null);
    }

    // ========================================================================
    // CDP capture control endpoints
    // ========================================================================

    /**
     * Arms CDP capture on sidekick. When armed, Custom Tab launches will
     * be held until the host acks with CDP attached.
     */
    public Result<String> requestCdpCapture() {
        return httpPost("/customtabs/request-cdp-capture", null);
    }

    /**
     * Disarms CDP capture on sidekick.
     */
    public Result<String> releaseCdpCapture() {
        return httpPost("/customtabs/release-cdp-capture", null);
    }

    /**
     * Acknowledges that CDP is attached for a Custom Tab launch event.
     * This unblocks the JVMTI hook so Chrome can proceed with launch.
     */
    public Result<String> ackCustomTab(String eventId) {
        return httpPost("/customtabs/ack/" + eventId, null);
    }

    /**
     * Makes an HTTP GET request to the sidekick server.
     *
     * @param path the endpoint path (e.g., "/health")
     * @return Result containing response body on success
     */
    private Result<String> httpGet(String path) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("http://localhost:" + port + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);

            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                String body = readStream(connection.getInputStream());
                return new Success<>(body, "OK");
            } else {
                String error = readStream(connection.getErrorStream());
                return new Failure<>(null, "HTTP " + responseCode + ": " + error);
            }

        } catch (java.net.ConnectException e) {
            return new Failure<>(null,
                "Cannot connect to sidekick server on port " + port + ". " +
                "Make sure the app is running and includes the dta-sidekick dependency.");
        } catch (Exception e) {
            return new Failure<>(null, "HTTP request failed: " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Makes an HTTP GET request to the sidekick server, returning raw bytes.
     *
     * @param path the endpoint path
     * @return Result containing response bytes on success
     */
    private Result<byte[]> httpGetBytes(String path) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("http://localhost:" + port + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);

            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                byte[] body = connection.getInputStream().readAllBytes();
                return new Success<>(body, "OK");
            } else {
                String error = readStream(connection.getErrorStream());
                return new Failure<>(null, "HTTP " + responseCode + ": " + error);
            }

        } catch (java.net.ConnectException e) {
            return new Failure<>(null,
                "Cannot connect to sidekick server on port " + port + ". " +
                "Make sure the app is running and includes the dta-sidekick dependency.");
        } catch (Exception e) {
            return new Failure<>(null, "HTTP request failed: " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Makes an HTTP POST request to the sidekick server.
     *
     * @param path the endpoint path
     * @param body the request body (JSON)
     * @return Result containing response body on success
     */
    private Result<String> httpPost(String path, String body) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("http://localhost:" + port + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");

            if (body != null) {
                connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = connection.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                String responseBody = readStream(connection.getInputStream());
                return new Success<>(responseBody, "OK");
            } else {
                String error = readStream(connection.getErrorStream());
                return new Failure<>(null, "HTTP " + responseCode + ": " + error);
            }

        } catch (java.net.ConnectException e) {
            return new Failure<>(null,
                "Cannot connect to sidekick server on port " + port + ". " +
                "Make sure the app is running and includes the dta-sidekick dependency.");
        } catch (Exception e) {
            return new Failure<>(null, "HTTP request failed: " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Makes an HTTP PUT request to the sidekick server.
     *
     * @param path the endpoint path
     * @param body the request body (JSON)
     * @return Result containing response body on success
     */
    private Result<String> httpPut(String path, String body) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("http://localhost:" + port + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("PUT");
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");

            if (body != null) {
                connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = connection.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                String responseBody = readStream(connection.getInputStream());
                return new Success<>(responseBody, "OK");
            } else {
                String error = readStream(connection.getErrorStream());
                return new Failure<>(null, "HTTP " + responseCode + ": " + error);
            }

        } catch (java.net.ConnectException e) {
            return new Failure<>(null,
                "Cannot connect to sidekick server on port " + port + ". " +
                "Make sure the app is running and includes the dta-sidekick dependency.");
        } catch (Exception e) {
            return new Failure<>(null, "HTTP request failed: " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Makes an HTTP DELETE request to the sidekick server.
     *
     * @param path the endpoint path
     * @return Result containing response body on success
     */
    private Result<String> httpDelete(String path) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("http://localhost:" + port + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("DELETE");
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);

            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                String responseBody = readStream(connection.getInputStream());
                return new Success<>(responseBody, "OK");
            } else {
                String error = readStream(connection.getErrorStream());
                return new Failure<>(null, "HTTP " + responseCode + ": " + error);
            }

        } catch (java.net.ConnectException e) {
            return new Failure<>(null,
                "Cannot connect to sidekick server on port " + port + ". " +
                "Make sure the app is running and includes the dta-sidekick dependency.");
        } catch (Exception e) {
            return new Failure<>(null, "HTTP request failed: " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Builds an ADB command with optional device selector.
     */
    private List<String> buildAdbCommand(String... args) {
        List<String> command = new ArrayList<>();
        command.add(adbPath);
        if (deviceSerial != null && !deviceSerial.isEmpty()) {
            command.add("-s");
            command.add(deviceSerial);
        }
        for (String arg : args) {
            command.add(arg);
        }
        return command;
    }

    /**
     * Reads an input stream to string.
     */
    private String readStream(InputStream is) throws IOException {
        if (is == null) return "";
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }
}
