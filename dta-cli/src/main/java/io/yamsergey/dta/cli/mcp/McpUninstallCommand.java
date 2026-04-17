package io.yamsergey.dta.cli.mcp;

import io.yamsergey.dta.mcp.McpInstaller;
import io.yamsergey.dta.mcp.McpInstaller.Result;
import io.yamsergey.dta.mcp.McpInstaller.Target;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code dta-cli mcp uninstall --target <agent>} — removes the dta MCP server
 * entry from a known agent's config file, leaving every other server in the
 * file untouched. Backs up to {@code .bak} before writing.
 */
@Command(name = "uninstall",
         description = "Remove the dta MCP server entry from a known agent's config.")
public class McpUninstallCommand implements Callable<Integer> {

    @Option(names = {"--target", "-t"},
            required = true,
            description = "Target agent: as-gemini, claude-code")
    private String target;

    @Option(names = {"--config", "-c"},
            description = "Override the target config file path (otherwise auto-discovered)")
    private String configPath;

    @Override
    public Integer call() {
        Target t = McpInstallCommand.parseTarget(target);
        if (t == null || t == Target.CLIPBOARD || t == Target.PRINT) {
            System.err.println("Cannot uninstall from target: " + target);
            System.err.println("Uninstallable targets: as-gemini, claude-code");
            return 2;
        }
        Path hint = configPath != null ? Path.of(configPath) : null;
        Result result = McpInstaller.uninstall(t, hint);
        if (result.success()) {
            System.out.println(result.message());
            return 0;
        }
        System.err.println("Error: " + result.message());
        return 1;
    }
}
