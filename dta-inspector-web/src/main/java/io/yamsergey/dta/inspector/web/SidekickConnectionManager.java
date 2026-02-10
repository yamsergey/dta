package io.yamsergey.dta.inspector.web;

import io.yamsergey.dta.tools.android.cdp.CdpWatcherManager;
import io.yamsergey.dta.tools.android.inspect.compose.SidekickClient;
import io.yamsergey.dta.tools.sugar.Result;
import io.yamsergey.dta.tools.sugar.Success;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/**
 * Manages connections to sidekick instances on Android devices.
 */
@Component
public class SidekickConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(SidekickConnectionManager.class);

    private static final String ADB = "adb";
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private final Map<String, ConnectionInfo> connections = new ConcurrentHashMap<>();
    private int nextPort = 18640;

    public record Device(String serial, String state, String model, String product) {}
    public record SidekickSocket(String socketName, String packageName) {}
    public record ConnectionInfo(String packageName, String device, int port, SidekickClient client) {}

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
    public List<SidekickSocket> discoverSockets(String deviceSerial) throws IOException, InterruptedException {
        String output = runAdb(deviceSerial, "shell", "cat", "/proc/net/unix");

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

    /**
     * Gets or creates a connection to a sidekick instance.
     */
    public synchronized ConnectionInfo getConnection(String packageName, String device) throws Exception {
        String key = (device != null ? device : "default") + ":" + packageName;

        ConnectionInfo existing = connections.get(key);
        if (existing != null) {
            // Verify connection is still valid
            Result<String> health = existing.client().checkHealth();
            if (health instanceof Success) {
                log.debug("Reusing existing connection for {}", key);
                return existing;
            }
            // Connection stale, remove and recreate
            log.info("Connection stale for {}, reconnecting", key);
            connections.remove(key);
            removePortForward(device, existing.port());
        }

        // Create new connection
        int port = nextPort++;
        String socketName = "dta_sidekick_" + packageName;

        log.debug("Setting up port forward tcp:{} -> localabstract:{}", port, socketName);
        if (!setupPortForward(device, port, socketName)) {
            throw new RuntimeException("Failed to set up port forwarding for " + socketName);
        }

        SidekickClient client = SidekickClient.builder()
            .packageName(packageName)
            .port(port)
            .deviceSerial(device)
            .timeoutMs(30000)
            .build();

        // Verify connection
        Result<String> health = client.checkHealth();
        if (!(health instanceof Success)) {
            removePortForward(device, port);
            throw new RuntimeException("Failed to connect to sidekick: " + packageName);
        }

        log.info("New connection created for {} on port {}", key, port);
        ConnectionInfo conn = new ConnectionInfo(packageName, device, port, client);
        connections.put(key, conn);
        return conn;
    }

    /**
     * Takes a screenshot from the device.
     */
    public byte[] captureScreenshot(String device) throws IOException, InterruptedException {
        return runAdbBytes(device, "exec-out", "screencap", "-p");
    }

    /**
     * Executes a tap on the device.
     */
    public boolean tap(String device, int x, int y) throws IOException, InterruptedException {
        runAdb(device, "shell", "input", "tap", String.valueOf(x), String.valueOf(y));
        return true;
    }

    private boolean setupPortForward(String device, int port, String socketName) throws IOException, InterruptedException {
        runAdb(device, "forward", "tcp:" + port, "localabstract:" + socketName);
        return true;
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

    /**
     * Runs an ADB command, drains all output, and waits with a timeout.
     * Returns stdout as a String. Throws on timeout.
     */
    private String runAdb(String device, String... args) throws IOException, InterruptedException {
        return new String(runAdbBytes(device, args), StandardCharsets.UTF_8);
    }

    /**
     * Runs an ADB command, drains all output, and waits with a timeout.
     * Returns stdout as raw bytes. Throws on timeout.
     */
    private byte[] runAdbBytes(String device, String... args) throws IOException, InterruptedException {
        List<String> cmd = buildAdbCommand(device, args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try {
            byte[] data = process.getInputStream().readAllBytes();
            if (!process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("ADB command timed out: " + String.join(" ", cmd));
            }
            return data;
        } catch (IOException | InterruptedException e) {
            process.destroyForcibly();
            throw e;
        }
    }

    private String extractProp(String props, String name) {
        Pattern p = Pattern.compile(name + ":(\\S+)");
        Matcher m = p.matcher(props);
        return m.find() ? m.group(1) : null;
    }

    // ========================================================================
    // CDP Watcher Management
    // ========================================================================

    private static final int DEFAULT_CDP_PORT = 9222;

    /**
     * Starts CDP watching for Custom Tabs network traffic.
     *
     * @return true if watcher was started, false if already running
     */
    public boolean startCdpWatcher(String packageName, String device) throws Exception {
        // Ensure sidekick connection exists
        ConnectionInfo conn = getConnection(packageName, device);

        // Set up Chrome DevTools port forwarding
        log.debug("Setting up CDP port forward for device={}", device);
        setupCdpPortForward(device);

        // Start watcher
        return CdpWatcherManager.getInstance().startWatcher(
            packageName,
            device,
            DEFAULT_CDP_PORT,
            conn.client(),
            null  // No event callback for inspector-web
        );
    }

    /**
     * Stops CDP watching.
     *
     * @return true if watcher was stopped, false if not running
     */
    public boolean stopCdpWatcher(String packageName, String device) {
        return CdpWatcherManager.getInstance().stopWatcher(packageName, device);
    }

    /**
     * Checks if CDP watching is active.
     */
    public boolean isCdpWatching(String packageName, String device) {
        return CdpWatcherManager.getInstance().isWatching(packageName, device);
    }

    /**
     * Gets CDP watcher info.
     */
    public CdpWatcherManager.WatcherInfo getCdpWatcherInfo(String packageName, String device) {
        return CdpWatcherManager.getInstance().getWatcherInfo(packageName, device);
    }

    private void setupCdpPortForward(String device) throws IOException, InterruptedException {
        runAdb(device, "forward", "tcp:" + DEFAULT_CDP_PORT, "localabstract:chrome_devtools_remote");
    }
}
