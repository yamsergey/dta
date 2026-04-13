package io.yamsergey.dta.daemon;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Auto-starts and discovers the DTA daemon server.
 *
 * Discovery order:
 * 1. Read ~/.dta/daemon.json -> get port -> health check
 * 2. If not running, spawn "dta-cli server --port 0" as detached process
 * 3. Poll health every 500ms, up to 15s
 *
 * Finding the server executable:
 * 1. DTA_SERVER_CMD env var
 * 2. Sibling in bin/ directory (relative to this JAR)
 * 3. "dta-cli" on PATH
 */
public class DaemonLauncher {

    private static final Logger log = LoggerFactory.getLogger(DaemonLauncher.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Path DAEMON_STATE_FILE = Path.of(System.getProperty("user.home"), ".dta", "daemon.json");
    private static final int HEALTH_CHECK_INTERVAL_MS = 500;
    private static final int MAX_WAIT_MS = 15_000;
    private static final int SHUTDOWN_WAIT_MS = 5_000;

    /**
     * Version of the launching binary (e.g. dta-cli, dta-mcp, plugin), used
     * to compare against the running daemon's version and detect mismatches.
     * Resolved lazily from {@code /version.properties} on the launcher's own
     * classpath — since each entry-point binary bundles dta-daemon and all
     * three version.properties files come from the same {@code gradle.properties}
     * at build time, this gives the correct answer for whichever process the
     * launcher is loaded into. No caller registration needed.
     *
     * <p>Tests can override via {@link #setLauncherVersion(String)}.</p>
     */
    private static volatile String launcherVersion = null;

    /**
     * Test/override hook: explicitly sets the launcher version, bypassing the
     * classpath self-read. Production code does not need to call this.
     */
    public static void setLauncherVersion(String version) {
        launcherVersion = version;
        log.debug("Launcher version overridden to {}", version);
    }

    private static String getLauncherVersion() {
        if (launcherVersion != null) return launcherVersion;
        try (var is = DaemonLauncher.class.getResourceAsStream("/version.properties")) {
            if (is != null) {
                var props = new java.util.Properties();
                props.load(is);
                String v = props.getProperty("version", "unknown");
                launcherVersion = v;
                return v;
            }
        } catch (Exception e) {
            log.debug("Failed to read launcher version.properties: {}", e.getMessage());
        }
        launcherVersion = "unknown";
        return "unknown";
    }

    /**
     * Ensures the daemon is running and returns a DaemonClient connected to it.
     *
     * @return a DaemonClient connected to the running daemon
     * @throws DaemonException if the daemon cannot be started
     */
    public static DaemonClient ensureDaemonRunning() {
        // Step 1: Check if daemon is already running and version-compatible
        DaemonClient existing = tryConnectExisting();
        if (existing != null) {
            return existing;
        }

        // Step 2: Start the daemon
        log.info("Daemon not running, starting...");
        startDaemon();

        // Step 3: Wait for daemon to become healthy
        long deadline = System.currentTimeMillis() + MAX_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(HEALTH_CHECK_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DaemonException("Interrupted while waiting for daemon");
            }

            DaemonClient client = tryConnectExisting();
            if (client != null) {
                log.info("Daemon is ready at {}", client.getBaseUrl());
                return client;
            }
        }

        throw new DaemonException("Daemon failed to start within " + (MAX_WAIT_MS / 1000) + " seconds");
    }

    /**
     * Tries to connect to an existing daemon by reading the state file.
     * Returns null if the daemon is not running, not healthy, or its version
     * doesn't match the launcher version (after triggering a takeover).
     */
    private static DaemonClient tryConnectExisting() {
        if (!Files.exists(DAEMON_STATE_FILE)) {
            return null;
        }

        try {
            JsonNode state = mapper.readTree(DAEMON_STATE_FILE.toFile());
            int port = state.get("port").asInt();
            long pid = state.get("pid").asLong();

            // Check if the process is still alive
            if (!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                log.debug("Daemon process {} is not alive, cleaning up state file", pid);
                Files.deleteIfExists(DAEMON_STATE_FILE);
                return null;
            }

            // Health check
            DaemonClient client = new DaemonClient("http://localhost:" + port);
            String versionJson;
            try {
                versionJson = client.version();
            } catch (Exception e) {
                log.debug("Daemon health check failed: {}", e.getMessage());
                return null;
            }

            // Verify this is actually a Javalin dta-daemon, not the old
            // Spring Boot dta-server (which has different routes and will
            // 404 on /api/layout/tree). The daemon sets "daemon":true.
            if (!isDaemonProcess(versionJson)) {
                log.warn("Found a server at port {} but it's not a dta-daemon (missing \"daemon\":true). " +
                    "Cleaning up state file — the plugin will start an embedded daemon.", port);
                Files.deleteIfExists(DAEMON_STATE_FILE);
                return null;
            }

            // Version-compat check
            String myVersion = getLauncherVersion();
            String daemonVersion = parseDaemonVersion(versionJson);
            if (!isCompatible(myVersion, daemonVersion)) {
                log.warn("Daemon version mismatch: running {} vs launcher {} — triggering takeover",
                    daemonVersion, myVersion);
                requestShutdown(client);
                waitForShutdown();
                return null; // caller will spawn fresh daemon
            }
            return client;
        } catch (Exception e) {
            log.debug("Failed to read daemon state: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Compares major.minor of two version strings. Returns true if both are
     * null/unknown, or if their major and minor components match.
     */
    static boolean isCompatible(String launcher, String daemon) {
        if (launcher == null || daemon == null) return true;
        if ("unknown".equals(launcher) || "unknown".equals(daemon)) return true;
        String[] l = launcher.split("\\.");
        String[] d = daemon.split("\\.");
        if (l.length < 2 || d.length < 2) return true;
        return l[0].equals(d[0]) && l[1].equals(d[1]);
    }

    private static boolean isDaemonProcess(String versionJson) {
        try {
            JsonNode node = mapper.readTree(versionJson);
            return node.path("daemon").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    private static String parseDaemonVersion(String versionJson) {
        try {
            JsonNode node = mapper.readTree(versionJson);
            JsonNode v = node.get("version");
            return v != null ? v.asText("unknown") : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * POSTs /api/shutdown to the running daemon, ignoring errors. The old
     * daemon is expected to clean up via its shutdown hook (releasing forwards
     * and the state file).
     */
    private static void requestShutdown(DaemonClient client) {
        try {
            // Use raw HTTP since DaemonClient doesn't expose a shutdown helper
            // (intentionally — only DaemonLauncher should ever call this).
            var http = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(2))
                .build();
            var req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(client.getBaseUrl() + "/api/shutdown"))
                .timeout(java.time.Duration.ofSeconds(2))
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                .build();
            http.send(req, java.net.http.HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.debug("Shutdown request to old daemon failed (expected if it died fast): {}", e.getMessage());
        }
    }

    /**
     * Polls until the state file disappears, indicating the old daemon's
     * shutdown hook ran and cleaned up. Bounded by SHUTDOWN_WAIT_MS so a
     * stuck-shutdown daemon doesn't block us forever — we'll start a new
     * one anyway and the new one's startup sweep will catch any leaks.
     */
    private static void waitForShutdown() {
        long deadline = System.currentTimeMillis() + SHUTDOWN_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (!Files.exists(DAEMON_STATE_FILE)) {
                log.info("Old daemon shut down cleanly");
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("Old daemon did not exit within {}ms — proceeding anyway (startup sweep will clean leaks)",
            SHUTDOWN_WAIT_MS);
        // Force-delete the state file so the new daemon doesn't see it.
        try { Files.deleteIfExists(DAEMON_STATE_FILE); } catch (Exception ignored) {}
    }

    /**
     * Starts the daemon as a detached process.
     */
    private static void startDaemon() {
        String command = findServerCommand();
        log.info("Starting daemon with: {} server --port 0", command);

        try {
            ProcessBuilder pb = new ProcessBuilder(command, "server", "--port", "0");
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            // Start detached - don't inherit our IO
            pb.start();
        } catch (Exception e) {
            throw new DaemonException("Failed to start daemon: " + e.getMessage(), e);
        }
    }

    /**
     * Finds the dta-cli command to run the server.
     * Search order: DTA_SERVER_CMD env -> sibling bin/ -> PATH
     */
    private static String findServerCommand() {
        // 1. Environment variable
        String envCmd = System.getenv("DTA_SERVER_CMD");
        if (envCmd != null && !envCmd.isEmpty()) {
            File f = new File(envCmd);
            if (f.exists() && f.canExecute()) {
                return envCmd;
            }
            log.warn("DTA_SERVER_CMD={} not found or not executable", envCmd);
        }

        // 2. Sibling in bin/ directory (e.g., /usr/local/bin/dta-cli next to this JAR)
        try {
            String jarPath = DaemonLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
            File jarFile = new File(jarPath);
            File binDir = jarFile.getParentFile();
            if (binDir != null) {
                // Check parent's parent /bin (for Gradle installDist layout: lib/xxx.jar -> bin/dta-cli)
                File binParent = binDir.getParentFile();
                if (binParent != null) {
                    File siblingBin = new File(binParent, "bin" + File.separator + "dta-cli");
                    if (siblingBin.exists() && siblingBin.canExecute()) {
                        return siblingBin.getAbsolutePath();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to find sibling dta-cli: {}", e.getMessage());
        }

        // 3. Fall back to PATH
        return "dta-cli";
    }
}
