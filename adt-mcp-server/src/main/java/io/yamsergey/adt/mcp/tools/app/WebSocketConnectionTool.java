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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Gets details of a specific WebSocket connection including message history.
 */
public class WebSocketConnectionTool extends AdtTool {

    @Override
    public String getName() {
        return "websocket_connection";
    }

    @Override
    public String getDescription() {
        return "Gets details of a specific WebSocket connection including full message history. " +
               "Use websocket_connections first to get connection IDs. " +
               "Requires attach_app first.";
    }

    @Override
    public String getInputSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "id": {
                            "type": "string",
                            "description": "Connection ID from websocket_connections."
                        },
                        "package": {
                            "type": "string",
                            "description": "Package name of the attached app. If not specified and only one app is attached, uses that."
                        },
                        "device": {
                            "type": "string",
                            "description": "Device serial number. If not specified, uses first available device."
                        }
                    },
                    "required": ["id"]
                }
                """;
    }

    @Override
    public Result<?> execute(Map<String, Object> args, SessionManager session) {
        String connectionId = getStringParam(args, "id");
        String packageName = getStringParam(args, "package");
        String device = getStringParam(args, "device");

        if (connectionId == null || connectionId.isEmpty()) {
            return new Failure<>(null,
                    "Missing required parameter: id. Use websocket_connections to get connection IDs.");
        }

        // Get session
        Result<AppSession> sessionResult = resolveSession(session, device, packageName);
        if (sessionResult instanceof Failure<AppSession> f) {
            return f;
        }
        AppSession appSession = ((Success<AppSession>) sessionResult).value();

        return fetchWebSocketConnection(appSession, connectionId);
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

    private Result<String> fetchWebSocketConnection(AppSession appSession, String connectionId) {
        try {
            String encodedId = URLEncoder.encode(connectionId, StandardCharsets.UTF_8);
            URL url = new URL(appSession.getSidekickUrl() + "/websocket/connections/" + encodedId);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");

            int responseCode = conn.getResponseCode();
            if (responseCode == 404) {
                return new Failure<>(null,
                        "Connection not found. Use websocket_connections to get valid IDs.");
            }
            if (responseCode != 200) {
                return new Failure<>(null,
                        String.format("Sidekick returned %d. Use attach_app to reconnect.", responseCode));
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line).append("\n");
                }
            }
            conn.disconnect();

            return new Success<>(response.toString().trim(),
                    "WebSocket connection details with message history.");

        } catch (java.net.ConnectException e) {
            return new Failure<>(e,
                    "Cannot connect to sidekick. Use attach_app to reconnect.");
        } catch (Exception e) {
            return new Failure<>(e,
                    "Failed to fetch WebSocket connection: " + e.getMessage());
        }
    }
}
