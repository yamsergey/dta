package io.yamsergey.adt.cli.inspect;

import io.yamsergey.adt.tools.android.inspect.Screenshot;
import io.yamsergey.adt.tools.android.inspect.ScreenshotCapture;
import io.yamsergey.adt.tools.sugar.Failure;
import io.yamsergey.adt.tools.sugar.Result;
import io.yamsergey.adt.tools.sugar.Success;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Callable;

/**
 * CLI command for capturing screenshots from an Android device.
 *
 * <p>This command captures screenshots from a connected Android device or emulator
 * using ADB screencap. The screenshot is saved as a PNG file.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * # Capture screenshot with auto-generated filename
 * adt-cli inspect screenshot
 *
 * # Save to specific file
 * adt-cli inspect screenshot -o screenshot.png
 *
 * # Capture from specific device
 * adt-cli inspect screenshot -d emulator-5554 -o screen.png
 *
 * # Specify custom ADB path
 * adt-cli inspect screenshot --adb-path /custom/path/to/adb -o screen.png
 * </pre>
 */
@Command(name = "screenshot",
         description = "Capture screenshot from Android device.")
public class ScreenshotCommand implements Callable<Integer> {

    @Parameters(index = "0", 
                description = "Output PNG file path (default: screenshot-TIMESTAMP.png)",
                arity = "0..1")
    private String outputPath;

    @Option(names = {"-o", "--output"},
            description = "Output PNG file path (alternative to positional argument).")
    private String outputPathOption;

    @Option(names = {"-d", "--device"},
            description = "Device serial number. If not specified, uses first available device.")
    private String deviceSerial;

    @Option(names = {"--timeout"},
            description = "Timeout in seconds for ADB commands (default: 30).")
    private Integer timeoutSeconds;

    @Option(names = {"--adb-path"},
            description = "Path to ADB executable. If not specified, uses 'adb' from PATH.")
    private String adbPath;

    @Override
    public Integer call() throws Exception {
        // Determine output path
        String finalOutputPath = outputPathOption != null ? outputPathOption : outputPath;
        
        if (finalOutputPath == null || finalOutputPath.isEmpty()) {
            // Generate default filename with timestamp
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            finalOutputPath = "screenshot-" + timestamp + ".png";
        }

        File outputFile = new File(finalOutputPath);

        // Create output directory if needed
        File outputDir = outputFile.getParentFile();
        if (outputDir != null && !outputDir.exists()) {
            if (!outputDir.mkdirs()) {
                System.err.println("Error: Failed to create output directory: " + outputDir.getAbsolutePath());
                return 1;
            }
        }

        // Build the capture
        ScreenshotCapture.ScreenshotCaptureBuilder builder = ScreenshotCapture.builder()
                .outputFile(outputFile);

        if (deviceSerial != null && !deviceSerial.isEmpty()) {
            builder.deviceSerial(deviceSerial);
        }

        if (timeoutSeconds != null && timeoutSeconds > 0) {
            builder.timeoutSeconds(timeoutSeconds);
        }

        if (adbPath != null && !adbPath.isEmpty()) {
            builder.adbPath(adbPath);
        }

        // Execute the capture
        System.err.println("Capturing screenshot...");
        if (deviceSerial != null) {
            System.err.println("Device: " + deviceSerial);
        }

        Result<Screenshot> result = builder.build().capture();

        return switch (result) {
            case Success<Screenshot> success -> {
                Screenshot screenshot = success.value();
                String description = success.description() != null ? success.description() : "Screenshot captured successfully";

                System.err.println("Success: " + description);
                System.err.println("Output: " + screenshot.getFile().getAbsolutePath());
                
                if (screenshot.getWidth() != null && screenshot.getHeight() != null) {
                    System.err.println("Dimensions: " + screenshot.getWidth() + "x" + screenshot.getHeight());
                }

                yield 0;
            }
            case Failure<Screenshot> failure -> {
                String description = failure.description() != null ? failure.description() : "Failed to capture screenshot";
                System.err.println("Error: " + description);
                yield 1;
            }
            default -> {
                System.err.println("Error: Unknown result type");
                yield 1;
            }
        };
    }
}
