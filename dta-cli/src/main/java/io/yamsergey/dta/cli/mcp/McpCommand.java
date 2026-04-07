package io.yamsergey.dta.cli.mcp;

import io.yamsergey.dta.daemon.DaemonLauncher;
import io.yamsergey.dta.mcp.McpServer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

@Command(name = "mcp",
         description = "Start the DTA MCP server (Model Context Protocol)")
public class McpCommand implements Callable<Integer> {

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

        // Ensure daemon is running before starting MCP server
        // This is done lazily on first tool invocation by McpServer itself,
        // but we pre-flight it here to fail fast if daemon can't start
        try {
            DaemonLauncher.ensureDaemonRunning();
        } catch (Exception e) {
            System.err.println("Warning: Could not pre-start daemon: " + e.getMessage());
            System.err.println("Daemon will be started on first tool invocation.");
        }

        McpServer.start(logFile, logLevel);
        return 0;
    }
}
