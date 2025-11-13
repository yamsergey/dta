package io.yamsergey.adt.tools.android.inspect;

import io.yamsergey.adt.tools.sugar.Failure;
import io.yamsergey.adt.tools.sugar.Result;
import io.yamsergey.adt.tools.sugar.Success;
import lombok.Builder;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Captures logcat logs from an Android device using ADB.
 *
 * <p>This class provides functionality to capture device logs from a connected
 * Android device or emulator using ADB logcat command.</p>
 *
 * <p>The capture process:</p>
 * <ol>
 *   <li>Verifies ADB is available in the system PATH</li>
 *   <li>Checks for connected Android devices</li>
 *   <li>Executes logcat command with specified filters</li>
 *   <li>Captures logs and saves to file or returns as string</li>
 * </ol>
 *
 * <p>Example usage:</p>
 * <pre>
 * Result&lt;String&gt; result = LogcatCapture.builder()
 *     .deviceSerial("emulator-5554")
 *     .outputFile(new File("logcat.txt"))
 *     .maxLines(1000)
 *     .priority("W")  // Warning and above
 *     .build()
 *     .capture();
 *
 * if (result instanceof Success&lt;String&gt; success) {
 *     String logs = success.value();
 *     System.out.println("Captured " + logs.split("\n").length + " lines");
 * }
 * </pre>
 */
@Builder
public class LogcatCapture {

    /**
     * Optional device serial number. If not specified, uses the first available device.
     */
    private final String deviceSerial;

    /**
     * Optional output file to save the logs. If not specified, returns logs as string.
     */
    private final File outputFile;

    /**
     * Maximum number of lines to capture. If not specified, captures all available logs.
     */
    private final Integer maxLines;

    /**
     * Log priority filter (V, D, I, W, E, F, S). If not specified, captures all priorities.
     */
    private final String priority;

    /**
     * Tag filter (e.g., "ActivityManager:I *:S" to show only ActivityManager at Info level).
     */
    private final String tagFilter;

    /**
     * Whether to clear logs before capturing (default: false).
     */
    @Builder.Default
    private final boolean clearFirst = false;

    /**
     * Log format (brief, process, tag, thread, raw, time, threadtime, long). Default: threadtime.
     */
    @Builder.Default
    private final String format = "threadtime";

    /**
     * Timeout in seconds for ADB commands (default: 30).
     */
    @Builder.Default
    private final int timeoutSeconds = 30;

    /**
     * Path to ADB executable. If not specified, assumes 'adb' is in PATH.
     */
    private final String adbPath;

    /**
     * Captures logcat logs from the connected Android device.
     *
     * @return Result containing logs as String on success, or Failure with error description
     */
    public Result<String> capture() {
        try {
            // Determine ADB command
            String adbCommand = adbPath != null ? adbPath : "adb";

            // Verify ADB is available
            Result<Void> adbCheck = verifyAdb(adbCommand);
            if (adbCheck instanceof Failure<Void> failure) {
                return new Failure<>(null, failure.description());
            }

            // Build device-specific ADB command prefix
            List<String> adbPrefix = new ArrayList<>();
            adbPrefix.add(adbCommand);
            if (deviceSerial != null && !deviceSerial.isEmpty()) {
                adbPrefix.add("-s");
                adbPrefix.add(deviceSerial);
            }

            // Verify device is connected
            Result<String> deviceCheck = verifyDevice(adbPrefix);
            if (deviceCheck instanceof Failure<String> failure) {
                return new Failure<>(null, failure.description());
            }

            // Clear logs if requested
            if (clearFirst) {
                Result<Void> clearResult = clearLogs(adbPrefix);
                if (clearResult instanceof Failure<Void> failure) {
                    return new Failure<>(null, failure.description());
                }
            }

            // Capture logs
            Result<String> captureResult = captureLogs(adbPrefix);
            if (captureResult instanceof Failure<String> failure) {
                return new Failure<>(null, failure.description());
            }

            String logs = ((Success<String>) captureResult).value();

            // Save to output file if specified
            if (outputFile != null) {
                try {
                    Files.writeString(outputFile.toPath(), logs);
                } catch (IOException e) {
                    return new Failure<>(null, "Failed to write output file: " + e.getMessage());
                }
            }

            int lineCount = logs.isEmpty() ? 0 : logs.split("\n").length;
            return new Success<>(logs, "Captured " + lineCount + " lines of logs");

        } catch (Exception e) {
            return new Failure<>(null, "Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Verifies that ADB is available and accessible.
     */
    private Result<Void> verifyAdb(String adbCommand) {
        try {
            ProcessBuilder pb = new ProcessBuilder(adbCommand, "version");
            Process process = pb.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return new Failure<>(null, "ADB version check timed out");
            }

            if (process.exitValue() != 0) {
                return new Failure<>(null, "ADB is not accessible. Make sure Android SDK is installed and ADB is in PATH.");
            }

            return new Success<>(null, "ADB verified");

        } catch (IOException e) {
            return new Failure<>(null, "ADB not found. Make sure Android SDK is installed and ADB is in PATH.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Failure<>(null, "ADB verification interrupted");
        }
    }

    /**
     * Verifies that a device is connected and accessible.
     */
    private Result<String> verifyDevice(List<String> adbPrefix) {
        try {
            List<String> command = new ArrayList<>(adbPrefix);
            command.add("get-state");

            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return new Failure<>(null, "Device check timed out");
            }

            if (process.exitValue() != 0) {
                String error = readProcessError(process);
                if (deviceSerial != null) {
                    return new Failure<>(null, "Device '" + deviceSerial + "' not found or not accessible: " + error);
                } else {
                    return new Failure<>(null, "No device connected. Connect an Android device or start an emulator.");
                }
            }

            String state = readProcessOutput(process).trim();
            if (!"device".equals(state)) {
                return new Failure<>(null, "Device is not ready. Current state: " + state);
            }

            return new Success<>(state, "Device verified");

        } catch (IOException | InterruptedException e) {
            return new Failure<>(null, "Failed to verify device: " + e.getMessage());
        }
    }

    /**
     * Clears the logcat buffer.
     */
    private Result<Void> clearLogs(List<String> adbPrefix) {
        try {
            List<String> command = new ArrayList<>(adbPrefix);
            command.add("logcat");
            command.add("-c");

            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return new Failure<>(null, "Logcat clear timed out");
            }

            if (process.exitValue() != 0) {
                String error = readProcessError(process);
                return new Failure<>(null, "Failed to clear logs: " + error);
            }

            return new Success<>(null, "Logs cleared");

        } catch (IOException | InterruptedException e) {
            return new Failure<>(null, "Failed to clear logs: " + e.getMessage());
        }
    }

    /**
     * Captures logcat logs from the device.
     */
    private Result<String> captureLogs(List<String> adbPrefix) {
        try {
            List<String> command = new ArrayList<>(adbPrefix);
            command.add("logcat");
            command.add("-d");  // Dump and exit
            command.add("-v");
            command.add(format);

            // Add max lines if specified
            if (maxLines != null && maxLines > 0) {
                command.add("-t");
                command.add(String.valueOf(maxLines));
            }

            // Add priority filter if specified
            if (priority != null && !priority.isEmpty()) {
                command.add("*:" + priority);
            }

            // Add tag filter if specified
            if (tagFilter != null && !tagFilter.isEmpty()) {
                command.add(tagFilter);
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return new Failure<>(null, "Logcat capture timed out");
            }

            if (process.exitValue() != 0) {
                String error = readProcessError(process);
                return new Failure<>(null, "Logcat capture failed: " + error);
            }

            String output = readProcessOutput(process);
            return new Success<>(output, "Logs captured successfully");

        } catch (IOException | InterruptedException e) {
            return new Failure<>(null, "Failed to capture logs: " + e.getMessage());
        }
    }

    /**
     * Reads the standard output from a process.
     */
    private String readProcessOutput(Process process) throws IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        return output.toString();
    }

    /**
     * Reads the error output from a process.
     */
    private String readProcessError(Process process) throws IOException {
        StringBuilder error = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                error.append(line).append("\n");
            }
        }
        return error.toString().trim();
    }
}
