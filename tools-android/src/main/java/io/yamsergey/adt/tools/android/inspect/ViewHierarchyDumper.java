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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Dumps the current UI view hierarchy from an Android device using UIAutomator.
 *
 * <p>This class provides functionality to capture the current view hierarchy from a connected
 * Android device or emulator using the UIAutomator framework via ADB commands.</p>
 *
 * <p>The dumper executes the following steps:</p>
 * <ol>
 *   <li>Verifies ADB is available in the system PATH</li>
 *   <li>Checks for connected Android devices</li>
 *   <li>Executes UIAutomator dump command on the device</li>
 *   <li>Pulls the generated XML file from the device</li>
 *   <li>Optionally saves it to a local file</li>
 * </ol>
 *
 * <p>Example usage:</p>
 * <pre>
 * Result&lt;ViewHierarchy&gt; result = ViewHierarchyDumper.builder()
 *     .deviceSerial("emulator-5554")
 *     .outputFile(new File("hierarchy.xml"))
 *     .compressed(false)
 *     .build()
 *     .dump();
 *
 * if (result instanceof Success&lt;ViewHierarchy&gt; success) {
 *     ViewHierarchy hierarchy = success.value();
 *     System.out.println(hierarchy.getXmlContent());
 * }
 * </pre>
 */
@Builder
public class ViewHierarchyDumper {

    /**
     * Optional device serial number. If not specified, uses the first available device.
     */
    private final String deviceSerial;

    /**
     * Optional output file to save the hierarchy XML. If not specified, returns content in memory.
     */
    private final File outputFile;

    /**
     * Whether to use compressed hierarchy format (default: false).
     * Compressed format is faster but contains less information.
     */
    @Builder.Default
    private final boolean compressed = false;

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
     * Dumps the current view hierarchy from the connected Android device.
     *
     * @return Result containing ViewHierarchy on success, or Failure with error description
     */
    public Result<ViewHierarchy> dump() {
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

            // Execute UIAutomator dump on device
            String devicePath = "/sdcard/window_dump.xml";
            Result<Void> dumpResult = executeDump(adbPrefix, devicePath);
            if (dumpResult instanceof Failure<Void> failure) {
                return new Failure<>(null, failure.description());
            }

            // Pull the file from device
            Result<String> pullResult = pullHierarchyFile(adbPrefix, devicePath);
            if (pullResult instanceof Failure<String> failure) {
                return new Failure<>(null, failure.description());
            }

            String xmlContent = ((Success<String>) pullResult).value();

            // Clean up device file
            cleanupDeviceFile(adbPrefix, devicePath);

            // Save to output file if specified
            if (outputFile != null) {
                try {
                    Files.writeString(outputFile.toPath(), xmlContent);
                } catch (IOException e) {
                    return new Failure<>(null, "Failed to write output file: " + e.getMessage());
                }
            }

            ViewHierarchy hierarchy = ViewHierarchy.builder()
                    .xmlContent(xmlContent)
                    .deviceSerial(deviceSerial)
                    .outputFile(outputFile)
                    .build();

            return new Success<>(hierarchy, "View hierarchy dumped successfully");

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
     * Executes the UIAutomator dump command on the device.
     */
    private Result<Void> executeDump(List<String> adbPrefix, String devicePath) {
        try {
            List<String> command = new ArrayList<>(adbPrefix);
            command.add("shell");
            command.add("uiautomator");
            command.add("dump");
            if (compressed) {
                command.add("--compressed");
            }
            command.add(devicePath);

            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return new Failure<>(null, "UIAutomator dump timed out");
            }

            if (process.exitValue() != 0) {
                String error = readProcessError(process);
                return new Failure<>(null, "UIAutomator dump failed: " + error);
            }

            String output = readProcessOutput(process);
            if (!output.contains("UI hierchary dumped") && !output.contains("hierarchy dumped")) {
                // UIAutomator sometimes has typos in its output
                return new Failure<>(null, "UIAutomator dump may have failed. Output: " + output);
            }

            return new Success<>(null, "Dump executed successfully");

        } catch (IOException | InterruptedException e) {
            return new Failure<>(null, "Failed to execute dump: " + e.getMessage());
        }
    }

    /**
     * Pulls the hierarchy XML file from the device.
     */
    private Result<String> pullHierarchyFile(List<String> adbPrefix, String devicePath) {
        try {
            // Create temporary file to pull to
            Path tempFile = Files.createTempFile("uiautomator_", ".xml");

            List<String> command = new ArrayList<>(adbPrefix);
            command.add("pull");
            command.add(devicePath);
            command.add(tempFile.toString());

            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                Files.deleteIfExists(tempFile);
                return new Failure<>(null, "File pull timed out");
            }

            if (process.exitValue() != 0) {
                String error = readProcessError(process);
                Files.deleteIfExists(tempFile);
                return new Failure<>(null, "Failed to pull hierarchy file: " + error);
            }

            // Read the content
            String content = Files.readString(tempFile);

            // Clean up temp file
            Files.deleteIfExists(tempFile);

            return new Success<>(content, "Hierarchy file pulled successfully");

        } catch (IOException | InterruptedException e) {
            return new Failure<>(null, "Failed to pull hierarchy file: " + e.getMessage());
        }
    }

    /**
     * Cleans up the temporary file on the device.
     */
    private void cleanupDeviceFile(List<String> adbPrefix, String devicePath) {
        try {
            List<String> command = new ArrayList<>(adbPrefix);
            command.add("shell");
            command.add("rm");
            command.add(devicePath);

            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Ignore cleanup errors
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
