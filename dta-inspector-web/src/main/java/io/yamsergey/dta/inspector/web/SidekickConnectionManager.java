package io.yamsergey.dta.inspector.web;

import io.yamsergey.dta.tools.android.inspect.compose.SidekickClient;
import io.yamsergey.dta.tools.sugar.Result;
import io.yamsergey.dta.tools.sugar.Success;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.*;

/**
 * Manages connections to sidekick instances on Android devices.
 */
@Component
public class SidekickConnectionManager {

    private static final String ADB = "adb";
    private final Map<String, ConnectionInfo> connections = new ConcurrentHashMap<>();
    private int nextPort = 18640;

    public record Device(String serial, String state, String model, String product) {}
    public record SidekickSocket(String socketName, String packageName) {}
    public record ConnectionInfo(String packageName, String device, int port, SidekickClient client) {}

    /**
     * Lists connected Android devices.
     */
    public List<Device> listDevices() throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(ADB, "devices", "-l");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = readStream(process.getInputStream());
        process.waitFor();

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
        List<String> cmd = buildAdbCommand(deviceSerial, "shell", "cat", "/proc/net/unix");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = readStream(process.getInputStream());
        process.waitFor();

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
                return existing;
            }
            // Connection stale, remove and recreate
            connections.remove(key);
            removePortForward(device, existing.port());
        }

        // Create new connection
        int port = nextPort++;
        String socketName = "dta_sidekick_" + packageName;

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

        ConnectionInfo conn = new ConnectionInfo(packageName, device, port, client);
        connections.put(key, conn);
        return conn;
    }

    /**
     * Takes a screenshot from the device.
     */
    public byte[] captureScreenshot(String device) throws IOException, InterruptedException {
        List<String> cmd = buildAdbCommand(device, "exec-out", "screencap", "-p");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Process process = pb.start();
        byte[] data = process.getInputStream().readAllBytes();
        process.waitFor();
        return data;
    }

    /**
     * Executes a tap on the device.
     */
    public boolean tap(String device, int x, int y) throws IOException, InterruptedException {
        List<String> cmd = buildAdbCommand(device, "shell", "input", "tap",
            String.valueOf(x), String.valueOf(y));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        return pb.start().waitFor() == 0;
    }

    private boolean setupPortForward(String device, int port, String socketName) throws IOException, InterruptedException {
        List<String> cmd = buildAdbCommand(device, "forward", "tcp:" + port, "localabstract:" + socketName);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        return pb.start().waitFor() == 0;
    }

    private void removePortForward(String device, int port) {
        try {
            List<String> cmd = buildAdbCommand(device, "forward", "--remove", "tcp:" + port);
            new ProcessBuilder(cmd).start().waitFor();
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

    private String extractProp(String props, String name) {
        Pattern p = Pattern.compile(name + ":(\\S+)");
        Matcher m = p.matcher(props);
        return m.find() ? m.group(1) : null;
    }

    private String readStream(InputStream is) throws IOException {
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }
}
