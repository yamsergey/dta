package io.yamsergey.dta.tools.android.inspect.compose;

import io.yamsergey.dta.tools.sugar.Failure;
import io.yamsergey.dta.tools.sugar.Result;
import io.yamsergey.dta.tools.sugar.Success;
import lombok.Builder;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                String output = readStream(process.getInputStream());
                return new Failure<>(null, "Port forwarding failed: " + output);
            }

            return new Success<>(null, "Port forwarding established: tcp:" + port + " -> " + socketName);

        } catch (Exception e) {
            return new Failure<>(null, "Failed to set up port forwarding: " + e.getMessage());
        }
    }

    /**
     * Removes ADB port forwarding.
     */
    public void removePortForwarding() {
        try {
            List<String> command = buildAdbCommand("forward", "--remove", "tcp:" + port);
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.start().waitFor();
        } catch (Exception e) {
            // Ignore cleanup errors
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
        return httpGet("/network/clear");
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
        return httpGet("/websocket/clear");
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
     * Gets the currently selected UI element.
     *
     * @return Result containing selected element JSON, or null if nothing selected
     */
    public Result<String> getSelectedElement() {
        return httpGet("/selection/element");
    }

    /**
     * Sets the selected UI element (highlights it on screen).
     *
     * @param elementJson JSON representation of the element to select
     * @return Result indicating success or failure
     */
    public Result<String> setSelectedElement(String elementJson) {
        return httpPost("/selection/element", elementJson);
    }

    /**
     * Clears the current element selection.
     *
     * @return Result indicating success or failure
     */
    public Result<String> clearSelectedElement() {
        return httpPost("/selection/element", "null");
    }

    // ========================================================================
    // Network request selection
    // ========================================================================

    /**
     * Gets the currently selected network request.
     *
     * @return Result containing selected request JSON, or null if nothing selected
     */
    public Result<String> getSelectedNetworkRequest() {
        return httpGet("/selection/network");
    }

    /**
     * Sets the selected network request.
     *
     * @param requestJson JSON representation of the request to select
     * @return Result indicating success or failure
     */
    public Result<String> setSelectedNetworkRequest(String requestJson) {
        return httpPost("/selection/network", requestJson);
    }

    /**
     * Clears the current network request selection.
     *
     * @return Result indicating success or failure
     */
    public Result<String> clearSelectedNetworkRequest() {
        return httpPost("/selection/network", "null");
    }

    // ========================================================================
    // WebSocket message selection
    // ========================================================================

    /**
     * Gets the currently selected WebSocket message.
     *
     * @return Result containing selected message JSON with connectionId, messageIndex, message
     */
    public Result<String> getSelectedWebSocketMessage() {
        return httpGet("/selection/websocket-message");
    }

    /**
     * Sets the selected WebSocket message.
     *
     * @param selectionJson JSON with { connectionId, messageIndex, message }
     * @return Result indicating success or failure
     */
    public Result<String> setSelectedWebSocketMessage(String selectionJson) {
        return httpPost("/selection/websocket-message", selectionJson);
    }

    /**
     * Clears the current WebSocket message selection.
     *
     * @return Result indicating success or failure
     */
    public Result<String> clearSelectedWebSocketMessage() {
        return httpPost("/selection/websocket-message", "null");
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
