package io.yamsergey.dta.cli.inspector;

import io.yamsergey.dta.inspector.web.InspectorApplication;
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
 * </pre>
 */
@Command(name = "inspector-web",
         description = "Start the DTA Inspector web server")
public class InspectorWebCommand implements Callable<Integer> {

    @Option(names = {"--port", "-p"},
            defaultValue = "8080",
            description = "Port to run the server on (default: 8080)")
    private int port;

    @Override
    public Integer call() throws Exception {
        System.out.println("Starting DTA Inspector on http://localhost:" + port);
        System.out.println("Press Ctrl+C to stop");

        InspectorApplication.start(port);
        return 0;
    }
}
