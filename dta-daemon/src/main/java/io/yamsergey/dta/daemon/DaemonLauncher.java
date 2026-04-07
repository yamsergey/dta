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

    /**
     * Ensures the daemon is running and returns a DaemonClient connected to it.
     *
     * @return a DaemonClient connected to the running daemon
     * @throws DaemonException if the daemon cannot be started
     */
    public static DaemonClient ensureDaemonRunning() {
        // Step 1: Check if daemon is already running
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
     * Returns null if daemon is not running or not healthy.
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
            try {
                client.version();
                return client;
            } catch (Exception e) {
                log.debug("Daemon health check failed: {}", e.getMessage());
                return null;
            }
        } catch (Exception e) {
            log.debug("Failed to read daemon state: {}", e.getMessage());
            return null;
        }
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
