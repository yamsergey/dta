package io.yamsergey.adt.mcp.tools.debug;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.yamsergey.adt.mcp.session.SessionManager;
import io.yamsergey.adt.mcp.tools.AdtTool;
import io.yamsergey.adt.tools.sugar.Failure;
import io.yamsergey.adt.tools.sugar.Result;
import io.yamsergey.adt.tools.sugar.Success;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/**
 * MCP tool for getting the network request selected by the developer in the debug server UI.
 *
 * <p>This enables a collaborative workflow where:
 * <ol>
 *   <li>Developer opens the debug server UI and views network traffic</li>
 *   <li>Developer clicks on a request to inspect it</li>
 *   <li>Agent queries this tool to see what request was selected</li>
 *   <li>Agent can analyze the request/response or help debug issues</li>
 * </ol>
 */
public class GetSelectedNetworkRequestTool extends AdtTool {

    private final SessionManager sessionManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GetSelectedNetworkRequestTool(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public String getName() {
        return "get_selected_network_request";
    }

    @Override
    public String getDescription() {
        return "Gets the network request selected by the developer in the Debug Server web UI. " +
               "Use this when the developer says they've selected a network request and wants you to " +
               "analyze it, debug an issue, or understand the API response. " +
               "Requires: 1) start_debug_server, 2) attach_app to connect to the app.";
    }

    @Override
    public String getInputSchema() {
        return """
            {
                "type": "object",
                "properties": {
                    "session_id": {
                        "type": "string",
                        "description": "Optional session ID. If not provided, uses the active session."
                    }
                },
                "required": []
            }
            """;
    }

    @Override
    public Result<Map<String, Object>> execute(Map<String, Object> arguments, SessionManager session) {
        // Check if debug server is running
        if (!session.isDebugServerRunning()) {
            return new Failure<>(null,
                    "Debug server is not running. Call start_debug_server first.");
        }

        String debugServerUrl = session.getDebugServerUrl();
        String sessionId = (String) arguments.get("session_id");

        try {
            // If no session ID provided, get the active session first
            if (sessionId == null || sessionId.isEmpty()) {
                URL activeUrl = new URL(debugServerUrl + "/api/sessions/active");
                HttpURLConnection activeConn = (HttpURLConnection) activeUrl.openConnection();
                activeConn.setRequestMethod("GET");
                activeConn.setConnectTimeout(5000);
                activeConn.setReadTimeout(5000);

                if (activeConn.getResponseCode() == 200) {
                    try (InputStream is = activeConn.getInputStream()) {
                        JsonNode activeSession = objectMapper.readTree(is);
                        sessionId = activeSession.has("id") ? activeSession.get("id").asText() : null;
                    }
                }
                activeConn.disconnect();

                if (sessionId == null) {
                    return new Failure<>(null,
                            "No active session. Call attach_app first to connect to an app.");
                }
            }

            // Get the selected network request
            URL url = new URL(debugServerUrl + "/api/sessions/" + sessionId + "/selected-network");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                try (InputStream is = conn.getInputStream()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> response = objectMapper.readValue(is, Map.class);

                    @SuppressWarnings("unchecked")
                    Map<String, Object> request = (Map<String, Object>) response.get("request");

                    if (request == null) {
                        return new Success<>(
                                Map.of("selected", false, "message", "No network request is currently selected in the debug server UI."),
                                "No network request selected");
                    }

                    // Build a clean response with relevant fields
                    return new Success<>(
                            Map.of(
                                    "selected", true,
                                    "request", request,
                                    "sessionId", sessionId
                            ),
                            "Network request selected: " + request.get("method") + " " + request.get("url"));
                }
            } else if (responseCode == 404) {
                return new Failure<>(null,
                        "Session not found: " + sessionId);
            } else {
                return new Failure<>(null,
                        "Failed to get selected network request: HTTP " + responseCode);
            }
        } catch (Exception e) {
            return new Failure<>(e,
                    "Failed to get selected network request: " + e.getMessage());
        }
    }
}
