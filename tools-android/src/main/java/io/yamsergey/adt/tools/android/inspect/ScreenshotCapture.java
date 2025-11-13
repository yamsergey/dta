package io.yamsergey.adt.tools.android.inspect;

import io.yamsergey.adt.tools.sugar.Failure;
import io.yamsergey.adt.tools.sugar.Result;
import io.yamsergey.adt.tools.sugar.Success;
import lombok.Builder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Captures screenshots from an Android device using ADB.
 *
 * <p>This class provides functionality to capture screenshots from a connected
 * Android device or emulator using ADB screencap command.</p>
 *
 * <p>The capture process:</p>
 * <ol>
 *   <li>Verifies ADB is available in the system PATH</li>
 *   <li>Checks for connected Android devices</li>
 *   <li>Executes screencap command on the device</li>
 *   <li>Pulls the screenshot file from the device</li>
 *   <li>Saves it to the specified location</li>
 * </ol>
 *
 * <p>Example usage:</p>
 * <pre>
 * Result&lt;Screenshot&gt; result = ScreenshotCapture.builder()
 *     .deviceSerial("emulator-5554")
 *     .outputFile(new File("screenshot.png"))
 *     .build()
 *     .capture();
 *
 * if (result instanceof Success&lt;Screenshot&gt; success) {
 *     Screenshot screenshot = success.value();
 *     System.out.println("Screenshot saved: " + screenshot.getFile());
 * }
 * </pre>
 */
@Builder
public class ScreenshotCapture {

    /**
     * Optional device serial number. If not specified, uses the first available device.
     */
    private final String deviceSerial;

    /**
     * Output file to save the screenshot. Required.
     */
    private final File outputFile;

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
     * Captures a screenshot from the connected Android device.
     *
     * @return Result containing Screenshot on success, or Failure with error description
     */
    public Result<Screenshot> capture() {
        try {
            // Validate output file
            if (outputFile == null) {
                return new Failure<>(null, "Output file is required");
            }

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

            // Execute screencap on device
            String devicePath = "/sdcard/screenshot.png";
            Result<Void> captureResult = executeScreencap(adbPrefix, devicePath);
            if (captureResult instanceof Failure<Void> failure) {
                return new Failure<>(null, failure.description());
            }

            // Pull the file from device
            Result<File> pullResult = pullScreenshot(adbPrefix, devicePath);
            if (pullResult instanceof Failure<File> failure) {
                return new Failure<>(null, failure.description());
            }

            File tempFile = ((Success<File>) pullResult).value();

            // Clean up device file
            cleanupDeviceFile(adbPrefix, devicePath);

            // Get image dimensions
            Integer width = null;
            Integer height = null;
            try {
                BufferedImage image = ImageIO.read(tempFile);
                if (image != null) {
                    width = image.getWidth();
                    height = image.getHeight();
                }
            } catch (Exception e) {
                // Dimensions are optional, continue without them
            }

            // Move to final location
            Files.move(tempFile.toPath(), outputFile.toPath(), 
                      java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            Screenshot screenshot = Screenshot.builder()
                    .file(outputFile)
                    .deviceSerial(deviceSerial)
                    .width(width)
                    .height(height)
                    .build();

            return new Success<>(screenshot, "Screenshot captured successfully");

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
     * Executes the screencap command on the device.
     */
    private Result<Void> executeScreencap(List<String> adbPrefix, String devicePath) {
        try {
            List<String> command = new ArrayList<>(adbPrefix);
            command.add("shell");
            command.add("screencap");
            command.add("-p");
            command.add(devicePath);

            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return new Failure<>(null, "Screencap timed out");
            }

            if (process.exitValue() != 0) {
                String error = readProcessError(process);
                return new Failure<>(null, "Screencap failed: " + error);
            }

            return new Success<>(null, "Screenshot captured on device");

        } catch (IOException | InterruptedException e) {
            return new Failure<>(null, "Failed to execute screencap: " + e.getMessage());
        }
    }

    /**
     * Pulls the screenshot file from the device.
     */
    private Result<File> pullScreenshot(List<String> adbPrefix, String devicePath) {
        try {
            // Create temporary file to pull to
            Path tempFile = Files.createTempFile("screenshot_", ".png");

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
                return new Failure<>(null, "Failed to pull screenshot: " + error);
            }

            return new Success<>(tempFile.toFile(), "Screenshot pulled successfully");

        } catch (IOException | InterruptedException e) {
            return new Failure<>(null, "Failed to pull screenshot: " + e.getMessage());
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
