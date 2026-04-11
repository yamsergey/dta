package io.yamsergey.dta.cli.mcp;

import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.util.concurrent.Callable;

/**
 * Parent {@code dta-cli mcp} command. Has three subcommands:
 *
 * <ul>
 *   <li>{@code serve} — runs the MCP server (stdio by default, {@code --http}
 *       for the HTTP transport on a user-picked port)</li>
 *   <li>{@code install} — writes the dta MCP server entry into a known agent's
 *       config file (Android Studio Gemini, Claude Code, etc.) or copies/prints
 *       the snippet</li>
 *   <li>{@code uninstall} — removes the dta entry from a known agent's config</li>
 * </ul>
 *
 * <p>Calling {@code dta-cli mcp} without a subcommand defaults to
 * {@code dta-cli mcp serve} (stdio) — preserves backward compatibility with
 * existing MCP client configs that invoke {@code dta-cli mcp} as a leaf
 * command.</p>
 */
@Command(name = "mcp",
         description = "DTA Model Context Protocol server — serve and configure.",
         subcommands = {
             McpServeCommand.class,
             McpInstallCommand.class,
             McpUninstallCommand.class,
         })
public class McpCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    /**
     * Default behavior when invoked without a subcommand: run the stdio
     * server. This keeps {@code dta-cli mcp} working for existing MCP client
     * configurations that point at the bare command.
     */
    @Override
    public Integer call() throws Exception {
        // Delegate to McpServeCommand with default options (stdio)
        return new McpServeCommand().call();
    }
}
