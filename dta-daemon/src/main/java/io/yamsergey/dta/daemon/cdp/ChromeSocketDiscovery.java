package io.yamsergey.dta.daemon.cdp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Utility for discovering Chrome DevTools sockets on Android devices.
 *
 * <p>Chrome and WebView expose debugging sockets as Unix domain sockets
 * in the abstract namespace. This class helps discover and connect to them.</p>
 *
 * <h3>Socket Types</h3>
 * <ul>
 *   <li>{@code chrome_devtools_remote} - Main Chrome browser</li>
 *   <li>{@code webview_devtools_remote_*} - WebViews in apps</li>
 *   <li>{@code *_devtools_remote} - Chrome Custom Tabs and other Chrome instances</li>
 * </ul>
 */
public final class ChromeSocketDiscovery {

    private static final Logger log = LoggerFactory.getLogger(ChromeSocketDiscovery.class);
    private static final String ADB = "adb";

    // Pattern to match DevTools sockets in /proc/net/unix output
    // Examples: @chrome_devtools_remote, @webview_devtools_remote_12345
    private static final Pattern DEVTOOLS_SOCKET_PATTERN =
        Pattern.compile("@((?:chrome_devtools_remote|webview_devtools_remote_\\d+|[\\w.]+_devtools_remote))");

    private ChromeSocketDiscovery() {}

    /**
     * Finds all Chrome DevTools sockets on the device.
     *
     * @param deviceSerial the device serial (or null for default device)
     * @return list of socket names (without @ prefix)
     * @throws IOException if adb command fails
     * @throws InterruptedException if interrupted
     */
    public static List<String> findDevToolsSockets(String deviceSerial)
            throws IOException, InterruptedException {

        // Use grep on-device to avoid transferring the entire /proc/net/unix table.
        // cat on large socket tables can hang adb/adbd, especially when the app is closing.
        List<String> cmd = buildAdbCommand(deviceSerial, "shell", "grep", "devtools_remote", "/proc/net/unix");
        String cmdStr = String.join(" ", cmd);
        log.debug("ADB exec: {}", cmdStr);
        long start = System.currentTimeMillis();

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // Read output in a separate thread to avoid blocking if the process hangs
        ExecutorService executor = Executors.newSingleThreadExecutor();
        String output;
        try {
            Future<String> future = executor.submit(() -> readStream(process.getInputStream()));
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                long elapsed = System.currentTimeMillis() - start;
                log.error("ADB timed out after {}ms: {}", elapsed, cmdStr);
                throw new IOException("ADB command timed out: " + cmdStr);
            }
            output = future.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            process.destroyForcibly();
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("Failed to read ADB output", cause);
        } catch (TimeoutException e) {
            process.destroyForcibly();
            long elapsed = System.currentTimeMillis() - start;
            log.error("ADB read timed out after {}ms: {}", elapsed, cmdStr);
            throw new IOException("Timed out reading ADB output: " + cmdStr);
        } finally {
            executor.shutdownNow();
        }

        long elapsed = System.currentTimeMillis() - start;
        Set<String> sockets = new HashSet<>();
        Matcher matcher = DEVTOOLS_SOCKET_PATTERN.matcher(output);

        while (matcher.find()) {
            sockets.add(matcher.group(1));
        }

        log.debug("Found {} DevTools sockets in {}ms", sockets.size(), elapsed);
        return new ArrayList<>(sockets);
    }

    /**
     * Finds the main Chrome DevTools socket.
     *
     * @param deviceSerial the device serial (or null for default device)
     * @return the socket name, or null if not found
     * @throws IOException if adb command fails
     * @throws InterruptedException if interrupted
     */
    public static String findChromeSocket(String deviceSerial)
            throws IOException, InterruptedException {

        List<String> sockets = findDevToolsSockets(deviceSerial);

        // Prefer chrome_devtools_remote
        for (String socket : sockets) {
            if ("chrome_devtools_remote".equals(socket)) {
                return socket;
            }
        }

        // Fall back to any chrome socket
        for (String socket : sockets) {
            if (socket.contains("chrome")) {
                return socket;
            }
        }

        return null;
    }

    /**
     * Finds WebView DevTools sockets.
     *
     * @param deviceSerial the device serial (or null for default device)
     * @return list of WebView socket names
     * @throws IOException if adb command fails
     * @throws InterruptedException if interrupted
     */
    public static List<String> findWebViewSockets(String deviceSerial)
            throws IOException, InterruptedException {

        List<String> sockets = findDevToolsSockets(deviceSerial);
        List<String> webviewSockets = new ArrayList<>();

        for (String socket : sockets) {
            if (socket.startsWith("webview_devtools_remote_")) {
                webviewSockets.add(socket);
            }
        }

        return webviewSockets;
    }

    /**
     * Sets up port forwarding from a local TCP port to a Chrome DevTools socket.
     *
     * @param deviceSerial the device serial (or null for default device)
     * @param localPort    the local TCP port to forward from
     * @param socketName   the socket name (without @ prefix)
     * @return true if forwarding was set up successfully
     * @throws IOException if adb command fails
     * @throws InterruptedException if interrupted
     */
    public static boolean setupPortForward(String deviceSerial, int localPort, String socketName)
            throws IOException, InterruptedException {

        List<String> cmd = buildAdbCommand(deviceSerial,
            "forward", "tcp:" + localPort, "localabstract:" + socketName);
        log.debug("Setting up port forward: tcp:{} -> localabstract:{}", localPort, socketName);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Process process = pb.start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            log.error("Port forwarding timed out: tcp:{} -> {}", localPort, socketName);
            throw new IOException("ADB port forwarding timed out after 30 seconds");
        }
        boolean success = process.exitValue() == 0;
        if (!success) {
            log.error("Port forwarding failed (exit {}): tcp:{} -> {}", process.exitValue(), localPort, socketName);
        }
        return success;
    }

    /**
     * Removes port forwarding.
     *
     * @param deviceSerial the device serial (or null for default device)
     * @param localPort    the local TCP port
     */
    public static void removePortForward(String deviceSerial, int localPort) {
        try {
            List<String> cmd = buildAdbCommand(deviceSerial,
                "forward", "--remove", "tcp:" + localPort);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            Process process = pb.start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (Exception ignored) {
            // Ignore cleanup errors
        }
    }

    /**
     * Finds an available local port for forwarding.
     *
     * @return an available port number
     * @throws IOException if no port is available
     */
    public static int findAvailablePort() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * Sets up port forwarding and returns the local port.
     * Automatically finds an available port.
     *
     * @param deviceSerial the device serial (or null for default device)
     * @param socketName   the socket name (without @ prefix)
     * @return the local port that was set up, or -1 if setup failed
     * @throws IOException if adb command fails
     * @throws InterruptedException if interrupted
     */
    public static int setupPortForwardAuto(String deviceSerial, String socketName)
            throws IOException, InterruptedException {

        int port = findAvailablePort();
        if (setupPortForward(deviceSerial, port, socketName)) {
            return port;
        }
        return -1;
    }

    private static List<String> buildAdbCommand(String deviceSerial, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ADB);
        if (deviceSerial != null && !deviceSerial.isEmpty()) {
            cmd.add("-s");
            cmd.add(deviceSerial);
        }
        for (String arg : args) {
            cmd.add(arg);
        }
        return cmd;
    }

    private static String readStream(java.io.InputStream is) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        }
    }
}
