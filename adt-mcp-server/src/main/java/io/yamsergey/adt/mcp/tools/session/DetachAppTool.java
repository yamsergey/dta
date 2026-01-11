package io.yamsergey.adt.mcp.tools.session;

import io.yamsergey.adt.mcp.session.SessionManager;
import io.yamsergey.adt.mcp.tools.AdtTool;
import io.yamsergey.adt.tools.sugar.Failure;
import io.yamsergey.adt.tools.sugar.Result;
import io.yamsergey.adt.tools.sugar.Success;

import java.util.Map;

/**
 * Detaches from an app session.
 */
public class DetachAppTool extends AdtTool {

    @Override
    public String getName() {
        return "detach_app";
    }

    @Override
    public String getDescription() {
        return "Detaches from an app session, removing port forwarding. " +
               "The sidekick agent continues running in the app but is no longer accessible. " +
               "Use attach_app to reconnect later.";
    }

    @Override
    public String getInputSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "package": {
                            "type": "string",
                            "description": "Package name of the app to detach from."
                        },
                        "device": {
                            "type": "string",
                            "description": "Device serial number. If not specified, uses first available device."
                        }
                    },
                    "required": ["package"]
                }
                """;
    }

    @Override
    public Result<?> execute(Map<String, Object> args, SessionManager session) {
        String packageName = getStringParam(args, "package");
        String device = getStringParam(args, "device");

        if (packageName == null || packageName.isEmpty()) {
            return new Failure<>(null,
                    "Missing required parameter: package. " +
                    "Use list_sessions to see active sessions.");
        }

        // Get device
        if (device == null) {
            Result<String> deviceResult = session.getDefaultDevice();
            if (deviceResult instanceof Failure<String> f) {
                return f;
            }
            device = ((Success<String>) deviceResult).value();
        }

        return session.removeSession(device, packageName);
    }
}
