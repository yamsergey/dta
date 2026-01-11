package io.yamsergey.adt.mcp.tools.session;

import io.yamsergey.adt.mcp.session.AppSession;
import io.yamsergey.adt.mcp.session.SessionManager;
import io.yamsergey.adt.mcp.tools.AdtTool;
import io.yamsergey.adt.tools.sugar.Result;
import io.yamsergey.adt.tools.sugar.Success;

import java.util.List;
import java.util.Map;

/**
 * Lists all active app sessions.
 */
public class ListSessionsTool extends AdtTool {

    @Override
    public String getName() {
        return "list_sessions";
    }

    @Override
    public String getDescription() {
        return "Lists all active app sessions (sidekick connections). " +
               "Shows which apps are currently connected for inspection.";
    }

    @Override
    public Result<?> execute(Map<String, Object> args, SessionManager session) {
        List<AppSession> sessions = session.listSessions();

        String guidance;
        if (sessions.isEmpty()) {
            guidance = "No active sessions. Use attach_app to connect to a debuggable app. " +
                       "First use list_packages to see available apps.";
        } else {
            guidance = String.format(
                    "Found %d active session(s). " +
                    "Use compose_tree, network_requests, or websocket_connections with device and package parameters. " +
                    "Use detach_app to disconnect from an app.",
                    sessions.size());
        }

        return new Success<>(sessions, guidance);
    }
}
