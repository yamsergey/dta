package io.yamsergey.adt.mcp.tools.device;

import io.yamsergey.adt.mcp.session.SessionManager;
import io.yamsergey.adt.mcp.tools.AdtTool;
import io.yamsergey.adt.tools.android.inspect.PackageInfo;
import io.yamsergey.adt.tools.android.inspect.PackageLister;
import io.yamsergey.adt.tools.sugar.Failure;
import io.yamsergey.adt.tools.sugar.Result;
import io.yamsergey.adt.tools.sugar.Success;

import java.util.List;
import java.util.Map;

/**
 * Lists debuggable packages on an Android device.
 */
public class ListPackagesTool extends AdtTool {

    @Override
    public String getName() {
        return "list_packages";
    }

    @Override
    public String getDescription() {
        return "Lists debuggable packages on an Android device. " +
               "By default shows only third-party debuggable apps. " +
               "Use these package names with attach_app to connect.";
    }

    @Override
    public String getInputSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "device": {
                            "type": "string",
                            "description": "Device serial number. If not specified, uses first available device."
                        },
                        "include_system": {
                            "type": "boolean",
                            "description": "Include system packages (default: false, only third-party apps)."
                        },
                        "include_non_debuggable": {
                            "type": "boolean",
                            "description": "Include non-debuggable packages (default: false, only debuggable apps)."
                        }
                    },
                    "required": []
                }
                """;
    }

    @Override
    public Result<?> execute(Map<String, Object> args, SessionManager session) {
        String device = getStringParam(args, "device");
        boolean includeSystem = getBoolParam(args, "include_system", false);
        boolean includeNonDebuggable = getBoolParam(args, "include_non_debuggable", false);

        // Get device
        if (device == null) {
            Result<String> deviceResult = session.getDefaultDevice();
            if (deviceResult instanceof Failure<String> f) {
                return f;
            }
            device = ((Success<String>) deviceResult).value();
        }

        // List packages
        PackageLister lister = PackageLister.builder()
                .adbPath(session.getAdbPath())
                .deviceSerial(device)
                .onlyThirdParty(!includeSystem)
                .onlyDebuggable(!includeNonDebuggable)
                .build();

        Result<List<PackageInfo>> result = lister.list();

        // Enhance the description for agent guidance
        return switch (result) {
            case Success<List<PackageInfo>> s -> {
                List<PackageInfo> packages = s.value();
                String guidance;
                if (packages.isEmpty()) {
                    guidance = "No debuggable packages found. " +
                               "Try include_system=true to see system apps, " +
                               "or include_non_debuggable=true to see all apps.";
                } else {
                    guidance = String.format(
                            "Found %d debuggable package(s). " +
                            "Use attach_app with device='%s' and package name to connect and inspect.",
                            packages.size(), device);
                }
                yield new Success<>(packages, guidance);
            }
            case Failure<List<PackageInfo>> f -> f;
        };
    }
}
