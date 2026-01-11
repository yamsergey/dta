package io.yamsergey.adt.mcp.session;

import io.yamsergey.adt.tools.sugar.Failure;
import io.yamsergey.adt.tools.sugar.Result;
import io.yamsergey.adt.tools.sugar.Success;
import lombok.Getter;
import lombok.Setter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages device connections and app sessions for MCP server.
 *
 * <p>Supports multiple simultaneous sessions:
 * <ul>
 *   <li>Multiple devices (emulators/physical devices)</li>
 *   <li>Multiple apps per device</li>
 *   <li>Independent port forwarding per session</li>
 * </ul>
 *
 * <p>Example session topology:
 * <pre>
 * SessionManager
 *   └── emulator-5554
 *       ├── com.example.app1 (port 8400 → 8642)
 *       └── com.example.app2 (port 8401 → 8642)
 *   └── emulator-5556
 *       └── com.example.app3 (port 8402 → 8642)
 * </pre>
 */
public class SessionManager {

    private static final int BASE_PORT = 8400;
    private static final int DEFAULT_DEVICE_PORT = 8642;
    private static final int TIMEOUT_SECONDS = 30;

    private final Map<String, AppSession> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger portCounter = new AtomicInteger(0);

    @Getter
    @Setter
    private String adbPath = "adb";

    /**
     * Lists connected Android devices.
     *
     * @return Result with list of device serial numbers
     */
    public Result<List<String>> listDevices() {
        try {
            List<String> command = List.of(adbPath, "devices");
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            List<String> devices = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("\tdevice")) {
                        String serial = line.split("\t")[0];
                        devices.add(serial);
                    }
                }
            }

            process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (devices.isEmpty()) {
                return new Failure<>(null,
                        "No devices connected. Start an emulator with 'emulator -avd <name>' " +
                        "or connect a physical device with USB debugging enabled.");
            }

            return new Success<>(devices,
                    String.format("Found %d device(s). Use list_packages to see debuggable apps, " +
                                  "or attach_app to connect to a specific app.", devices.size()));

        } catch (Exception e) {
            return new Failure<>(e,
                    "Failed to list devices: " + e.getMessage() + ". " +
                    "Ensure ADB is installed and in PATH, or set adb_path parameter.");
        }
    }

    /**
     * Gets the first available device.
     *
     * @return Result with device serial or failure
     */
    public Result<String> getDefaultDevice() {
        Result<List<String>> devicesResult = listDevices();
        return switch (devicesResult) {
            case Success<List<String>> s -> new Success<>(s.value().get(0),
                    "Using device: " + s.value().get(0));
            case Failure<List<String>> f -> new Failure<>(f.cause(), f.description());
        };
    }

    /**
     * Creates a new app session with port forwarding.
     *
     * @param device device serial number
     * @param packageName package to attach
     * @param devicePort port where sidekick listens on device
     * @return Result with created session
     */
    public Result<AppSession> createSession(String device, String packageName, int devicePort) {
        String sessionId = AppSession.createId(device, packageName);

        // Check if session already exists
        if (sessions.containsKey(sessionId)) {
            AppSession existing = sessions.get(sessionId);
            return new Success<>(existing,
                    String.format("Session already exists for %s on %s. " +
                                  "Sidekick available at %s. " +
                                  "Use compose_tree or network_requests to inspect the app.",
                            packageName, device, existing.getSidekickUrl()));
        }

        int localPort = allocatePort();

        // Set up ADB port forwarding
        Result<Void> forwardResult = setupPortForward(device, localPort, devicePort);
        if (forwardResult instanceof Failure<Void> f) {
            return new Failure<>(f.cause(), f.description());
        }

        AppSession session = AppSession.builder()
                .id(sessionId)
                .device(device)
                .packageName(packageName)
                .localPort(localPort)
                .devicePort(devicePort)
                .build();

        sessions.put(sessionId, session);

        return new Success<>(session,
                String.format("Connected to %s on %s. " +
                              "Sidekick available at %s. " +
                              "Use compose_tree to inspect UI hierarchy, " +
                              "network_requests for HTTP traffic, or " +
                              "websocket_connections for WebSocket activity.",
                        packageName, device, session.getSidekickUrl()));
    }

    /**
     * Creates session with default device port.
     */
    public Result<AppSession> createSession(String device, String packageName) {
        return createSession(device, packageName, DEFAULT_DEVICE_PORT);
    }

    /**
     * Removes a session and cleans up port forwarding.
     *
     * @param device device serial
     * @param packageName package name
     * @return Result indicating success/failure
     */
    public Result<Void> removeSession(String device, String packageName) {
        String sessionId = AppSession.createId(device, packageName);
        AppSession session = sessions.remove(sessionId);

        if (session == null) {
            return new Failure<>(null,
                    String.format("No active session for %s on %s. " +
                                  "Use list_sessions to see active sessions.",
                            packageName, device));
        }

        // Remove port forwarding
        removePortForward(device, session.getLocalPort());

        return new Success<>(null,
                String.format("Disconnected from %s on %s. " +
                              "Use attach_app to connect to another app.",
                        packageName, device));
    }

    /**
     * Gets a session by device and package.
     *
     * @param device device serial
     * @param packageName package name
     * @return Result with session or failure
     */
    public Result<AppSession> getSession(String device, String packageName) {
        String sessionId = AppSession.createId(device, packageName);
        AppSession session = sessions.get(sessionId);

        if (session == null) {
            return new Failure<>(null,
                    String.format("No active session for %s on %s. " +
                                  "Use attach_app to connect first.",
                            packageName, device));
        }

        return new Success<>(session,
                String.format("Session active: %s on %s at %s",
                        packageName, device, session.getSidekickUrl()));
    }

    /**
     * Lists all active sessions.
     *
     * @return list of active sessions
     */
    public List<AppSession> listSessions() {
        return new ArrayList<>(sessions.values());
    }

    /**
     * Checks if there are any active sessions.
     */
    public boolean hasSessions() {
        return !sessions.isEmpty();
    }

    /**
     * Allocates a unique local port for forwarding.
     */
    private int allocatePort() {
        return BASE_PORT + portCounter.getAndIncrement();
    }

    /**
     * Sets up ADB port forwarding.
     */
    private Result<Void> setupPortForward(String device, int localPort, int devicePort) {
        try {
            List<String> command = new ArrayList<>();
            command.add(adbPath);
            command.add("-s");
            command.add(device);
            command.add("forward");
            command.add("tcp:" + localPort);
            command.add("tcp:" + devicePort);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                return new Failure<>(null,
                        "Failed to set up port forwarding: " + output + ". " +
                        "Ensure the device is connected and ADB has permissions.");
            }

            return new Success<>(null, "Port forwarding established");

        } catch (Exception e) {
            return new Failure<>(e,
                    "Failed to set up port forwarding: " + e.getMessage());
        }
    }

    /**
     * Removes ADB port forwarding.
     */
    private void removePortForward(String device, int localPort) {
        try {
            List<String> command = List.of(adbPath, "-s", device, "forward", "--remove", "tcp:" + localPort);
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.start().waitFor(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Best effort cleanup
        }
    }

    /**
     * Cleans up all sessions on shutdown.
     */
    public void cleanup() {
        for (AppSession session : sessions.values()) {
            removePortForward(session.getDevice(), session.getLocalPort());
        }
        sessions.clear();
    }
}
