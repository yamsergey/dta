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
 * Types text on an Android device.
 */
public class InputTextTool extends AdtTool {

    @Override
    public String getName() {
        return "input_text";
    }

    @Override
    public String getDescription() {
        return "Types text into the currently focused input field on the device. " +
               "First tap on an input field to focus it, then use this tool to enter text. " +
               "Special characters may not work correctly; use press_key for special keys.";
    }

    @Override
    public String getInputSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "text": {
                            "type": "string",
                            "description": "Text to type. Spaces will be escaped automatically."
                        },
                        "device": {
                            "type": "string",
                            "description": "Device serial number. If not specified, uses first available device."
                        }
                    },
                    "required": ["text"]
                }
                """;
    }

    @Override
    public Result<?> execute(Map<String, Object> args, SessionManager session) {
        String text = getStringParam(args, "text");
        String device = getStringParam(args, "device");

        if (text == null || text.isEmpty()) {
            return new Failure<>(null,
                    "Missing required parameter: text. " +
                    "Provide the text you want to type into the focused input field.");
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
            // Escape spaces for ADB input
            String escapedText = text.replace(" ", "%s");

            List<String> command = new ArrayList<>();
            command.add(session.getAdbPath());
            command.add("-s");
            command.add(device);
            command.add("shell");
            command.add("input");
            command.add("text");
            command.add(escapedText);

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
                        "Input text command failed: " + output + ". " +
                        "Ensure an input field is focused.");
            }

            return new Success<>(null,
                    String.format("Typed '%s' on %s. " +
                                  "Use press_key with 'ENTER' to submit, or screenshot to verify.",
                            text, device));

        } catch (Exception e) {
            return new Failure<>(e,
                    "Failed to input text: " + e.getMessage());
        }
    }
}
