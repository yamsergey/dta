package io.yamsergey.dta.daemon.sidekick;

import io.yamsergey.dta.daemon.cdp.CdpWatcherManager;
import io.yamsergey.dta.tools.sugar.Result;
import io.yamsergey.dta.tools.sugar.Success;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * Singleton manager for sidekick connections to Android devices.
 *
 * <p>Prevents duplicate ADB port forwards across threads (in-process)
 * and across JVM processes (cross-process) by checking {@code adb forward --list}
 * before creating new forwards.</p>
 *
 * <p>Usage:</p>
 * <pre>
 * var conn = SidekickConnectionManager.getInstance().getConnection("com.example.app", deviceSerial);
 * var client = conn.client();
 * </pre>
 */
public class SidekickConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(SidekickConnectionManager.class);
    private static final SidekickConnectionManager INSTANCE = new SidekickConnectionManager();

    private static volatile String ADB = findAdb();
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final long RECONNECT_COOLDOWN_MS = 10_000;

    private final ConcurrentHashMap<String, ConnectionInfo> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> connectionLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> failedConnectionTimestamps = new ConcurrentHashMap<>();

    private SidekickConnectionManager() {}

    /**
     * Finds the ADB executable. Checks ANDROID_HOME/ANDROID_SDK_ROOT env vars first,
     * then falls back to "adb" on PATH.
     */
    private static String findAdb() {
        for (String envVar : new String[]{"ANDROID_HOME", "ANDROID_SDK_ROOT"}) {
            String sdk = System.getenv(envVar);
            if (sdk != null && !sdk.isEmpty()) {
                java.io.File adb = new java.io.File(sdk, "platform-tools" + java.io.File.separator + "adb");
                if (adb.exists()) {
                    log.info("Using ADB from {}: {}", envVar, adb.getAbsolutePath());
                    return adb.getAbsolutePath();
                }
            }
        }
        // macOS common location
        java.io.File macAdb = new java.io.File(System.getProperty("user.home"), "Library/Android/sdk/platform-tools/adb");
        if (macAdb.exists()) {
            return macAdb.getAbsolutePath();
        }
        // Fallback to PATH
        return "adb";
    }

    public static SidekickConnectionManager getInstance() {
        return INSTANCE;
    }

    /**
     * Overrides the auto-detected ADB path.
     * Call before any device operations (e.g. from an IDE plugin that knows the SDK location).
     */
    public static void setAdbPath(String path) {
        ADB = path;
        log.info("ADB path set to: {}", path);
    }

    // ========================================================================
    // Records
    // ========================================================================

    public record ConnectionInfo(String packageName, String device, int port, SidekickClient client, String sidekickVersion) {}
    public record Device(String serial, String state, String model, String product) {}
    public record SidekickSocket(String socketName, String packageName) {}

    // ========================================================================
    // Connection management
    // ========================================================================

    /**
     * Gets or creates a connection to a sidekick instance.
     *
     * <p>Deduplicates port forwards both within-process (via in-memory cache)
     * and cross-process (via {@code adb forward --list}).</p>
     *
     * @param packageName the Android package name
     * @param device device serial, or null for default device
     * @return connection info with a live client
     * @throws Exception if connection fails
     */
    public ConnectionInfo getConnection(String packageName, String device) throws Exception {
        String key = (device != null ? device : "default") + ":" + packageName;

        // Check cooldown before acquiring lock to fail fast
        Long failedAt = failedConnectionTimestamps.get(key);
        if (failedAt != null) {
            long elapsed = System.currentTimeMillis() - failedAt;
            if (elapsed < RECONNECT_COOLDOWN_MS) {
                long retryInSeconds = (RECONNECT_COOLDOWN_MS - elapsed + 999) / 1000;
                log.debug("Connection cooldown active for {} ({}s remaining)", key, retryInSeconds);
                throw new RuntimeException(
                    "Connection to " + packageName + " failed recently, retry in " + retryInSeconds + "s");
            }
            failedConnectionTimestamps.remove(key);
        }

        Object lock = connectionLocks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            // 1. Check in-memory cache
            ConnectionInfo existing = connections.get(key);
            if (existing != null) {
                Result<String> health = existing.client().checkHealth();
                if (health instanceof Success) {
                    log.debug("Reusing cached connection for {}", key);
                    return existing;
                }
                log.warn("Cached connection stale for {}: {}", key, health);
                connections.remove(key);
                removePortForward(device, existing.port());
                stopCdpWatcher(packageName, device);
            }

            String socketName = "dta_sidekick_" + packageName;

            // Create new forward with auto-allocated port.
            // We intentionally skip checking adb forward --list because multiple
            // processes (inspector-web, MCP) may have their own forwards to the
            // same socket. Reusing or removing another process's forward breaks it.
            int port;
            try {
                port = setupPortForwardAuto(device, socketName);
            } catch (Exception e) {
                failedConnectionTimestamps.put(key, System.currentTimeMillis());
                throw e;
            }
            log.debug("Port forward established tcp:{} -> localabstract:{}", port, socketName);

            SidekickClient client = SidekickClient.builder()
                .packageName(packageName)
                .port(port)
                .deviceSerial(device)
                .timeoutMs(30000)
                .build();

            // Verify connection
            Result<String> health = client.checkHealth();
            if (!(health instanceof Success)) {
                log.error("New connection health check failed for {} on port {}: {}", key, port, health);
                removePortForward(device, port);
                failedConnectionTimestamps.put(key, System.currentTimeMillis());
                throw new RuntimeException("Failed to connect to sidekick: " + packageName);
            }

            String sidekickVersion = getSidekickVersion(client);
            failedConnectionTimestamps.remove(key);
            log.info("New connection created for {} on port {}", key, port);
            ConnectionInfo conn = new ConnectionInfo(packageName, device, port, client, sidekickVersion);
            connections.put(key, conn);
            return conn;
        }
    }

    /**
     * Removes a cached connection and its port forward.
     */
    public void removeConnection(String packageName, String device) {
        String key = (device != null ? device : "default") + ":" + packageName;
        ConnectionInfo conn = connections.remove(key);
        if (conn != null) {
            removePortForward(device, conn.port());
            stopCdpWatcher(packageName, device);
        }
    }

    // ========================================================================
    // Device and socket discovery
    // ========================================================================

    /**
     * Lists connected Android devices.
     */
    public List<Device> listDevices() throws IOException, InterruptedException {
        String output = runAdb(null, "devices", "-l");

        List<Device> devices = new ArrayList<>();
        Pattern pattern = Pattern.compile("^(\\S+)\\s+(device|offline|unauthorized)\\s*(.*)$", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(output);

        while (matcher.find()) {
            String serial = matcher.group(1);
            String state = matcher.group(2);
            String props = matcher.group(3);

            String model = extractProp(props, "model");
            String product = extractProp(props, "product");

            devices.add(new Device(serial, state, model, product));
        }

        return devices;
    }

    /**
     * Discovers sidekick sockets on a device.
     */
    public List<SidekickSocket> findSidekickSockets(String device) throws IOException, InterruptedException {
        // Use grep on-device to avoid transferring the entire /proc/net/unix table.
        // cat on large socket tables can hang adb/adbd, especially when the app is closing.
        String output = runAdb(device, "shell", "grep", "dta_sidekick", "/proc/net/unix");

        List<SidekickSocket> sockets = new ArrayList<>();
        Pattern pattern = Pattern.compile("@(dta_sidekick_([\\w.]+))");
        Matcher matcher = pattern.matcher(output);

        Set<String> seen = new HashSet<>();
        while (matcher.find()) {
            String socketName = matcher.group(1);
            String packageName = matcher.group(2);
            if (seen.add(socketName)) {
                sockets.add(new SidekickSocket(socketName, packageName));
            }
        }

        return sockets;
    }

    // ========================================================================
    // ADB device interaction
    // ========================================================================

    /**
     * Takes a screenshot from the device.
     */
    public byte[] captureScreenshot(String device) throws IOException, InterruptedException {
        return runAdbBytes(device, DEFAULT_TIMEOUT_SECONDS, "exec-out", "screencap", "-p");
    }

    /**
     * Taps at screen coordinates.
     */
    public boolean tap(String device, int x, int y) throws IOException, InterruptedException {
        runAdb(device, "shell", "input", "tap", String.valueOf(x), String.valueOf(y));
        return true;
    }

    /**
     * Swipes from one point to another.
     */
    public boolean swipe(String device, int x1, int y1, int x2, int y2, int durationMs) throws IOException, InterruptedException {
        runAdb(device, "shell", "input", "swipe",
            String.valueOf(x1), String.valueOf(y1), String.valueOf(x2), String.valueOf(y2), String.valueOf(durationMs));
        return true;
    }

    /**
     * Inputs text on the device.
     */
    public boolean inputText(String device, String text) throws IOException, InterruptedException {
        String escaped = text.replace(" ", "%s").replace("\"", "\\\"").replace("'", "\\'");
        runAdb(device, "shell", "input", "text", escaped);
        return true;
    }

    /**
     * Presses a key on the device.
     */
    public boolean pressKey(String device, String keyCode) throws IOException, InterruptedException {
        String code = switch (keyCode.toUpperCase()) {
            case "BACK" -> "4";
            case "HOME" -> "3";
            case "MENU" -> "82";
            case "ENTER" -> "66";
            case "TAB" -> "61";
            case "DEL", "DELETE" -> "67";
            case "SPACE" -> "62";
            default -> keyCode;
        };
        runAdb(device, "shell", "input", "keyevent", code);
        return true;
    }

    /**
     * Sets up port forwarding for Chrome DevTools Protocol.
     */
    public void setupCdpPortForward(String device, int port) throws IOException, InterruptedException {
        runAdb(device, "forward", "tcp:" + port, "localabstract:chrome_devtools_remote");
    }

    /**
     * Removes port forwarding for Chrome DevTools Protocol.
     */
    public void removeCdpPortForward(String device, int port) {
        removePortForward(device, port);
    }

    // ========================================================================
    // Version compatibility
    // ========================================================================

    /**
     * Checks if the tool version is compatible with sidekick version.
     * Major and minor versions must match.
     */
    public static boolean isVersionCompatible(String toolVersion, String sidekickVersion) {
        if (toolVersion == null || sidekickVersion == null) {
            return true;
        }
        if ("unknown".equals(toolVersion) || "unknown".equals(sidekickVersion)) {
            return true;
        }

        String[] tool = toolVersion.split("\\.");
        String[] sidekick = sidekickVersion.split("\\.");

        if (tool.length < 2 || sidekick.length < 2) {
            return true;
        }

        return tool[0].equals(sidekick[0]) && tool[1].equals(sidekick[1]);
    }

    // ========================================================================
    // APK install and activity launch
    // ========================================================================

    /**
     * Installs an APK on the device. Uses a longer timeout (120s) since installs can be slow.
     */
    public String installApk(String device, String apkPath) throws IOException, InterruptedException {
        return runAdbWithTimeout(device, 120, "install", "-r", apkPath);
    }

    /**
     * Resolves the main launcher activity for a package.
     * Parses output of {@code cmd package resolve-activity --brief}.
     *
     * @return fully qualified activity name (e.g. "com.example/.MainActivity")
     */
    public String resolveMainActivity(String device, String packageName) throws IOException, InterruptedException {
        String output = runAdb(device, "shell", "cmd", "package", "resolve-activity",
            "--brief", packageName);
        // Output format: two lines, last line is "pkg/activity"
        String[] lines = output.trim().split("\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.contains("/")) {
                return line;
            }
        }
        throw new IOException("Could not resolve main activity for " + packageName + ": " + output);
    }

    /**
     * Launches an activity on the device.
     *
     * @param component fully qualified component (e.g. "com.example/.MainActivity")
     */
    public String launchActivity(String device, String component) throws IOException, InterruptedException {
        return runAdb(device, "shell", "am", "start", "-n", component);
    }

    // ========================================================================
    // Private ADB internals
    // ========================================================================

    private String getSidekickVersion(SidekickClient client) {
        Result<HealthResponse> healthResult = client.checkHealthTyped();
        if (healthResult instanceof Success<HealthResponse> success) {
            return success.value().version();
        }
        return null;
    }

    private int setupPortForwardAuto(String device, String socketName) throws IOException, InterruptedException {
        String output = runAdb(device, "forward", "tcp:0", "localabstract:" + socketName);
        return Integer.parseInt(output.trim());
    }


    private void stopCdpWatcher(String packageName, String device) {
        try {
            if (CdpWatcherManager.getInstance().stopWatcher(packageName, device)) {
                log.info("Stopped CDP watcher for {} (sidekick connection lost)", packageName);
            }
        } catch (Exception e) {
            log.debug("Failed to stop CDP watcher: {}", e.getMessage());
        }
    }

    private void removePortForward(String device, int port) {
        try {
            runAdb(device, "forward", "--remove", "tcp:" + port);
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    private List<String> buildAdbCommand(String device, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ADB);
        if (device != null && !device.isEmpty()) {
            cmd.add("-s");
            cmd.add(device);
        }
        cmd.addAll(Arrays.asList(args));
        return cmd;
    }

    private String runAdb(String device, String... args) throws IOException, InterruptedException {
        return new String(runAdbBytes(device, DEFAULT_TIMEOUT_SECONDS, args), StandardCharsets.UTF_8);
    }

    private String runAdbWithTimeout(String device, int timeoutSeconds, String... args) throws IOException, InterruptedException {
        return new String(runAdbBytes(device, timeoutSeconds, args), StandardCharsets.UTF_8);
    }

    private byte[] runAdbBytes(String device, int timeoutSeconds, String... args) throws IOException, InterruptedException {
        List<String> cmd = buildAdbCommand(device, args);
        String cmdStr = String.join(" ", cmd);
        log.debug("ADB exec: {}", cmdStr);
        long start = System.currentTimeMillis();

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<byte[]> future = executor.submit(
                () -> process.getInputStream().readAllBytes()
            );
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                long elapsed = System.currentTimeMillis() - start;
                log.error("ADB timed out after {}ms: {}", elapsed, cmdStr);
                throw new IOException("ADB command timed out: " + cmdStr);
            }
            byte[] data = future.get(5, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - start;
            log.debug("ADB done in {}ms ({} bytes): {}", elapsed, data.length, cmdStr);
            return data;
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
        } catch (IOException | InterruptedException e) {
            process.destroyForcibly();
            long elapsed = System.currentTimeMillis() - start;
            log.error("ADB failed after {}ms: {} - {}", elapsed, cmdStr, e.getMessage());
            throw e;
        } finally {
            executor.shutdownNow();
        }
    }

    private String extractProp(String props, String name) {
        Pattern p = Pattern.compile(name + ":(\\S+)");
        Matcher m = p.matcher(props);
        return m.find() ? m.group(1) : null;
    }
}
