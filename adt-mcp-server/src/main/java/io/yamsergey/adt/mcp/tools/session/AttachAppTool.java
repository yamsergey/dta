package io.yamsergey.adt.mcp.tools.session;

import io.yamsergey.adt.mcp.session.AppSession;
import io.yamsergey.adt.mcp.session.SessionManager;
import io.yamsergey.adt.mcp.tools.AdtTool;
import io.yamsergey.adt.tools.sugar.Failure;
import io.yamsergey.adt.tools.sugar.Result;
import io.yamsergey.adt.tools.sugar.Success;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Attaches to an app via sidekick for UI and network inspection.
 *
 * <p>This tool:
 * <ol>
 *   <li>Optionally restarts the app (if restart=true or app not running)</li>
 *   <li>Sets up ADB port forwarding to sidekick server (port 8642)</li>
 *   <li>Creates a session for subsequent tool calls</li>
 * </ol>
 *
 * <p>Note: The app must include the 'adt-sidekick' SDK which auto-starts
 * the inspection server via AndroidX Startup when the app launches.
 */
public class AttachAppTool extends AdtTool {

    private static final int DEFAULT_SIDEKICK_PORT = 8642;
    private static final int SIDEKICK_STARTUP_TIMEOUT_MS = 10000;
    private static final int SIDEKICK_POLL_INTERVAL_MS = 200;
    private static final int APP_LAUNCH_DELAY_MS = 2000;

    @Override
    public String getName() {
        return "attach_app";
    }

    @Override
    public String getDescription() {
        return "Connects to a debuggable Android app for inspection. Call this FIRST before compose_tree or network_requests. " +
               "By default connects to running app (fast). If connection fails, retry with restart=true to force app restart. " +
               "The app must include the sidekick SDK in its debug build.";
    }

    @Override
    public String getInputSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "package": {
                            "type": "string",
                            "description": "Package name of the app to attach (e.g., 'com.example.myapp')."
                        },
                        "device": {
                            "type": "string",
                            "description": "Device serial number. If not specified, uses first available device."
                        },
                        "restart": {
                            "type": "boolean",
                            "description": "Default: false. Only set to true if normal attach fails. Forces app restart which is slower but recovers from bad states."
                        }
                    },
                    "required": ["package"]
                }
                """;
    }

    @Override
    public Result<?> execute(Map<String, Object> args, SessionManager session) {
        String packageName = getStringParam(args, "package");
        String device = getStringParam(args, "device");
        boolean restart = getBoolParam(args, "restart", false);

        if (packageName == null || packageName.isEmpty()) {
            return new Failure<>(null,
                    "Missing required parameter: package. " +
                    "Use list_packages to see available debuggable apps.");
        }

        // Get device
        if (device == null) {
            Result<String> deviceResult = session.getDefaultDevice();
            if (deviceResult instanceof Failure<String> f) {
                return f;
            }
            device = ((Success<String>) deviceResult).value();
        }

        // Check if already attached (and not restarting)
        if (!restart) {
            Result<AppSession> existingSession = session.getSession(device, packageName);
            if (existingSession instanceof Success<AppSession>) {
                return existingSession; // Already attached, return existing session
            }
        } else {
            // If restarting, remove existing session first
            session.removeSession(device, packageName);
        }

        // Handle app state based on restart flag
        Result<Integer> pidResult = getAppPid(session.getAdbPath(), device, packageName);
        boolean appRunning = pidResult instanceof Success<Integer>;

        if (restart) {
            // Force restart the app
            Result<Void> restartResult = restartApp(session.getAdbPath(), device, packageName);
            if (restartResult instanceof Failure<Void> f) {
                return f;
            }
        } else if (!appRunning) {
            // App not running and restart not requested - launch it
            Result<Void> launchResult = launchApp(session.getAdbPath(), device, packageName);
            if (launchResult instanceof Failure<Void> f) {
                return new Failure<>(f.cause(),
                        f.description() + " Or use restart=true to force launch.");
            }
        }

        // Create session (sets up port forwarding)
        Result<AppSession> sessionResult = session.createSession(device, packageName, DEFAULT_SIDEKICK_PORT);
        if (sessionResult instanceof Failure<AppSession> f) {
            return f;
        }

        AppSession appSession = ((Success<AppSession>) sessionResult).value();

        // Wait for sidekick to be ready
        Result<Void> readyResult = waitForSidekick(appSession.getSidekickUrl());
        if (readyResult instanceof Failure<Void> f) {
            // Cleanup on failure
            session.removeSession(device, packageName);
            return new Failure<>(f.cause(),
                    f.description() + " The app must include the 'adt-sidekick' SDK. " +
                    "Try restart=true if the app is in a bad state.");
        }

        return sessionResult;
    }

    /**
     * Gets the PID of a running app.
     */
    private Result<Integer> getAppPid(String adbPath, String device, String packageName) {
        try {
            List<String> command = List.of(
                    adbPath, "-s", device, "shell",
                    "pidof", "-s", packageName
            );

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line.trim());
                }
            }

            process.waitFor(10, TimeUnit.SECONDS);

            String pidStr = output.toString().trim();
            if (pidStr.isEmpty()) {
                return new Failure<>(null,
                        String.format("App %s is not running.", packageName));
            }

            try {
                int pid = Integer.parseInt(pidStr);
                return new Success<>(pid, "Found app PID: " + pid);
            } catch (NumberFormatException e) {
                return new Failure<>(null,
                        "Failed to parse app PID: " + pidStr);
            }

        } catch (Exception e) {
            return new Failure<>(e,
                    "Failed to get app PID: " + e.getMessage());
        }
    }

    /**
     * Restarts an app by force stopping and launching.
     */
    private Result<Void> restartApp(String adbPath, String device, String packageName) {
        // Force stop
        try {
            List<String> stopCommand = List.of(
                    adbPath, "-s", device, "shell",
                    "am", "force-stop", packageName
            );
            ProcessBuilder pb = new ProcessBuilder(stopCommand);
            pb.start().waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Ignore stop errors, app might not be running
        }

        // Small delay to let the system clean up
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Launch the app
        return launchApp(adbPath, device, packageName);
    }

    /**
     * Launches an app using monkey (starts the launcher activity).
     */
    private Result<Void> launchApp(String adbPath, String device, String packageName) {
        try {
            // Use monkey to launch - it finds and starts the launcher activity
            List<String> command = List.of(
                    adbPath, "-s", device, "shell",
                    "monkey", "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1"
            );

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new Failure<>(null, "App launch timed out.");
            }

            String outputStr = output.toString();
            if (outputStr.contains("No activities found")) {
                return new Failure<>(null,
                        String.format("No launcher activity found for %s. Is the app installed?", packageName));
            }

            // Wait for app to start and sidekick to initialize
            Thread.sleep(APP_LAUNCH_DELAY_MS);

            return new Success<>(null, "App launched: " + packageName);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Failure<>(e, "Interrupted while launching app");
        } catch (Exception e) {
            return new Failure<>(e, "Failed to launch app: " + e.getMessage());
        }
    }

    /**
     * Waits for sidekick HTTP server to become responsive.
     */
    private Result<Void> waitForSidekick(String sidekickUrl) {
        long startTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - startTime < SIDEKICK_STARTUP_TIMEOUT_MS) {
            try {
                URL url = new URL(sidekickUrl + "/health");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(1000);
                conn.setReadTimeout(1000);
                conn.setRequestMethod("GET");

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    return new Success<>(null, "Sidekick is ready");
                }
                conn.disconnect();

            } catch (Exception e) {
                // Not ready yet, keep polling
            }

            try {
                Thread.sleep(SIDEKICK_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Failure<>(e, "Interrupted while waiting for sidekick");
            }
        }

        return new Failure<>(null,
                "Sidekick did not start within timeout. " +
                "Check if the app is running and has sidekick library included.");
    }
}
