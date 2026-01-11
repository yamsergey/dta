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
 * Fetches captured HTTP requests from an attached app.
 *
 * <p>Returns a list of HTTP requests with:
 * <ul>
 *   <li>Method and URL</li>
 *   <li>Status code and timing</li>
 *   <li>Request/response sizes</li>
 *   <li>Request ID for detailed inspection</li>
 * </ul>
 *
 * <p>Requires attach_app to be called first.
 * The app must use OkHttp or URLConnection for request capture.
 */
public class NetworkRequestsTool extends AdtTool {

    @Override
    public String getName() {
        return "network_requests";
    }

    @Override
    public String getDescription() {
        return "Gets captured HTTP requests from an attached app. " +
               "Returns method, URL, status, timing, and sizes. " +
               "Use network_request with an ID for full request/response details. " +
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
                            "description": "Maximum number of requests to return (default: 50). Use smaller values for large response bodies."
                        },
                        "offset": {
                            "type": "integer",
                            "description": "Number of requests to skip (default: 0). Use with limit for pagination."
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

        // Fetch network requests from sidekick
        return fetchNetworkRequests(appSession, limit, offset);
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
     * Fetches network requests from sidekick HTTP endpoint with pagination.
     */
    private Result<String> fetchNetworkRequests(AppSession appSession, int limit, int offset) {
        try {
            URL url = new URL(appSession.getSidekickUrl() + "/network/requests");
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

            // Apply client-side pagination
            String jsonResponse = response.toString().trim();
            String paginatedResponse = applyPagination(jsonResponse, limit, offset);

            return new Success<>(paginatedResponse,
                    String.format("Network requests for %s (limit=%d, offset=%d). " +
                                  "Use network_request with request ID for full details.",
                            appSession.getPackageName(), limit, offset));

        } catch (java.net.ConnectException e) {
            return new Failure<>(e,
                    "Cannot connect to sidekick. The app may have crashed. " +
                    "Check if the app is still running and use attach_app to reconnect.");
        } catch (Exception e) {
            return new Failure<>(e,
                    "Failed to fetch network requests: " + e.getMessage());
        }
    }

    /**
     * Applies pagination to the JSON response.
     */
    @SuppressWarnings("unchecked")
    private String applyPagination(String jsonResponse, int limit, int offset) {
        try {
            Map<String, Object> data = JSON.readValue(jsonResponse, Map.class);
            List<Object> requests = (List<Object>) data.get("requests");

            if (requests == null || requests.isEmpty()) {
                return jsonResponse;
            }

            int total = requests.size();
            int fromIndex = Math.min(offset, total);
            int toIndex = Math.min(offset + limit, total);

            List<Object> paginated = requests.subList(fromIndex, toIndex);

            Map<String, Object> result = new java.util.HashMap<>();
            result.put("total", total);
            result.put("offset", offset);
            result.put("limit", limit);
            result.put("count", paginated.size());
            result.put("requests", paginated);

            return JSON.writeValueAsString(result);
        } catch (Exception e) {
            // If parsing fails, return original
            return jsonResponse;
        }
    }
}
