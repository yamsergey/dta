package io.yamsergey.dta.cli.mcp;

import io.yamsergey.dta.mcp.McpInstaller;
import io.yamsergey.dta.mcp.McpInstaller.Result;
import io.yamsergey.dta.mcp.McpInstaller.Target;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * {@code dta-cli mcp install --target <agent> --port N} — writes the dta MCP
 * server entry into a known agent's config file. Pairs with
 * {@code dta-cli mcp serve --http --port N} (or the plugin's MCP tab running
 * on the same port).
 *
 * <p>Targets:</p>
 * <ul>
 *   <li>{@code as-gemini} — Android Studio Gemini's {@code mcp.json}</li>
 *   <li>{@code claude-code} — {@code ~/.claude.json}</li>
 *   <li>{@code clipboard} — copy the JSON snippet to the system clipboard</li>
 *   <li>{@code print} — print the JSON snippet to stdout</li>
 * </ul>
 *
 * <p>For file-based targets, the existing config is backed up to {@code .bak}
 * and the install JSON-merges (preserves other servers).</p>
 */
@Command(name = "install",
         description = "Install the dta MCP server into a known agent's config.")
public class McpInstallCommand implements Callable<Integer> {

    @Option(names = {"--target", "-t"},
            required = true,
            description = "Target agent: as-gemini, claude-code, clipboard, print")
    private String target;

    @Option(names = {"--port", "-p"},
            defaultValue = "12321",
            description = "Port the dta MCP HTTP server is running on (default: ${DEFAULT-VALUE})")
    private int port;

    @Option(names = {"--config", "-c"},
            description = "Override the target config file path (otherwise auto-discovered)")
    private String configPath;

    @Override
    public Integer call() {
        Target t = parseTarget(target);
        if (t == null) {
            System.err.println("Unknown target: " + target);
            System.err.println("Valid targets: as-gemini, claude-code, clipboard, print");
            return 2;
        }
        Path hint = configPath != null ? Path.of(configPath) : null;
        Result result = McpInstaller.install(t, port, hint);
        if (result.success()) {
            System.out.println(result.message());
            return 0;
        }
        System.err.println("Error: " + result.message());
        return 1;
    }

    static Target parseTarget(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "as-gemini", "as_gemini", "android-studio", "gemini" -> Target.AS_GEMINI;
            case "claude-code", "claude_code", "claude" -> Target.CLAUDE_CODE;
            case "clipboard", "clip" -> Target.CLIPBOARD;
            case "print", "stdout" -> Target.PRINT;
            default -> null;
        };
    }
}
