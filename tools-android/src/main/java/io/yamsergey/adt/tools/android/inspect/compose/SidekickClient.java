package io.yamsergey.adt.tools.android.inspect.compose;

import io.yamsergey.adt.tools.sugar.Failure;
import io.yamsergey.adt.tools.sugar.Result;
import io.yamsergey.adt.tools.sugar.Success;
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
 * <p>The sidekick server runs on a configurable port (default 8642) and provides
 * endpoints for inspecting the app's Compose UI hierarchy.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * SidekickClient client = SidekickClient.builder()
 *     .port(8642)
 *     .adbPath("adb")
 *     .deviceSerial("emulator-5554")
 *     .build();
 *
 * // Set up port forwarding first
 * client.setupPortForwarding();
 *
 * // Then make requests
 * Result&lt;String&gt; hierarchy = client.getComposeHierarchy();
 * </pre>
 */
@Builder
public class SidekickClient {

    private static final int DEFAULT_PORT = 8642;
    private static final int DEFAULT_TIMEOUT_MS = 30000;

    /**
     * Port where the sidekick server is running on the device.
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
     * Sets up ADB port forwarding to the sidekick server.
     *
     * @return Result indicating success or failure
     */
    public Result<Void> setupPortForwarding() {
        try {
            List<String> command = buildAdbCommand("forward", "tcp:" + port, "tcp:" + port);
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                String output = readStream(process.getInputStream());
                return new Failure<>(null, "Port forwarding failed: " + output);
            }

            return new Success<>(null, "Port forwarding established on port " + port);

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
     * Gets the Compose UI hierarchy from the sidekick server.
     *
     * @return Result containing hierarchy JSON on success
     */
    public Result<String> getComposeHierarchy() {
        return httpGet("/compose/hierarchy");
    }

    /**
     * Gets the Compose semantics tree from the sidekick server.
     *
     * @return Result containing semantics JSON on success
     */
    public Result<String> getComposeSemantics() {
        return httpGet("/compose/semantics");
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
                "Make sure the app is running and includes the adt-sidekick dependency.");
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
