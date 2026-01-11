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

/**
 * Performs a swipe gesture on an Android device.
 */
public class SwipeTool extends AdtTool {

    @Override
    public String getName() {
        return "swipe";
    }

    @Override
    public String getDescription() {
        return "Performs a swipe gesture from one point to another on the device screen. " +
               "Useful for scrolling lists, dismissing dialogs, or navigating between screens.";
    }

    @Override
    public String getInputSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "start_x": {
                            "type": "integer",
                            "description": "Starting X coordinate in pixels."
                        },
                        "start_y": {
                            "type": "integer",
                            "description": "Starting Y coordinate in pixels."
                        },
                        "end_x": {
                            "type": "integer",
                            "description": "Ending X coordinate in pixels."
                        },
                        "end_y": {
                            "type": "integer",
                            "description": "Ending Y coordinate in pixels."
                        },
                        "duration_ms": {
                            "type": "integer",
                            "description": "Swipe duration in milliseconds (default: 300). Longer duration = slower swipe."
                        },
                        "device": {
                            "type": "string",
                            "description": "Device serial number. If not specified, uses first available device."
                        }
                    },
                    "required": ["start_x", "start_y", "end_x", "end_y"]
                }
                """;
    }

    @Override
    public Result<?> execute(Map<String, Object> args, SessionManager session) {
        Integer startX = getIntParam(args, "start_x");
        Integer startY = getIntParam(args, "start_y");
        Integer endX = getIntParam(args, "end_x");
        Integer endY = getIntParam(args, "end_y");
        int durationMs = getIntParam(args, "duration_ms", 300);
        String device = getStringParam(args, "device");

        if (startX == null || startY == null || endX == null || endY == null) {
            return new Failure<>(null,
                    "Missing required parameters: start_x, start_y, end_x, end_y are all required. " +
                    "For scrolling down, swipe from center-bottom to center-top.");
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
            command.add("swipe");
            command.add(String.valueOf(startX));
            command.add(String.valueOf(startY));
            command.add(String.valueOf(endX));
            command.add(String.valueOf(endY));
            command.add(String.valueOf(durationMs));

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
                        "Swipe command failed: " + output);
            }

            return new Success<>(null,
                    String.format("Swiped from (%d, %d) to (%d, %d) in %dms on %s. " +
                                  "Use screenshot or compose_tree to see the result.",
                            startX, startY, endX, endY, durationMs, device));

        } catch (Exception e) {
            return new Failure<>(e,
                    "Failed to perform swipe: " + e.getMessage());
        }
    }
}
