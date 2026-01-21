package io.yamsergey.dta.inspector.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * DTA Inspector Web Application.
 *
 * <p>A web-based inspector for Android apps with sidekick installed.
 * Provides UI for inspecting Compose hierarchy, network requests, and WebSocket connections.</p>
 */
@SpringBootApplication
@ComponentScan(basePackages = "io.yamsergey.dta.inspector.web")
public class InspectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(InspectorApplication.class, args);
    }

    /**
     * Starts the inspector with optional port configuration.
     * This method blocks forever - the server runs until interrupted.
     */
    public static void start(int port) {
        SpringApplication app = new SpringApplication(InspectorApplication.class);
        app.setDefaultProperties(java.util.Map.of("server.port", String.valueOf(port)));
        var context = app.run();

        // Register shutdown hook to close context on JVM shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(context::close));

        // Block forever - this prevents picocli from calling System.exit()
        // The server will run until the user presses Ctrl+C
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
}
