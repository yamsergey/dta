package io.yamsergey.dta.daemon;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
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

            // CORS for development
            config.bundledPlugins.enableCors(cors -> cors.addRule(rule -> rule.anyHost()));

            // Serve static files (inspector-web)
            config.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/";
                staticFiles.directory = "/static";
                staticFiles.location = Location.CLASSPATH;
            });

            // JSON as default content type
            config.http.defaultContentType = "application/json";
        });

        // Redirect root to index.html
        app.get("/", ctx -> ctx.redirect("/index.html"));

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
            String json = String.format("{\"port\":%d,\"pid\":%d,\"startTime\":%d}",
                    port, ProcessHandle.current().pid(), System.currentTimeMillis());
            java.nio.file.Files.writeString(STATE_FILE, json);
            log.debug("Wrote daemon state: {}", STATE_FILE);
        } catch (Exception e) {
            log.warn("Failed to write daemon state file: {}", e.getMessage());
        }
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
