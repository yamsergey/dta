package io.yamsergey.dta.cli.util;

import io.yamsergey.dta.cli.App;
import io.yamsergey.dta.tools.android.inspect.compose.HealthResponse;
import io.yamsergey.dta.tools.android.inspect.compose.SidekickClient;
import io.yamsergey.dta.tools.android.inspect.compose.SidekickConnectionManager.ConnectionInfo;
import io.yamsergey.dta.tools.sugar.Result;
import io.yamsergey.dta.tools.sugar.Success;

import java.io.PrintStream;

/**
 * Utility for checking version compatibility between CLI tools and sidekick.
 */
public class VersionChecker {

    private static final String WARNING_PREFIX = "⚠️  ";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_RESET = "\u001B[0m";

    /**
     * Checks if the CLI version is compatible with the sidekick version.
     * Returns true if compatible, false if there's a mismatch.
     *
     * @param sidekickVersion the sidekick version string
     * @return true if compatible
     */
    public static boolean isCompatible(String toolVersion, String sidekickVersion) {
        if (toolVersion == null || sidekickVersion == null) {
            return true; // Can't check, assume compatible
        }
        if ("unknown".equals(toolVersion) || "unknown".equals(sidekickVersion)) {
            return true; // Can't check, assume compatible
        }

        String[] tool = toolVersion.split("\\.");
        String[] sidekick = sidekickVersion.split("\\.");

        if (tool.length < 2 || sidekick.length < 2) {
            return true; // Can't parse, assume compatible
        }

        // Major and minor must match
        return tool[0].equals(sidekick[0]) && tool[1].equals(sidekick[1]);
    }

    /**
     * Checks version compatibility with the connected sidekick and prints
     * a warning if there's a mismatch.
     *
     * @param client the sidekick client to check
     * @param out the output stream for warnings
     * @return true if compatible (or unable to check), false if mismatch
     */
    public static boolean checkAndWarn(SidekickClient client, PrintStream out) {
        String toolVersion = App.getVersion();

        Result<HealthResponse> healthResult = client.checkHealthTyped();
        if (!(healthResult instanceof Success<HealthResponse> success)) {
            return true; // Can't check, don't warn
        }

        HealthResponse health = success.value();
        String sidekickVersion = health.version();

        if (!isCompatible(toolVersion, sidekickVersion)) {
            printWarning(out, "CLI", toolVersion, sidekickVersion);
            return false;
        }

        return true;
    }

    /**
     * Checks version compatibility using the connection info from SidekickConnectionManager.
     * Uses the sidekick version already stored in the connection info, avoiding an extra
     * health check round-trip.
     *
     * @param conn the connection info containing the sidekick version
     * @param out the output stream for warnings
     * @return true if compatible (or unable to check), false if mismatch
     */
    public static boolean checkAndWarnFromConn(ConnectionInfo conn, PrintStream out) {
        String toolVersion = App.getVersion();
        String sidekickVersion = conn.sidekickVersion();

        if (!isCompatible(toolVersion, sidekickVersion)) {
            printWarning(out, "CLI", toolVersion, sidekickVersion);
            return false;
        }

        return true;
    }

    /**
     * Prints a version mismatch warning.
     *
     * @param out the output stream
     * @param toolName the name of the tool (CLI, MCP, etc.)
     * @param toolVersion the tool version
     * @param sidekickVersion the sidekick version
     */
    public static void printWarning(PrintStream out, String toolName, String toolVersion, String sidekickVersion) {
        boolean useAnsi = System.console() != null;
        String prefix = useAnsi ? ANSI_YELLOW + WARNING_PREFIX : WARNING_PREFIX;
        String suffix = useAnsi ? ANSI_RESET : "";

        out.println(prefix + "Version mismatch: " + toolName + " v" + toolVersion + ", Sidekick v" + sidekickVersion + suffix);
        out.println("   Some features may not work correctly. Update sidekick dependency to " + toolVersion);
    }

    /**
     * Creates a warning message for inclusion in JSON responses.
     *
     * @param toolName the name of the tool
     * @param toolVersion the tool version
     * @param sidekickVersion the sidekick version
     * @return the warning message
     */
    public static String createWarningMessage(String toolName, String toolVersion, String sidekickVersion) {
        return "Version mismatch: " + toolName + " v" + toolVersion + ", Sidekick v" + sidekickVersion;
    }
}
