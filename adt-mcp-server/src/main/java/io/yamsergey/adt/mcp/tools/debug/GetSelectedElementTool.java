package io.yamsergey.adt.mcp.tools.debug;

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
 * Gets the UI element currently selected in the Debug Server web UI.
 *
 * <p>This enables a collaborative workflow where:
 * <ol>
 *   <li>Developer opens Debug Server in browser</li>
 *   <li>Developer clicks on a UI element to select it</li>
 *   <li>Developer asks the agent about the element</li>
 *   <li>Agent calls this tool to get the selected element details</li>
 * </ol>
 */
public class GetSelectedElementTool extends AdtTool {

    @Override
    public String getName() {
        return "get_selected_element";
    }

    @Override
    public String getDescription() {
        return "Gets the UI element selected by the developer in the Debug Server web UI. " +
               "Use this when the developer says they've selected an element and wants you to " +
               "analyze it, explain it, or help modify it. " +
               "Requires: 1) start_debug_server, 2) attach_app to connect to the app.";
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

        // Check if debug server is running
        if (!session.isDebugServerRunning()) {
            return new Failure<>(null,
                    "Debug server is not running. Use start_debug_server first.");
        }

        // Resolve session to get session ID
        Result<AppSession> sessionResult = resolveSession(session, device, packageName);
        if (sessionResult instanceof Failure<AppSession> f) {
            return f;
        }
        AppSession appSession = ((Success<AppSession>) sessionResult).value();
        String sessionId = appSession.getDevice() + ":" + appSession.getPackageName();

        // Query debug server for selected element
        return fetchSelectedElement(session.getDebugServerUrl(), sessionId);
    }

    private Result<AppSession> resolveSession(SessionManager session, String device, String packageName) {
        List<AppSession> sessions = session.listSessions();

        if (sessions.isEmpty()) {
            return new Failure<>(null,
                    "No attached apps. Use attach_app first to connect to an app.");
        }

        if (sessions.size() == 1 && packageName == null && device == null) {
            AppSession only = sessions.get(0);
            return new Success<>(only,
                    String.format("Using attached app: %s on %s", only.getPackageName(), only.getDevice()));
        }

        if (device == null) {
            Result<String> deviceResult = session.getDefaultDevice();
            if (deviceResult instanceof Failure<String> f) {
                return new Failure<>(f.cause(), f.description());
            }
            device = ((Success<String>) deviceResult).value();
        }

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
                return new Success<>(deviceSessions.get(0), "Using attached app");
            }

            StringBuilder msg = new StringBuilder("Multiple apps attached. Specify package:\n");
            for (AppSession s : deviceSessions) {
                msg.append("  - ").append(s.getPackageName()).append("\n");
            }
            return new Failure<>(null, msg.toString());
        }

        return session.getSession(device, packageName);
    }

    private Result<String> fetchSelectedElement(String debugServerUrl, String sessionId) {
        try {
            URL url = new URL(debugServerUrl + "/api/sessions/" + sessionId + "/selected");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");

            int responseCode = conn.getResponseCode();
            if (responseCode == 404) {
                return new Failure<>(null,
                        "Session not found in debug server. Make sure the debug server " +
                        "is connected to the same app.");
            }
            if (responseCode != 200) {
                return new Failure<>(null,
                        String.format("Debug server returned %d.", responseCode));
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line).append("\n");
                }
            }
            conn.disconnect();

            String result = response.toString().trim();

            // Check if there's actually a selection
            if (result.contains("\"element\":null") || result.contains("\"element\": null")) {
                return new Failure<>(null,
                        "No element is currently selected. Ask the developer to click on " +
                        "an element in the Debug Server web UI.");
            }

            return new Success<>(result,
                    "Selected element from Debug Server. This is the element the developer " +
                    "clicked on in the web UI.");

        } catch (java.net.ConnectException e) {
            return new Failure<>(e,
                    "Cannot connect to debug server. Use start_debug_server first.");
        } catch (Exception e) {
            return new Failure<>(e,
                    "Failed to get selected element: " + e.getMessage());
        }
    }
}
