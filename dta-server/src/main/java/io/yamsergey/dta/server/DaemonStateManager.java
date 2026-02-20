package io.yamsergey.dta.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/**
 * Manages the daemon state file (~/.dta/daemon.json).
 * This file allows clients to discover the running daemon's port.
 */
public class DaemonStateManager {

    private static final Logger log = LoggerFactory.getLogger(DaemonStateManager.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Path DAEMON_STATE_FILE = Path.of(System.getProperty("user.home"), ".dta", "daemon.json");

    /**
     * Writes the daemon state file with the given port.
     */
    public static void writeDaemonState(int port) {
        try {
            Files.createDirectories(DAEMON_STATE_FILE.getParent());
            String version = InspectorController.getVersion();
            var state = Map.of(
                "pid", ProcessHandle.current().pid(),
                "port", port,
                "version", version,
                "startedAt", Instant.now().toString()
            );
            mapper.writeValue(DAEMON_STATE_FILE.toFile(), state);
            log.info("Daemon state written: port={}, pid={}", port, ProcessHandle.current().pid());
        } catch (IOException e) {
            log.error("Failed to write daemon state file", e);
        }
    }

    /**
     * Deletes the daemon state file.
     */
    public static void deleteDaemonState() {
        try {
            Files.deleteIfExists(DAEMON_STATE_FILE);
            log.info("Daemon state file deleted");
        } catch (IOException e) {
            log.error("Failed to delete daemon state file", e);
        }
    }

    /**
     * Returns the path to the daemon state file.
     */
    public static Path getDaemonStateFile() {
        return DAEMON_STATE_FILE;
    }
}
