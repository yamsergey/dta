package io.yamsergey.adt.mcp.tools.dump;

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
import java.util.List;
import java.util.Map;

/**
 * Dumps the full Compose UI hierarchy to a file.
 *
 * <p>Useful for saving UI state for later analysis or sharing.
 */
public class DumpLayoutTool extends AdtTool {

    @Override
    public String getName() {
        return "dump_layout";
    }

    @Override
    public String getDescription() {
        return "Saves FULL Compose UI hierarchy to a JSON file for analysis without polluting context. " +
               "Use this when you need to examine the complete layout structure. " +
               "For finding specific elements, use compose_tree with filters instead. " +
               "Requires attach_app first.";
    }

    @Override
    public String getInputSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "file_path": {
                            "type": "string",
                            "description": "Path to save the layout JSON file. If not specified, saves to layout_dump.json in current directory."
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
            filePath = "layout_dump.json";
        }

        // Get session
        Result<AppSession> sessionResult = resolveSession(session, device, packageName);
        if (sessionResult instanceof Failure<AppSession> f) {
            return f;
        }
        AppSession appSession = ((Success<AppSession>) sessionResult).value();

        // Fetch and save compose tree
        return fetchAndSaveLayout(appSession, filePath);
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

    private Result<Map<String, Object>> fetchAndSaveLayout(AppSession appSession, String filePath) {
        try {
            URL url = new URL(appSession.getSidekickUrl() + "/compose/tree");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(30000);
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

            // Write to file
            Path path = Path.of(filePath);
            Files.writeString(path, response.toString());

            return new Success<>(
                    Map.of(
                            "file", path.toAbsolutePath().toString(),
                            "size", Files.size(path),
                            "package", appSession.getPackageName()
                    ),
                    String.format("Layout dumped to %s (%d bytes)",
                            path.toAbsolutePath(), Files.size(path)));

        } catch (java.net.ConnectException e) {
            return new Failure<>(e, "Cannot connect to sidekick. Use attach_app to reconnect.");
        } catch (Exception e) {
            return new Failure<>(e, "Failed to dump layout: " + e.getMessage());
        }
    }
}
