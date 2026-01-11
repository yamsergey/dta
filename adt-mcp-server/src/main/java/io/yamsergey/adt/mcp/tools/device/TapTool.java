package io.yamsergey.adt.mcp.tools.device;

import io.yamsergey.adt.mcp.session.SessionManager;
import io.yamsergey.adt.mcp.tools.AdtTool;
import io.yamsergey.adt.tools.sugar.Failure;
import io.yamsergey.adt.tools.sugar.Result;
import io.yamsergey.adt.tools.sugar.Success;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Performs a tap gesture on an Android device.
 */
public class TapTool extends AdtTool {

    @Override
    public String getName() {
        return "tap";
    }

    @Override
    public String getDescription() {
        return "Performs a tap gesture at specified coordinates on the device screen. " +
               "Use compose_tree or screenshot to determine correct coordinates. " +
               "Coordinates are in pixels from top-left corner.";
    }

    @Override
    public String getInputSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "x": {
                            "type": "integer",
                            "description": "X coordinate in pixels from left edge."
                        },
                        "y": {
                            "type": "integer",
                            "description": "Y coordinate in pixels from top edge."
                        },
                        "device": {
                            "type": "string",
                            "description": "Device serial number. If not specified, uses first available device."
                        }
                    },
                    "required": ["x", "y"]
                }
                """;
    }

    @Override
    public Result<?> execute(Map<String, Object> args, SessionManager session) {
        Integer x = getIntParam(args, "x");
        Integer y = getIntParam(args, "y");
        String device = getStringParam(args, "device");

        if (x == null || y == null) {
            return new Failure<>(null,
                    "Missing required parameters: x and y coordinates are required. " +
                    "Use compose_tree to find element bounds and calculate tap coordinates.");
        }

        // Get device
        if (device == null) {
            Result<String> deviceResult = session.getDefaultDevice();
            if (deviceResult instanceof Failure<String> f) {
                return f;
            }
            device = ((Success<String>) deviceResult).value();
        }

        try {
            List<String> command = new ArrayList<>();
            command.add(session.getAdbPath());
            command.add("-s");
            command.add(device);
            command.add("shell");
            command.add("input");
            command.add("tap");
            command.add(String.valueOf(x));
            command.add(String.valueOf(y));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                return new Failure<>(null,
                        "Tap command failed: " + output + ". " +
                        "Ensure the device is connected and the screen is on.");
            }

            return new Success<>(null,
                    String.format("Tapped at (%d, %d) on %s. " +
                                  "Use screenshot to verify the result, or " +
                                  "compose_tree to see updated UI state.",
                            x, y, device));

        } catch (Exception e) {
            return new Failure<>(e,
                    "Failed to perform tap: " + e.getMessage());
        }
    }
}
