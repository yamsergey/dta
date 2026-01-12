package io.yamsergey.adt.mcp.tools.debug;

import io.yamsergey.adt.mcp.session.SessionManager;
import io.yamsergey.adt.mcp.tools.AdtTool;
import io.yamsergey.adt.tools.sugar.Failure;
import io.yamsergey.adt.tools.sugar.Result;
import io.yamsergey.adt.tools.sugar.Success;

import java.util.Map;

/**
 * Stops the ADT Debug Server subprocess.
 */
public class StopDebugServerTool extends AdtTool {

    @Override
    public String getName() {
        return "stop_debug_server";
    }

    @Override
    public String getDescription() {
        return "Stops the ADT Debug Server if it's running. " +
               "Use this to free up resources when debugging is complete.";
    }

    @Override
    public String getInputSchema() {
        return """
                {
                    "type": "object",
                    "properties": {}
                }
                """;
    }

    @Override
    public Result<?> execute(Map<String, Object> args, SessionManager session) {
        if (!session.isDebugServerRunning()) {
            return new Failure<>(null, "Debug server is not running.");
        }

        session.stopDebugServer();

        return new Success<>(
                Map.of("status", "stopped"),
                "Debug server stopped.");
    }
}
