package io.yamsergey.adt.mcp.tools.app;

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

/**
 * Fetches the Compose UI hierarchy from an attached app.
 *
 * <p>Returns a tree structure showing all Compose composables with:
 * <ul>
 *   <li>Composable names and types</li>
 *   <li>Bounds (screen coordinates)</li>
 *   <li>Semantics IDs for accessibility</li>
 *   <li>Text content where applicable</li>
 * </ul>
 *
 * <p>Requires attach_app to be called first.
 */
public class ComposeTreeTool extends AdtTool {

    @Override
    public String getName() {
        return "compose_tree";
    }

    @Override
    public String getDescription() {
        return "PRIMARY TOOL for understanding Android UI. Returns the Compose hierarchy with " +
               "composable names, bounds (coordinates), text content, and semantics IDs. " +
               "Use this FIRST to understand layout, find tap coordinates, or analyze UI structure. " +
               "Much more useful than screenshot for programmatic interaction. Requires attach_app first.";
    }

    @Override
    public String getInputSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "package": {
                            "type": "string",
                            "description": "Package name of the attached app. If not specified and only one app is attached, uses that."
                        },
                        "device": {
                            "type": "string",
                            "description": "Device serial number. If not specified, uses first available device."
                        }
                    }
                }
                """;
    }

    @Override
    public Result<?> execute(Map<String, Object> args, SessionManager session) {
        String packageName = getStringParam(args, "package");
        String device = getStringParam(args, "device");

        // Get session
        Result<AppSession> sessionResult = resolveSession(session, device, packageName);
        if (sessionResult instanceof Failure<AppSession> f) {
            return f;
        }
        AppSession appSession = ((Success<AppSession>) sessionResult).value();

        // Fetch compose tree from sidekick
        return fetchComposeTree(appSession);
    }

    /**
     * Resolves the session, handling defaults when only one session exists.
     */
    private Result<AppSession> resolveSession(SessionManager session, String device, String packageName) {
        List<AppSession> sessions = session.listSessions();

        if (sessions.isEmpty()) {
            return new Failure<>(null,
                    "No attached apps. Use attach_app first to connect to an app.");
        }

        // If only one session and no specific parameters, use it
        if (sessions.size() == 1 && packageName == null && device == null) {
            AppSession only = sessions.get(0);
            return new Success<>(only,
                    String.format("Using attached app: %s on %s", only.getPackageName(), only.getDevice()));
        }

        // Get device
        if (device == null) {
            Result<String> deviceResult = session.getDefaultDevice();
            if (deviceResult instanceof Failure<String> f) {
                return new Failure<>(f.cause(), f.description());
            }
            device = ((Success<String>) deviceResult).value();
        }

        // If package not specified, check if there's only one app on this device
        if (packageName == null) {
            String finalDevice = device;
            List<AppSession> deviceSessions = sessions.stream()
                    .filter(s -> s.getDevice().equals(finalDevice))
                    .toList();

            if (deviceSessions.isEmpty()) {
                return new Failure<>(null,
                        String.format("No attached apps on %s. Use attach_app first.", device));
            }
            if (deviceSessions.size() == 1) {
                AppSession only = deviceSessions.get(0);
                return new Success<>(only,
                        String.format("Using attached app: %s", only.getPackageName()));
            }

            // Multiple apps, need to specify
            StringBuilder msg = new StringBuilder("Multiple apps attached on " + device + ". Specify package:\n");
            for (AppSession s : deviceSessions) {
                msg.append("  - ").append(s.getPackageName()).append("\n");
            }
            return new Failure<>(null, msg.toString());
        }

        return session.getSession(device, packageName);
    }

    /**
     * Fetches the compose tree from sidekick HTTP endpoint.
     */
    private Result<String> fetchComposeTree(AppSession appSession) {
        try {
            URL url = new URL(appSession.getSidekickUrl() + "/compose/tree");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                return new Failure<>(null,
                        String.format("Sidekick returned %d. The app may have crashed or restarted. " +
                                      "Use attach_app to reconnect.", responseCode));
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line).append("\n");
                }
            }
            conn.disconnect();

            String tree = response.toString().trim();
            return new Success<>(tree,
                    String.format("Compose tree for %s. The tree shows the UI hierarchy with " +
                                  "composable names, bounds, and semantics IDs. Use tap with coordinates " +
                                  "to interact with elements.", appSession.getPackageName()));

        } catch (java.net.ConnectException e) {
            return new Failure<>(e,
                    "Cannot connect to sidekick. The app may have crashed. " +
                    "Check if the app is still running and use attach_app to reconnect.");
        } catch (Exception e) {
            return new Failure<>(e,
                    "Failed to fetch compose tree: " + e.getMessage());
        }
    }
}
