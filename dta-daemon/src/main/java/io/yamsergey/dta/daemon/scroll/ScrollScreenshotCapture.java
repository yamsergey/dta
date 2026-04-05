package io.yamsergey.dta.daemon.scroll;

import io.yamsergey.dta.tools.android.inspect.ViewHierarchy;
import io.yamsergey.dta.tools.android.inspect.ViewHierarchyDumper;
import io.yamsergey.dta.tools.android.inspect.ViewHierarchyParser;
import io.yamsergey.dta.tools.android.inspect.ViewNode;
import io.yamsergey.dta.tools.sugar.Failure;
import io.yamsergey.dta.tools.sugar.Result;
import io.yamsergey.dta.tools.sugar.Success;
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
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Captures scrolling screenshots from an Android device.
 *
 * <p>This class orchestrates the entire scrolling screenshot capture process:</p>
 * <ol>
 *   <li>Dumps the view hierarchy to find scrollable views</li>
 *   <li>Optionally scrolls to the top of the content</li>
 *   <li>Takes multiple screenshots while scrolling through content</li>
 *   <li>Detects overlap between consecutive screenshots using row hashing</li>
 *   <li>Stitches unique content into a single long image</li>
 * </ol>
 *
 * <p>Example usage:</p>
 * <pre>
 * Result&lt;ScrollScreenshot&gt; result = ScrollScreenshotCapture.builder()
 *     .deviceSerial("emulator-5554")
 *     .outputFile(new File("long-screenshot.png"))
 *     .scrollToTop(true)
 *     .build()
 *     .capture();
 *
 * if (result instanceof Success&lt;ScrollScreenshot&gt; success) {
 *     ScrollScreenshot screenshot = success.value();
 *     System.out.println("Captured " + screenshot.getCaptureCount() + " screenshots");
 *     System.out.println("Final size: " + screenshot.getWidth() + "x" + screenshot.getHeight());
 * }
 * </pre>
 */
@Builder
public class ScrollScreenshotCapture {

    // Device settings
    private final String deviceSerial;
    private final String adbPath;

    @Builder.Default
    private final int timeoutSeconds = 30;

    // Output settings
    private final File outputFile;

    // Scroll behavior
    @Builder.Default
    private final int scrollDelayMs = 500;

    @Builder.Default
    private final int maxCaptures = 30;

    @Builder.Default
    private final double swipeRatio = 0.5;

    @Builder.Default
    private final int swipeDurationMs = 300;

    @Builder.Default
    private final boolean scrollToTop = false;

    @Builder.Default
    private final int scrollToTopSwipes = 10;

    // View targeting
    private final String targetViewId;

    // Debug mode - saves individual captures
    @Builder.Default
    private final boolean debugMode = false;

    // Internal components
    private final RowHashCalculator rowHashCalculator = new RowHashCalculator();
    private final ImageOverlapDetector overlapDetector = new ImageOverlapDetector();
    private final ScrollableViewFinder viewFinder = new ScrollableViewFinder();
    private final ImageStitcher stitcher = new ImageStitcher();

    /**
     * Captures a scrolling screenshot from the connected Android device.
     *
     * @return Result containing ScrollScreenshot on success, or Failure with error description
     */
    public Result<ScrollScreenshot> capture() {
        try {
            // Validate output file
            if (outputFile == null) {
                return new Failure<>(null, "Output file is required");
            }

            String adbCommand = adbPath != null ? adbPath : "adb";

            // Verify ADB is available
            Result<Void> adbCheck = verifyAdb(adbCommand);
            if (adbCheck instanceof Failure<Void> failure) {
                return new Failure<>(null, failure.description());
            }

            // Build ADB prefix
            List<String> adbPrefix = buildAdbPrefix(adbCommand);

            // Verify device is connected
            Result<String> deviceCheck = verifyDevice(adbPrefix);
            if (deviceCheck instanceof Failure<String> failure) {
                return new Failure<>(null, failure.description());
            }

            // Dump view hierarchy
            Result<ViewHierarchy> hierarchyResult = dumpViewHierarchy();
            if (hierarchyResult instanceof Failure<ViewHierarchy> failure) {
                return new Failure<>(null, "Failed to dump view hierarchy: " + failure.description());
            }

            ViewHierarchy hierarchy = ((Success<ViewHierarchy>) hierarchyResult).value();

            // Parse hierarchy
            Result<ViewNode> parseResult = ViewHierarchyParser.parse(hierarchy.getXmlContent());
            if (parseResult instanceof Failure<ViewNode> failure) {
                return new Failure<>(null, "Failed to parse view hierarchy: " + failure.description());
            }

            ViewNode rootNode = ((Success<ViewNode>) parseResult).value();

            // Find scrollable view
            Optional<ViewNode> scrollableViewOpt = findScrollableView(rootNode);
            if (scrollableViewOpt.isEmpty()) {
                List<String> availableIds = viewFinder.getScrollableResourceIds(rootNode);
                String hint = availableIds.isEmpty()
                    ? "No scrollable views found in the current screen."
                    : "Available scrollable views: " + String.join(", ", availableIds);
                return new Failure<>(null, "Could not find scrollable view. " + hint);
            }

            ViewNode scrollableView = scrollableViewOpt.get();
            ViewNode.Bounds originalBounds = scrollableView.getBounds();
            // Apply small bottom margin to exclude content partially hidden behind overlays
            // Keep margin small (20px) to avoid cutting off actual content
            ViewNode.Bounds bounds = originalBounds.withBottomMargin(20);

            // Create swipe controller
            AdbSwipeController swipeController = AdbSwipeController.builder()
                .deviceSerial(deviceSerial)
                .adbPath(adbPath)
                .timeoutSeconds(timeoutSeconds)
                .build();

            // Scroll to top if requested
            if (scrollToTop) {
                Result<Void> scrollResult = swipeController.scrollToTopSimple(bounds, scrollToTopSwipes);
                if (scrollResult instanceof Failure<Void> failure) {
                    return new Failure<>(null, "Failed to scroll to top: " + failure.description());
                }
                // Wait for content to settle
                Thread.sleep(scrollDelayMs);
            }

            // Capture loop
            stitcher.clear();
            long[] prevHashes = null;
            int captureCount = 0;
            boolean reachedEnd = false;
            BufferedImage lastScreenshot = null;
            int lastOverlapRows = 0;

            while (captureCount < maxCaptures) {
                // Capture screenshot
                Result<BufferedImage> captureResult = captureScreenshot(adbPrefix);
                if (captureResult instanceof Failure<BufferedImage> failure) {
                    if (captureCount == 0) {
                        return new Failure<>(null, "Failed to capture screenshot: " + failure.description());
                    }
                    // If we have some captures, continue with what we have
                    break;
                }

                BufferedImage screenshot = ((Success<BufferedImage>) captureResult).value();
                captureCount++;

                // Debug: save individual captures
                if (debugMode && outputFile != null) {
                    String debugPath = outputFile.getAbsolutePath().replace(".png", "-capture" + captureCount + ".png");
                    try {
                        ImageIO.write(screenshot, "PNG", new File(debugPath));
                    } catch (Exception e) {
                        // Ignore debug save errors
                    }
                }

                // On first screenshot, capture the static UI regions (top bar, bottom nav)
                if (captureCount == 1) {
                    // Top region: from top of screen to top of scrollable view
                    stitcher.setTopRegion(screenshot, 0, bounds.getTop());
                    // Bottom region: from bottom of ORIGINAL scrollable view to bottom of screen
                    // Use originalBounds to capture only the actual static UI (input bar, nav bar)
                    stitcher.setBottomRegion(screenshot, originalBounds.getBottom(), screenshot.getHeight());
                }

                // Compute row hashes for the scrollable region
                long[] currHashes = rowHashCalculator.computeRowHashes(screenshot, bounds);

                if (prevHashes != null) {
                    // Check for scroll end
                    if (overlapDetector.isScrollEnd(prevHashes, currHashes)) {
                        reachedEnd = true;
                        // Don't add this segment - it's essentially identical to the previous one
                        // The previous segment already has all the content
                        break;
                    }

                    // Find overlap
                    Optional<ImageOverlapDetector.OverlapResult> overlapOpt =
                        overlapDetector.findOverlap(prevHashes, currHashes);

                    if (overlapOpt.isPresent()) {
                        // Add unique portion from current screenshot
                        int overlapRows = overlapOpt.get().overlapRows();
                        int uniqueRows = currHashes.length - overlapRows;
                        if (debugMode) {
                            System.err.printf("Capture %d: overlap=%d rows (%.1f%% match), unique=%d rows%n",
                                captureCount, overlapRows, overlapOpt.get().matchRatio() * 100,
                                uniqueRows);
                        }
                        // Only add if there are meaningful unique rows
                        if (uniqueRows > 10) {
                            stitcher.addSegmentWithOverlap(screenshot, bounds, overlapRows);
                            // Track for potential last segment adjustment
                            lastScreenshot = screenshot;
                            lastOverlapRows = overlapRows;
                        } else {
                            // Almost no unique content - treat as scroll end
                            if (debugMode) {
                                System.err.printf("Capture %d: only %d unique rows, treating as scroll end%n",
                                    captureCount, uniqueRows);
                            }
                            reachedEnd = true;
                            break;
                        }
                    } else {
                        // No overlap found - this shouldn't happen in normal scrolling
                        // The images are likely almost identical (scroll end) or completely different
                        // Check similarity to decide
                        int matchingRows = 0;
                        for (int i = 0; i < Math.min(prevHashes.length, currHashes.length); i++) {
                            if (prevHashes[i] == currHashes[i]) {
                                matchingRows++;
                            }
                        }
                        double similarityRatio = (double) matchingRows / Math.max(prevHashes.length, currHashes.length);

                        if (similarityRatio > 0.5) {
                            // Images are very similar - we've likely reached scroll end
                            // with minimal scroll movement
                            if (debugMode) {
                                System.err.printf("Capture %d: NO OVERLAP FOUND but %.1f%% similar, treating as scroll end%n",
                                    captureCount, similarityRatio * 100);
                            }
                            reachedEnd = true;
                            break;
                        } else {
                            // Truly different content - add full region (unlikely in normal use)
                            if (debugMode) {
                                System.err.printf("Capture %d: NO OVERLAP FOUND and only %.1f%% similar, adding full region%n",
                                    captureCount, similarityRatio * 100);
                            }
                            stitcher.addFirstSegment(screenshot, bounds);
                            lastScreenshot = screenshot;
                            lastOverlapRows = 0;
                        }
                    }
                } else {
                    // First screenshot - add entire scrollable region
                    if (debugMode) {
                        System.err.printf("Capture %d: first segment, bounds=[%d,%d,%d,%d] height=%d%n",
                            captureCount, bounds.getLeft(), bounds.getTop(),
                            bounds.getRight(), bounds.getBottom(), bounds.getHeight());
                    }
                    stitcher.addFirstSegment(screenshot, bounds);
                    lastScreenshot = screenshot;
                    lastOverlapRows = 0;
                }

                prevHashes = currHashes;

                // Check if we're at the last allowed capture
                if (captureCount >= maxCaptures) {
                    break;
                }

                // Swipe for next capture
                Result<Void> swipeResult = swipeController.swipeUp(bounds, swipeRatio, swipeDurationMs);
                if (swipeResult instanceof Failure<Void>) {
                    // Swipe failed, stop here
                    break;
                }

                // Wait for scroll animation to complete
                Thread.sleep(scrollDelayMs);
            }

            // Replace last segment with original bounds to capture full content at the bottom
            // This ensures we don't cut off content that was excluded by the bottom margin
            // Only do this if we actually reached the end (otherwise we might cut off in the middle)
            if (reachedEnd && lastScreenshot != null && captureCount > 1) {
                stitcher.replaceLastSegment(lastScreenshot, originalBounds, lastOverlapRows);
                if (debugMode) {
                    System.err.printf("Replaced last segment with original bounds (added %d extra rows)%n",
                        originalBounds.getHeight() - bounds.getHeight());
                }
            }

            // Stitch segments
            BufferedImage finalImage = stitcher.stitch();
            if (finalImage == null) {
                return new Failure<>(null, "Failed to stitch screenshots - no valid segments captured");
            }

            // Create output directory if needed
            File outputDir = outputFile.getParentFile();
            if (outputDir != null && !outputDir.exists()) {
                if (!outputDir.mkdirs()) {
                    return new Failure<>(null, "Failed to create output directory: " + outputDir.getAbsolutePath());
                }
            }

            // Save to file
            if (!ImageIO.write(finalImage, "PNG", outputFile)) {
                return new Failure<>(null, "Failed to write PNG file - no suitable writer found");
            }

            ScrollScreenshot result = ScrollScreenshot.builder()
                .file(outputFile)
                .deviceSerial(deviceSerial)
                .width(finalImage.getWidth())
                .height(finalImage.getHeight())
                .captureCount(captureCount)
                .scrollableViewId(scrollableView.getResourceId())
                .scrollableBounds(bounds)
                .reachedScrollEnd(reachedEnd)
                .build();

            String description = String.format(
                "Scrolling screenshot captured successfully. %d screenshots, %dx%d pixels%s",
                captureCount, finalImage.getWidth(), finalImage.getHeight(),
                reachedEnd ? " (reached end)" : " (max captures reached)");

            return new Success<>(result, description);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Failure<>(null, "Capture interrupted");
        } catch (Exception e) {
            return new Failure<>(null, "Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Finds the target scrollable view in the hierarchy.
     */
    private Optional<ViewNode> findScrollableView(ViewNode root) {
        if (targetViewId != null && !targetViewId.isEmpty()) {
            return viewFinder.findByResourceId(root, targetViewId);
        }
        return viewFinder.findDefaultScrollable(root);
    }

    /**
     * Dumps the current view hierarchy.
     */
    private Result<ViewHierarchy> dumpViewHierarchy() {
        return ViewHierarchyDumper.builder()
            .deviceSerial(deviceSerial)
            .adbPath(adbPath)
            .timeoutSeconds(timeoutSeconds)
            .compressed(false)
            .build()
            .dump();
    }

    /**
     * Captures a screenshot from the device.
     */
    private Result<BufferedImage> captureScreenshot(List<String> adbPrefix) {
        try {
            // Create temp file for screenshot
            Path tempFile = Files.createTempFile("scroll_screenshot_", ".png");

            // Execute screencap on device
            String devicePath = "/sdcard/scroll_temp.png";

            List<String> captureCmd = new ArrayList<>(adbPrefix);
            captureCmd.add("shell");
            captureCmd.add("screencap");
            captureCmd.add("-p");
            captureCmd.add(devicePath);

            ProcessBuilder pb = new ProcessBuilder(captureCmd);
            Process process = pb.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                Files.deleteIfExists(tempFile);
                return new Failure<>(null, "Screencap timed out");
            }

            if (process.exitValue() != 0) {
                String error = readProcessError(process);
                Files.deleteIfExists(tempFile);
                return new Failure<>(null, "Screencap failed: " + error);
            }

            // Pull the file
            List<String> pullCmd = new ArrayList<>(adbPrefix);
            pullCmd.add("pull");
            pullCmd.add(devicePath);
            pullCmd.add(tempFile.toString());

            pb = new ProcessBuilder(pullCmd);
            process = pb.start();
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                Files.deleteIfExists(tempFile);
                cleanupDeviceFile(adbPrefix, devicePath);
                return new Failure<>(null, "File pull timed out");
            }

            if (process.exitValue() != 0) {
                String error = readProcessError(process);
                Files.deleteIfExists(tempFile);
                cleanupDeviceFile(adbPrefix, devicePath);
                return new Failure<>(null, "Failed to pull screenshot: " + error);
            }

            // Clean up device file
            cleanupDeviceFile(adbPrefix, devicePath);

            // Read the image
            BufferedImage image = ImageIO.read(tempFile.toFile());
            Files.deleteIfExists(tempFile);

            if (image == null) {
                return new Failure<>(null, "Failed to read screenshot image");
            }

            return new Success<>(image, "Screenshot captured");

        } catch (IOException | InterruptedException e) {
            return new Failure<>(null, "Failed to capture screenshot: " + e.getMessage());
        }
    }

    /**
     * Builds the ADB command prefix with optional device serial.
     */
    private List<String> buildAdbPrefix(String adbCommand) {
        List<String> prefix = new ArrayList<>();
        prefix.add(adbCommand);
        if (deviceSerial != null && !deviceSerial.isEmpty()) {
            prefix.add("-s");
            prefix.add(deviceSerial);
        }
        return prefix;
    }

    /**
     * Verifies that ADB is available.
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
     * Verifies that a device is connected.
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
                    return new Failure<>(null, "Device '" + deviceSerial + "' not found: " + error);
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
     * Cleans up temporary file on the device.
     */
    private void cleanupDeviceFile(List<String> adbPrefix, String devicePath) {
        try {
            List<String> command = new ArrayList<>(adbPrefix);
            command.add("shell");
            command.add("rm");
            command.add("-f");
            command.add(devicePath);

            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    /**
     * Reads standard output from a process.
     */
    private String readProcessOutput(Process process) throws IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        return output.toString();
    }

    /**
     * Reads error output from a process.
     */
    private String readProcessError(Process process) throws IOException {
        StringBuilder error = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                error.append(line).append("\n");
            }
        }
        return error.toString().trim();
    }
}
