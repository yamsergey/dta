package io.yamsergey.dta.cli.mcp;

import io.yamsergey.dta.mcp.McpServer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * CLI command to start the MCP server.
 *
 * <p>This starts the DTA MCP server using STDIO transport, suitable for
 * integration with AI assistants like Claude Desktop.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * # Start MCP server (uses stdin/stdout)
 * dta-cli mcp
 *
 * # Start MCP server with file logging
 * dta-cli mcp --log-file /tmp/dta-mcp.log
 * </pre>
 *
 * <p>Claude Desktop configuration (claude_desktop_config.json):</p>
 * <pre>
 * {
 *   "mcpServers": {
 *     "dta": {
 *       "command": "/path/to/dta-cli",
 *       "args": ["mcp", "--log-file", "/tmp/dta-mcp.log"]
 *     }
 *   }
 * }
 * </pre>
 */
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
        McpServer.start(logFile, logLevel);
        return 0;
    }
}
