package io.yamsergey.adt.mcp.tools.device;

import io.modelcontextprotocol.spec.McpSchema;
import io.yamsergey.adt.mcp.session.SessionManager;
import io.yamsergey.adt.mcp.tools.AdtTool;
import io.yamsergey.adt.tools.android.inspect.Screenshot;
import io.yamsergey.adt.tools.android.inspect.ScreenshotCapture;
import io.yamsergey.adt.tools.sugar.Failure;
import io.yamsergey.adt.tools.sugar.Result;
import io.yamsergey.adt.tools.sugar.Success;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;

/**
 * Captures a screenshot from an Android device.
 */
public class ScreenshotTool extends AdtTool {

    @Override
    public String getName() {
        return "screenshot";
    }

    @Override
    public String getDescription() {
        return "Captures a screenshot from an Android device as base64 PNG. " +
               "Use for visual verification AFTER making changes. " +
               "For understanding UI structure or finding elements, use compose_tree instead - " +
               "it provides structured data with element coordinates.";
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
                        }
                    },
                    "required": []
                }
                """;
    }

    @Override
    public Result<?> execute(Map<String, Object> args, SessionManager session) {
        String device = getStringParam(args, "device");

        // Get device
        if (device == null) {
            Result<String> deviceResult = session.getDefaultDevice();
            if (deviceResult instanceof Failure<String> f) {
                return f;
            }
            device = ((Success<String>) deviceResult).value();
        }

        try {
            // Create temp file for screenshot
            File tempFile = File.createTempFile("screenshot-", ".png");
            tempFile.deleteOnExit();

            ScreenshotCapture capture = ScreenshotCapture.builder()
                    .adbPath(session.getAdbPath())
                    .deviceSerial(device)
                    .outputFile(tempFile)
                    .build();

            Result<Screenshot> result = capture.capture();

            return switch (result) {
                case Success<Screenshot> s -> {
                    Screenshot screenshot = s.value();
                    String description = String.format(
                            "Screenshot captured from %s. Dimensions: %dx%d.",
                            device,
                            screenshot.getWidth() != null ? screenshot.getWidth() : 0,
                            screenshot.getHeight() != null ? screenshot.getHeight() : 0);

                    // Read and return as image result
                    byte[] imageData = Files.readAllBytes(tempFile.toPath());
                    yield new Success<>(imageData, description);
                }
                case Failure<Screenshot> f -> f;
            };

        } catch (Exception e) {
            return new Failure<>(e,
                    "Failed to capture screenshot: " + e.getMessage() + ". " +
                    "Ensure the device screen is on and unlocked.");
        }
    }

    @Override
    protected McpSchema.CallToolResult toCallToolResult(Result<?> result) {
        if (result instanceof Success<?> s && s.value() instanceof byte[] imageData) {
            return imageResult(imageData, s.description());
        }
        return super.toCallToolResult(result);
    }
}
