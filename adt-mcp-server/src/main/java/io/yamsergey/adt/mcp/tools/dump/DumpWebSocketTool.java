package io.yamsergey.adt.mcp.tools.dump;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dumps all captured WebSocket connections with messages to a file.
 *
 * <p>Includes connection info and all sent/received messages.
 */
public class DumpWebSocketTool extends AdtTool {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Override
    public String getName() {
        return "dump_websocket";
    }

    @Override
    public String getDescription() {
        return "Saves ALL WebSocket connections with messages to a JSON file for analysis " +
               "without polluting context. Use this for comprehensive WebSocket review. " +
               "For quick overview, use websocket_connections instead. Requires attach_app first.";
    }

    @Override
    public String getInputSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "file_path": {
                            "type": "string",
                            "description": "Path to save the WebSocket dump JSON file. If not specified, saves to websocket_dump.json in current directory."
                        },
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
        String filePath = getStringParam(args, "file_path");
        String packageName = getStringParam(args, "package");
        String device = getStringParam(args, "device");

        if (filePath == null || filePath.isEmpty()) {
            filePath = "websocket_dump.json";
        }

        // Get session
        Result<AppSession> sessionResult = resolveSession(session, device, packageName);
        if (sessionResult instanceof Failure<AppSession> f) {
            return f;
        }
        AppSession appSession = ((Success<AppSession>) sessionResult).value();

        // Fetch and save websocket connections
        return fetchAndSaveWebSocket(appSession, filePath);
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

            StringBuilder msg = new StringBuilder("Multiple apps attached on " + device + ". Specify package:\n");
            for (AppSession s : deviceSessions) {
                msg.append("  - ").append(s.getPackageName()).append("\n");
            }
            return new Failure<>(null, msg.toString());
        }

        return session.getSession(device, packageName);
    }

    @SuppressWarnings("unchecked")
    private Result<Map<String, Object>> fetchAndSaveWebSocket(AppSession appSession, String filePath) {
        try {
            // First get the list of connections
            URL listUrl = new URL(appSession.getSidekickUrl() + "/websocket/connections");
            HttpURLConnection listConn = (HttpURLConnection) listUrl.openConnection();
            listConn.setConnectTimeout(5000);
            listConn.setReadTimeout(30000);
            listConn.setRequestMethod("GET");

            if (listConn.getResponseCode() != 200) {
                return new Failure<>(null,
                        String.format("Sidekick returned %d. Use attach_app to reconnect.", listConn.getResponseCode()));
            }

            String listResponse = readResponse(listConn);
            listConn.disconnect();

            Map<String, Object> listData = objectMapper.readValue(listResponse, Map.class);
            List<Map<String, Object>> connections = (List<Map<String, Object>>) listData.get("connections");

            if (connections == null || connections.isEmpty()) {
                // No connections, write empty dump
                Map<String, Object> emptyDump = new LinkedHashMap<>();
                emptyDump.put("package", appSession.getPackageName());
                emptyDump.put("device", appSession.getDevice());
                emptyDump.put("timestamp", System.currentTimeMillis());
                emptyDump.put("count", 0);
                emptyDump.put("connections", new ArrayList<>());

                Path path = Path.of(filePath);
                Files.writeString(path, objectMapper.writeValueAsString(emptyDump));

                return new Success<>(
                        Map.of("file", path.toAbsolutePath().toString(), "count", 0),
                        "No WebSocket connections captured");
            }

            // Fetch full details for each connection (including messages)
            List<Map<String, Object>> fullConnections = new ArrayList<>();
            int totalMessages = 0;

            for (Map<String, Object> connection : connections) {
                String connectionId = (String) connection.get("id");
                if (connectionId != null) {
                    try {
                        URL detailUrl = new URL(appSession.getSidekickUrl() + "/websocket/connections/" + connectionId);
                        HttpURLConnection detailConn = (HttpURLConnection) detailUrl.openConnection();
                        detailConn.setConnectTimeout(5000);
                        detailConn.setReadTimeout(10000);
                        detailConn.setRequestMethod("GET");

                        if (detailConn.getResponseCode() == 200) {
                            String detailResponse = readResponse(detailConn);
                            Map<String, Object> fullConnection = objectMapper.readValue(detailResponse, Map.class);
                            fullConnections.add(fullConnection);

                            // Count messages
                            List<?> messages = (List<?>) fullConnection.get("messages");
                            if (messages != null) {
                                totalMessages += messages.size();
                            }
                        } else {
                            fullConnections.add(connection);
                        }
                        detailConn.disconnect();
                    } catch (Exception e) {
                        fullConnections.add(connection);
                    }
                } else {
                    fullConnections.add(connection);
                }
            }

            // Build dump object
            Map<String, Object> dump = new LinkedHashMap<>();
            dump.put("package", appSession.getPackageName());
            dump.put("device", appSession.getDevice());
            dump.put("timestamp", System.currentTimeMillis());
            dump.put("connectionCount", fullConnections.size());
            dump.put("messageCount", totalMessages);
            dump.put("connections", fullConnections);

            // Write to file
            Path path = Path.of(filePath);
            Files.writeString(path, objectMapper.writeValueAsString(dump));

            return new Success<>(
                    Map.of(
                            "file", path.toAbsolutePath().toString(),
                            "size", Files.size(path),
                            "connectionCount", fullConnections.size(),
                            "messageCount", totalMessages,
                            "package", appSession.getPackageName()
                    ),
                    String.format("WebSocket dump saved to %s (%d connections, %d messages, %d bytes)",
                            path.toAbsolutePath(), fullConnections.size(), totalMessages, Files.size(path)));

        } catch (java.net.ConnectException e) {
            return new Failure<>(e, "Cannot connect to sidekick. Use attach_app to reconnect.");
        } catch (Exception e) {
            return new Failure<>(e, "Failed to dump WebSocket: " + e.getMessage());
        }
    }

    private String readResponse(HttpURLConnection conn) throws Exception {
        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line).append("\n");
            }
        }
        return response.toString();
    }
}
