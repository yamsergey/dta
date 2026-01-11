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
 * Lists WebSocket connections from an attached app.
 */
public class WebSocketConnectionsTool extends AdtTool {

    @Override
    public String getName() {
        return "websocket_connections";
    }

    @Override
    public String getDescription() {
        return "Lists WebSocket connections from an attached app. " +
               "Returns connection URLs, state, and message counts. " +
               "Use websocket_connection with an ID for full message history. " +
               "Requires attach_app first.";
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
                        },
                        "limit": {
                            "type": "integer",
                            "description": "Maximum number of connections to return (default: 50)."
                        },
                        "offset": {
                            "type": "integer",
                            "description": "Number of connections to skip (default: 0). Use with limit for pagination."
                        }
                    }
                }
                """;
    }

    @Override
    public Result<?> execute(Map<String, Object> args, SessionManager session) {
        String packageName = getStringParam(args, "package");
        String device = getStringParam(args, "device");
        int limit = getIntParam(args, "limit", 50);
        int offset = getIntParam(args, "offset", 0);

        // Get session
        Result<AppSession> sessionResult = resolveSession(session, device, packageName);
        if (sessionResult instanceof Failure<AppSession> f) {
            return f;
        }
        AppSession appSession = ((Success<AppSession>) sessionResult).value();

        return fetchWebSocketConnections(appSession, limit, offset);
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

    private Result<String> fetchWebSocketConnections(AppSession appSession, int limit, int offset) {
        try {
            URL url = new URL(appSession.getSidekickUrl() + "/websocket/connections");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");

            int responseCode = conn.getResponseCode();
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

            // Apply client-side pagination
            String jsonResponse = response.toString().trim();
            String paginatedResponse = applyPagination(jsonResponse, limit, offset);

            return new Success<>(paginatedResponse,
                    String.format("WebSocket connections (limit=%d, offset=%d). " +
                                  "Use websocket_connection with an ID for message history.",
                            limit, offset));

        } catch (java.net.ConnectException e) {
            return new Failure<>(e,
                    "Cannot connect to sidekick. Use attach_app to reconnect.");
        } catch (Exception e) {
            return new Failure<>(e,
                    "Failed to fetch WebSocket connections: " + e.getMessage());
        }
    }

    /**
     * Applies pagination to the JSON response.
     */
    @SuppressWarnings("unchecked")
    private String applyPagination(String jsonResponse, int limit, int offset) {
        try {
            Map<String, Object> data = JSON.readValue(jsonResponse, Map.class);
            List<Object> connections = (List<Object>) data.get("connections");

            if (connections == null || connections.isEmpty()) {
                return jsonResponse;
            }

            int total = connections.size();
            int fromIndex = Math.min(offset, total);
            int toIndex = Math.min(offset + limit, total);

            List<Object> paginated = connections.subList(fromIndex, toIndex);

            Map<String, Object> result = new java.util.HashMap<>();
            result.put("total", total);
            result.put("offset", offset);
            result.put("limit", limit);
            result.put("count", paginated.size());
            result.put("connections", paginated);

            return JSON.writeValueAsString(result);
        } catch (Exception e) {
            // If parsing fails, return original
            return jsonResponse;
        }
    }
}
