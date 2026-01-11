package io.yamsergey.adt.mcp.tools.device;

import io.yamsergey.adt.mcp.session.SessionManager;
import io.yamsergey.adt.mcp.tools.AdtTool;
import io.yamsergey.adt.tools.sugar.Result;

import java.util.List;
import java.util.Map;

/**
 * Lists connected Android devices and emulators.
 */
public class ListDevicesTool extends AdtTool {

    @Override
    public String getName() {
        return "list_devices";
    }

    @Override
    public String getDescription() {
        return "Lists all connected Android devices and emulators. " +
               "Returns device serial numbers that can be used with other tools.";
    }

    @Override
    public Result<?> execute(Map<String, Object> args, SessionManager session) {
        return session.listDevices();
    }
}
