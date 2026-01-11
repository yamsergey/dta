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
 * Presses a key on an Android device.
 */
public class PressKeyTool extends AdtTool {

    @Override
    public String getName() {
        return "press_key";
    }

    @Override
    public String getDescription() {
        return "Presses a key on the Android device. " +
               "Common keys: BACK (go back), HOME (home screen), ENTER (submit form), " +
               "DPAD_UP/DOWN/LEFT/RIGHT (navigation), TAB, ESCAPE, VOLUME_UP/DOWN, POWER. " +
               "You can also use numeric key codes directly.";
    }

    @Override
    public String getInputSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "key": {
                            "type": "string",
                            "description": "Key name (e.g., 'BACK', 'HOME', 'ENTER') or numeric key code."
                        },
                        "device": {
                            "type": "string",
                            "description": "Device serial number. If not specified, uses first available device."
                        }
                    },
                    "required": ["key"]
                }
                """;
    }

    // Common key name to keycode mapping
    private static final Map<String, Integer> KEY_CODES = Map.ofEntries(
            Map.entry("BACK", 4),
            Map.entry("HOME", 3),
            Map.entry("ENTER", 66),
            Map.entry("TAB", 61),
            Map.entry("ESCAPE", 111),
            Map.entry("DPAD_UP", 19),
            Map.entry("DPAD_DOWN", 20),
            Map.entry("DPAD_LEFT", 21),
            Map.entry("DPAD_RIGHT", 22),
            Map.entry("DPAD_CENTER", 23),
            Map.entry("VOLUME_UP", 24),
            Map.entry("VOLUME_DOWN", 25),
            Map.entry("POWER", 26),
            Map.entry("MENU", 82),
            Map.entry("SEARCH", 84),
            Map.entry("DEL", 67),
            Map.entry("SPACE", 62),
            Map.entry("APP_SWITCH", 187)
    );

    @Override
    public Result<?> execute(Map<String, Object> args, SessionManager session) {
        String key = getStringParam(args, "key");
        String device = getStringParam(args, "device");

        if (key == null || key.isEmpty()) {
            return new Failure<>(null,
                    "Missing required parameter: key. " +
                    "Common keys: BACK, HOME, ENTER, DPAD_UP/DOWN/LEFT/RIGHT, TAB, ESCAPE.");
        }

        // Get device
        if (device == null) {
            Result<String> deviceResult = session.getDefaultDevice();
            if (deviceResult instanceof Failure<String> f) {
                return f;
            }
            device = ((Success<String>) deviceResult).value();
        }

        // Resolve key code
        String keyCode;
        String upperKey = key.toUpperCase();
        if (KEY_CODES.containsKey(upperKey)) {
            keyCode = String.valueOf(KEY_CODES.get(upperKey));
        } else {
            // Try to use as numeric code or KEYCODE_ name
            try {
                Integer.parseInt(key);
                keyCode = key;
            } catch (NumberFormatException e) {
                // Use as KEYCODE_ name
                keyCode = "KEYCODE_" + upperKey;
            }
        }

        try {
            List<String> command = new ArrayList<>();
            command.add(session.getAdbPath());
            command.add("-s");
            command.add(device);
            command.add("shell");
            command.add("input");
            command.add("keyevent");
            command.add(keyCode);

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
                        "Key press failed: " + output);
            }

            return new Success<>(null,
                    String.format("Pressed key '%s' (code: %s) on %s. " +
                                  "Use screenshot or compose_tree to see the result.",
                            key, keyCode, device));

        } catch (Exception e) {
            return new Failure<>(e,
                    "Failed to press key: " + e.getMessage());
        }
    }
}
