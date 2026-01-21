package io.yamsergey.dta.cli.mcp;

import io.yamsergey.dta.mcp.McpServer;
import picocli.CommandLine.Command;

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
 * </pre>
 *
 * <p>Claude Desktop configuration (claude_desktop_config.json):</p>
 * <pre>
 * {
 *   "mcpServers": {
 *     "dta": {
 *       "command": "/path/to/dta-cli",
 *       "args": ["mcp"]
 *     }
 *   }
 * }
 * </pre>
 */
@Command(name = "mcp",
         description = "Start the DTA MCP server (Model Context Protocol)")
public class McpCommand implements Callable<Integer> {

    @Override
    public Integer call() throws Exception {
        // Start the MCP server - this blocks until interrupted
        McpServer.main(new String[]{});
        return 0;
    }
}
