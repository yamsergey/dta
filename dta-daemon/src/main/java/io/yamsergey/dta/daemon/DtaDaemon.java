package io.yamsergey.dta.daemon;

import io.javalin.Javalin;
import io.javalin.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.lang.reflect.Type;

/**
 * Standalone HTTP daemon that wraps {@link DtaOrchestrator} with a Javalin server.
 * Exposes the same REST API as the Spring-based dta-server InspectorController.
 *
 * <p>Usage: {@code java -jar dta-daemon.jar --port 8081}</p>
 */
public class DtaDaemon {

    private static final Logger log = LoggerFactory.getLogger(DtaDaemon.class);
    private static final int DEFAULT_PORT = 8080;

    private Javalin app;

    public static void main(String[] args) {
        // Mark this JVM as a standalone daemon process — DtaRoutes uses this
        // when handling /api/shutdown so it knows to System.exit() (and trigger
        // the shutdown hook chain) rather than just stop Javalin.
        System.setProperty("dta.daemon.standalone", "true");
        int port = parsePort(args);
        DtaDaemon daemon = new DtaDaemon();
        daemon.start(port);
    }

    /**
     * Starts the Javalin HTTP server on the given port.
     *
     * @param port the port to bind (0 for random available port)
     * @return the actual port the server is listening on
     */
    public int start(int port) {
        DtaOrchestrator orchestrator = DtaOrchestrator.getInstance();

        // Sweep orphan dta_sidekick_* port forwards left by a previous daemon
        // that crashed or was killed with kill -9. Must run BEFORE binding the
        // HTTP port and BEFORE accepting any connections, so the new daemon
        // starts with a clean slate. Idempotent + best-effort — never blocks startup.
        int orphans = io.yamsergey.dta.daemon.sidekick.SidekickConnectionManager
                .getInstance()
                .cleanupOwnedSidekickForwards();
        if (orphans > 0) {
            log.info("Startup sweep removed {} orphan sidekick forward(s)", orphans);
        }

        // Configure Jackson 3 JSON mapper for Javalin
        ObjectMapper objectMapper = new ObjectMapper();
        JsonMapper jsonMapper = new JsonMapper() {
            @Override
            public String toJsonString(Object obj, Type type) {
                try {
                    return objectMapper.writeValueAsString(obj);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public <T> T fromJsonString(String json, Type targetType) {
                try {
                    return objectMapper.readValue(json, objectMapper.constructType(targetType));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public <T> T fromJsonStream(InputStream json, Type targetType) {
                try {
                    return objectMapper.readValue(json, objectMapper.constructType(targetType));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };

        app = Javalin.create(config -> {
            // Use Jackson 3 for JSON serialization
            config.jsonMapper(jsonMapper);

            // CORS for development — inspector-web and other clients need this
            config.bundledPlugins.enableCors(cors -> cors.addRule(rule -> rule.anyHost()));

            // JSON as default content type
            config.http.defaultContentType = "application/json";
        });

        // Register all REST routes
        DtaRoutes.register(app);

        // Graceful shutdown — clean up state file and orchestrator
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down DTA daemon...");
            deleteStateFile();
            orchestrator.shutdown();
            if (app != null) {
                app.stop();
            }
        }));

        app.start(port);
        int actualPort = app.port();
        writeStateFile(actualPort);
        log.info("DTA daemon started on port {}", actualPort);
        return actualPort;
    }

    /**
     * Stops the daemon and cleans up resources.
     */
    public void stop() {
        if (app != null) {
            DtaOrchestrator.getInstance().shutdown();
            app.stop();
            app = null;
        }
    }

    /**
     * Returns the Javalin instance (for testing).
     */
    public Javalin getApp() {
        return app;
    }

    private static final java.nio.file.Path STATE_FILE =
            java.nio.file.Path.of(System.getProperty("user.home"), ".dta", "daemon.json");

    private void writeStateFile(int port) {
        try {
            java.nio.file.Files.createDirectories(STATE_FILE.getParent());
            String json = String.format("{\"port\":%d,\"pid\":%d,\"startTime\":%d,\"version\":\"%s\"}",
                    port, ProcessHandle.current().pid(), System.currentTimeMillis(), readDaemonVersion());
            java.nio.file.Files.writeString(STATE_FILE, json);
            log.debug("Wrote daemon state: {}", STATE_FILE);
        } catch (Exception e) {
            log.warn("Failed to write daemon state file: {}", e.getMessage());
        }
    }

    /**
     * Reads the dta-daemon version from {@code /version.properties} on the
     * classpath. Generated at build time from {@code dtaVersion}.
     */
    private static String readDaemonVersion() {
        try (var is = DtaDaemon.class.getResourceAsStream("/version.properties")) {
            if (is != null) {
                var props = new java.util.Properties();
                props.load(is);
                return props.getProperty("version", "unknown");
            }
        } catch (Exception ignored) {}
        return "unknown";
    }

    private static void deleteStateFile() {
        try {
            java.nio.file.Files.deleteIfExists(STATE_FILE);
        } catch (Exception ignored) {}
    }

    static int parsePort(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--port".equals(args[i])) {
                try {
                    return Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid port number: " + args[i + 1]);
                    System.exit(1);
                }
            }
        }
        return DEFAULT_PORT;
    }
}
