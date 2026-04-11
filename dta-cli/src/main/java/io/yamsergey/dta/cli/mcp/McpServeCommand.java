package io.yamsergey.dta.cli.mcp;

import io.yamsergey.dta.daemon.DaemonLauncher;
import io.yamsergey.dta.mcp.McpHttpServer;
import io.yamsergey.dta.mcp.McpServer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * {@code dta-cli mcp serve [--http] [--port N]} — runs the DTA MCP server.
 *
 * <p>Default mode is <strong>stdio</strong>: the server takes over the process
 * and exchanges JSON-RPC over stdin/stdout, which is what every existing MCP
 * client config (Claude Code, Cursor, Claude Desktop) expects.</p>
 *
 * <p>{@code --http} switches to the Streamable HTTP transport on a user-picked
 * port. This is what Android Studio Gemini and any other HTTP-based MCP client
 * needs (AS Gemini explicitly does NOT support stdio). The server runs in the
 * foreground until interrupted; pair with {@code dta-cli mcp install --target
 * as-gemini --port N} from another terminal to wire it into AS.</p>
 */
@Command(name = "serve",
         description = "Run the DTA MCP server (stdio by default; --http for HTTP transport).")
public class McpServeCommand implements Callable<Integer> {

    @Option(names = {"--http"},
            description = "Use the Streamable HTTP transport instead of stdio. " +
                          "Required for Android Studio Gemini and other HTTP-based MCP clients.")
    private boolean http;

    @Option(names = {"--port"},
            defaultValue = "12321",
            description = "Port for the HTTP server (only meaningful with --http; default: ${DEFAULT-VALUE}). " +
                          "Use 0 for an OS-assigned port.")
    private int port;

    @Option(names = {"--log-file"},
            description = "Path to log file (enables file logging)")
    private String logFile;

    @Option(names = {"--log-level"},
            description = "Log level: TRACE, DEBUG, INFO, WARN, ERROR (default: DEBUG when --log-file is set)")
    private String logLevel;

    @Override
    public Integer call() throws Exception {
        if (logFile != null && !logFile.isEmpty()) {
            System.setProperty("dta.log.file", logFile);
        }
        if (logLevel != null && !logLevel.isEmpty()) {
            System.setProperty("dta.log.level", logLevel.toUpperCase());
        }

        // Pre-flight the daemon — fail fast if it can't start, instead of
        // surfacing the error on the first tool call.
        try {
            DaemonLauncher.ensureDaemonRunning();
        } catch (Exception e) {
            System.err.println("Warning: Could not pre-start daemon: " + e.getMessage());
            System.err.println("Daemon will be started on first tool invocation.");
        }

        if (http) {
            return serveHttp();
        }
        // Default: stdio mode (current behavior, unchanged)
        McpServer.start(logFile, logLevel);
        return 0;
    }

    private Integer serveHttp() throws Exception {
        McpHttpServer server = new McpHttpServer();
        int actualPort;
        try {
            actualPort = server.start(port);
        } catch (Exception e) {
            System.err.println("Failed to start HTTP MCP server on port " + port + ": " + e.getMessage());
            return 1;
        }
        System.err.println("DTA MCP HTTP server listening at http://localhost:" + actualPort + "/mcp");
        System.err.println("Configure your client with:");
        System.err.println("  dta-cli mcp install --target as-gemini --port " + actualPort);
        System.err.println("Press Ctrl+C to stop.");

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "dta-mcp-shutdown"));
        // Block — Jetty runs in worker threads but the main thread must stay alive.
        Thread.currentThread().join();
        return 0;
    }
}
