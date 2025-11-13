package io.yamsergey.adt.cli.inspect;

import io.yamsergey.adt.tools.android.inspect.LogcatCapture;
import io.yamsergey.adt.tools.sugar.Failure;
import io.yamsergey.adt.tools.sugar.Result;
import io.yamsergey.adt.tools.sugar.Success;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Callable;

/**
 * CLI command for capturing logcat logs from an Android device.
 *
 * <p>This command captures device logs from a connected Android device or emulator
 * using ADB logcat. Supports filtering by priority, tags, and line limits.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * # Capture all logs
 * adt-cli inspect logcat
 *
 * # Save to file
 * adt-cli inspect logcat -o logcat.txt
 *
 * # Capture last 500 lines
 * adt-cli inspect logcat --lines 500
 *
 * # Capture only errors and warnings
 * adt-cli inspect logcat --priority W
 *
 * # Clear logs first, then capture
 * adt-cli inspect logcat --clear -o logcat.txt
 *
 * # Filter by tag
 * adt-cli inspect logcat --tag "ActivityManager:I *:S"
 *
 * # Capture from specific device
 * adt-cli inspect logcat -d emulator-5554 -o logcat.txt
 * </pre>
 */
@Command(name = "logcat",
         description = "Capture logcat logs from Android device.")
public class LogcatCommand implements Callable<Integer> {

    @Option(names = {"-o", "--output"},
            description = "Output file path. If not specified, prints to stdout.")
    private String outputPath;

    @Option(names = {"-d", "--device"},
            description = "Device serial number. If not specified, uses first available device.")
    private String deviceSerial;

    @Option(names = {"-n", "--lines"},
            description = "Maximum number of lines to capture. If not specified, captures all.")
    private Integer maxLines;

    @Option(names = {"-p", "--priority"},
            description = "Minimum priority level: V (Verbose), D (Debug), I (Info), W (Warning), E (Error), F (Fatal).")
    private String priority;

    @Option(names = {"-t", "--tag"},
            description = "Tag filter (e.g., 'ActivityManager:I *:S' for only ActivityManager at Info level).")
    private String tagFilter;

    @Option(names = {"--clear"},
            description = "Clear logs before capturing.")
    private boolean clearFirst;

    @Option(names = {"-f", "--format"},
            description = "Log format: brief, process, tag, thread, raw, time, threadtime (default), long.")
    private String format;

    @Option(names = {"--timeout"},
            description = "Timeout in seconds for ADB commands (default: 30).")
    private Integer timeoutSeconds;

    @Option(names = {"--adb-path"},
            description = "Path to ADB executable. If not specified, uses 'adb' from PATH.")
    private String adbPath;

    @Override
    public Integer call() throws Exception {
        // Validate priority if specified
        if (priority != null && !priority.isEmpty()) {
            String p = priority.toUpperCase();
            if (!p.matches("[VDIWEF]")) {
                System.err.println("Error: Invalid priority '" + priority + "'. Must be one of: V, D, I, W, E, F");
                return 1;
            }
            priority = p;
        }

        // Build the capture
        LogcatCapture.LogcatCaptureBuilder builder = LogcatCapture.builder()
                .clearFirst(clearFirst);

        if (deviceSerial != null && !deviceSerial.isEmpty()) {
            builder.deviceSerial(deviceSerial);
        }

        if (maxLines != null && maxLines > 0) {
            builder.maxLines(maxLines);
        }

        if (priority != null && !priority.isEmpty()) {
            builder.priority(priority);
        }

        if (tagFilter != null && !tagFilter.isEmpty()) {
            builder.tagFilter(tagFilter);
        }

        if (format != null && !format.isEmpty()) {
            builder.format(format);
        }

        if (timeoutSeconds != null && timeoutSeconds > 0) {
            builder.timeoutSeconds(timeoutSeconds);
        }

        if (adbPath != null && !adbPath.isEmpty()) {
            builder.adbPath(adbPath);
        }

        // Set output file if specified
        File outputFile = null;
        if (outputPath != null && !outputPath.isEmpty()) {
            outputFile = new File(outputPath);

            // Create output directory if needed
            File outputDir = outputFile.getParentFile();
            if (outputDir != null && !outputDir.exists()) {
                if (!outputDir.mkdirs()) {
                    System.err.println("Error: Failed to create output directory: " + outputDir.getAbsolutePath());
                    return 1;
                }
            }

            builder.outputFile(outputFile);
        }

        // Execute the capture
        System.err.println("Capturing logcat logs...");
        if (deviceSerial != null) {
            System.err.println("Device: " + deviceSerial);
        }
        if (clearFirst) {
            System.err.println("Clearing logs first...");
        }
        if (maxLines != null) {
            System.err.println("Max lines: " + maxLines);
        }
        if (priority != null) {
            System.err.println("Priority: " + priority + " and above");
        }

        Result<String> result = builder.build().capture();

        return switch (result) {
            case Success<String> success -> {
                String logs = success.value();
                String description = success.description() != null ? success.description() : "Logs captured successfully";

                if (outputPath != null) {
                    System.err.println("Success: " + description);
                    System.err.println("Output: " + outputFile.getAbsolutePath());
                } else {
                    // Print logs to stdout
                    System.out.println(logs);
                }

                yield 0;
            }
            case Failure<String> failure -> {
                String description = failure.description() != null ? failure.description() : "Failed to capture logs";
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
