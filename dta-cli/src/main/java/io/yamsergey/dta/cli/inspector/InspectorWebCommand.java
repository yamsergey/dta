package io.yamsergey.dta.cli.inspector;

import io.yamsergey.dta.inspector.web.InspectorApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * CLI command to start the DTA Inspector web server.
 *
 * <p>This starts a web-based inspector for Android apps with sidekick installed.
 * The inspector provides UI for inspecting Compose hierarchy, network requests,
 * and WebSocket connections.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * # Start inspector on default port (8080)
 * dta-cli inspector-web
 *
 * # Start on custom port
 * dta-cli inspector-web --port 3000
 *
 * # Enable file logging
 * dta-cli inspector-web --log-file /tmp/inspector.log
 * </pre>
 */
@Command(name = "inspector-web",
         description = "Start the DTA Inspector web server")
public class InspectorWebCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(InspectorWebCommand.class);

    @Option(names = {"--port", "-p"},
            defaultValue = "8080",
            description = "Port to run the server on (default: 8080)")
    private int port;

    @Option(names = {"--log-file"},
            description = "Path to log file (enables file logging)")
    private String logFile;

    @Override
    public Integer call() throws Exception {
        log.info("Starting DTA Inspector on http://localhost:{}", port);
        if (logFile != null) {
            log.info("Logging to file: {}", logFile);
        }
        log.info("Press Ctrl+C to stop");

        InspectorApplication.start(port, logFile);
        return 0;
    }
}
