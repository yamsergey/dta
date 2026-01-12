package io.yamsergey.adt.mcp.tools.debug;

import io.yamsergey.adt.mcp.session.SessionManager;
import io.yamsergey.adt.mcp.tools.AdtTool;
import io.yamsergey.adt.tools.sugar.Failure;
import io.yamsergey.adt.tools.sugar.Result;
import io.yamsergey.adt.tools.sugar.Success;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Starts the ADT Debug Server as a subprocess.
 *
 * <p>The debug server provides a web UI for visual inspection of attached apps
 * and stores selection state that can be queried via get_selected_element.
 *
 * <p>In MCP coordination mode, sessions are automatically synced from the
 * MCP server to the debug server.
 */
public class StartDebugServerTool extends AdtTool {

    private static final int DEFAULT_PORT = 8080;
    private static final int STARTUP_TIMEOUT_MS = 10000;
    private static final int POLL_INTERVAL_MS = 200;

    @Override
    public String getName() {
        return "start_debug_server";
    }

    @Override
    public String getDescription() {
        return "Starts the ADT Debug Server web UI for visual inspection. " +
               "Returns the URL to open in a browser. " +
               "IMPORTANT: Call attach_app AFTER starting this server to connect to an app. " +
               "The debug server needs an attached app to show layout inspector and network data. " +
               "Use this when the developer wants to visually inspect the app or select UI elements for you to analyze.";
    }

    @Override
    public String getInputSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "port": {
                            "type": "integer",
                            "description": "Port to run the debug server on (default: 8080)"
                        }
                    }
                }
                """;
    }

    @Override
    public Result<?> execute(Map<String, Object> args, SessionManager session) {
        int port = getIntParam(args, "port", DEFAULT_PORT);

        // Check if already running
        if (session.isDebugServerRunning()) {
            String url = session.getDebugServerUrl();
            return new Success<>(
                    Map.of("url", url, "status", "already_running"),
                    "Debug server is already running at " + url);
        }

        // Find the debug server jar
        String debugServerPath = findDebugServerJar();
        if (debugServerPath == null) {
            return new Failure<>(null,
                    "Debug server JAR not found. Ensure adt-debug-server is built. " +
                    getSearchedPaths());
        }

        // Build command
        List<String> command = new ArrayList<>();
        command.add("java");
        command.add("-jar");
        command.add(debugServerPath);
        command.add("--mcp");
        command.add("--port=" + port);

        try {
            // Start the process
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            // Don't inherit IO - let it run in background
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);

            Process process = pb.start();

            // Wait for server to be ready
            String serverUrl = "http://localhost:" + port;
            Result<Void> readyResult = waitForServer(serverUrl);
            if (readyResult instanceof Failure<Void> f) {
                process.destroyForcibly();
                return f;
            }

            // Register with session manager
            session.setDebugServerProcess(process, serverUrl);

            // Sync existing sessions to debug server
            syncSessionsToDebugServer(session, serverUrl);

            return new Success<>(
                    Map.of("url", serverUrl, "status", "started"),
                    "Debug server started at " + serverUrl + " - open in browser to inspect apps.");

        } catch (Exception e) {
            return new Failure<>(e,
                    "Failed to start debug server: " + e.getMessage());
        }
    }

    /**
     * Finds the debug server JAR file.
     */
    private String findDebugServerJar() {
        // Check environment variable first
        String envPath = System.getenv("ADT_DEBUG_SERVER_JAR");
        if (envPath != null && new File(envPath).exists()) {
            return envPath;
        }

        // Build list of directories to search
        List<String> searchDirs = buildSearchDirs();

        // Search each directory for the boot jar
        for (String dir : searchDirs) {
            File dirFile = new File(dir);
            if (dirFile.exists() && dirFile.isDirectory()) {
                File[] jars = dirFile.listFiles((d, name) ->
                        name.startsWith("adt-debug-server") &&
                        name.endsWith(".jar") &&
                        !name.contains("plain"));
                if (jars != null && jars.length > 0) {
                    return jars[0].getAbsolutePath();
                }
            }
        }

        return null;
    }

    /**
     * Builds list of directories to search for the debug server JAR.
     */
    private List<String> buildSearchDirs() {
        List<String> searchDirs = new ArrayList<>();

        // Try to find relative to this class's location (inside the MCP server install)
        String mcpServerPath = findMcpServerInstallPath();

        // If we found the MCP install path, look for sibling debug-server build
        if (mcpServerPath != null) {
            // MCP JAR is at: .../adt-mcp-server/build/install/adt-mcp/lib/adt-mcp-server-X.X.X.jar
            // Debug server is at: .../adt-debug-server/build/libs/adt-debug-server-X.X.X.jar
            File mcpJar = new File(mcpServerPath);
            File libDir = mcpJar.getParentFile(); // lib/
            if (libDir != null) {
                File adtMcpDir = libDir.getParentFile(); // adt-mcp/
                if (adtMcpDir != null) {
                    File installDir = adtMcpDir.getParentFile(); // install/
                    if (installDir != null) {
                        File buildDir = installDir.getParentFile(); // build/
                        if (buildDir != null) {
                            File mcpModuleDir = buildDir.getParentFile(); // adt-mcp-server/
                            if (mcpModuleDir != null) {
                                File projectRoot = mcpModuleDir.getParentFile(); // project root
                                if (projectRoot != null) {
                                    searchDirs.add(new File(projectRoot, "adt-debug-server/build/libs").getAbsolutePath());
                                }
                            }
                        }
                    }
                }
            }
        }

        // Search common locations relative to current directory
        String cwd = System.getProperty("user.dir");
        searchDirs.add(cwd + "/adt-debug-server/build/libs");
        searchDirs.add(new File(cwd).getParent() + "/adt-debug-server/build/libs");

        // User home location
        searchDirs.add(System.getProperty("user.home") + "/.adt");

        return searchDirs;
    }

    /**
     * Gets searched paths for error message.
     */
    private String getSearchedPaths() {
        StringBuilder sb = new StringBuilder();
        sb.append("Searched directories: ");

        String envPath = System.getenv("ADT_DEBUG_SERVER_JAR");
        if (envPath != null) {
            sb.append("env=").append(envPath).append(", ");
        }

        String mcpServerPath = findMcpServerInstallPath();
        if (mcpServerPath != null) {
            sb.append("mcpJar=").append(mcpServerPath).append(", ");
        }

        List<String> searchDirs = buildSearchDirs();
        for (String dir : searchDirs) {
            File dirFile = new File(dir);
            sb.append(dir);
            if (dirFile.exists()) {
                File[] files = dirFile.listFiles();
                sb.append(" (exists, ");
                sb.append(files != null ? files.length : 0);
                sb.append(" files)");
            } else {
                sb.append(" (not found)");
            }
            sb.append(", ");
        }

        sb.append("cwd=").append(System.getProperty("user.dir"));
        return sb.toString();
    }

    /**
     * Finds the MCP server installation path by looking at the classpath.
     */
    private String findMcpServerInstallPath() {
        try {
            // Get the location of this class
            String path = getClass().getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
            if (path != null && path.contains("adt-mcp-server")) {
                return path;
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    /**
     * Waits for the debug server to become responsive.
     */
    private Result<Void> waitForServer(String serverUrl) {
        long startTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - startTime < STARTUP_TIMEOUT_MS) {
            try {
                URL url = new URL(serverUrl + "/health");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(1000);
                conn.setReadTimeout(1000);
                conn.setRequestMethod("GET");

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    return new Success<>(null, "Debug server is ready");
                }
                conn.disconnect();
            } catch (Exception e) {
                // Not ready yet
            }

            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Failure<>(e, "Interrupted while waiting for debug server");
            }
        }

        return new Failure<>(null,
                "Debug server did not start within timeout. Check logs for errors.");
    }

    /**
     * Syncs existing MCP sessions to the debug server.
     */
    private void syncSessionsToDebugServer(SessionManager session, String serverUrl) {
        session.listSessions().forEach(appSession -> {
            try {
                URL url = new URL(serverUrl + "/api/sessions");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                String json = String.format(
                        "{\"device\":\"%s\",\"packageName\":\"%s\",\"sidekickUrl\":\"%s\"}",
                        appSession.getDevice(),
                        appSession.getPackageName(),
                        appSession.getSidekickUrl()
                );

                conn.getOutputStream().write(json.getBytes());
                conn.getResponseCode(); // Execute request
                conn.disconnect();
            } catch (Exception e) {
                // Ignore sync errors
            }
        });
    }
}
