package io.yamsergey.dta.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = "io.yamsergey.dta.server")
public class DtaServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DtaServerApplication.class, args);
    }

    /**
     * Starts the server with optional port configuration.
     * This method blocks forever - the server runs until interrupted.
     */
    public static void start(int port) {
        start(port, null);
    }

    /**
     * Starts the server with optional port and log file configuration.
     * This method blocks forever - the server runs until interrupted.
     *
     * @param port the server port (0 = auto-pick available port)
     * @param logFile path to log file, or null to disable file logging
     */
    public static void start(int port, String logFile) {
        if (logFile != null && !logFile.isEmpty()) {
            System.setProperty("logging.file.name", logFile);
        }

        // Auto-pick port if requested
        int actualPort = port;
        if (port == 0) {
            String envPort = System.getenv("DTA_PORT");
            if (envPort != null && !envPort.isEmpty()) {
                actualPort = Integer.parseInt(envPort);
            } else {
                actualPort = findAvailablePort();
            }
        }

        SpringApplication app = new SpringApplication(DtaServerApplication.class);
        var props = new java.util.HashMap<String, Object>();
        props.put("server.port", String.valueOf(actualPort));
        app.setDefaultProperties(props);
        ConfigurableApplicationContext context = app.run();

        // Write daemon state file
        DaemonStateManager.writeDaemonState(actualPort);

        // Register shutdown hook to clean up
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            DaemonStateManager.deleteDaemonState();
            context.close();
        }));

        // Block forever - prevents picocli from calling System.exit()
        Object lock = new Object();
        synchronized (lock) {
            try {
                while (context.isActive()) {
                    lock.wait(1000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static int findAvailablePort() {
        try (var socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to find available port", e);
        }
    }
}
