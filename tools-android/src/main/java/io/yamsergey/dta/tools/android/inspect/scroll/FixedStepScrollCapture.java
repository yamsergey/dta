package io.yamsergey.dta.tools.android.inspect.scroll;

import io.yamsergey.dta.tools.android.inspect.ViewHierarchy;
import io.yamsergey.dta.tools.android.inspect.ViewHierarchyDumper;
import io.yamsergey.dta.tools.android.inspect.ViewHierarchyParser;
import io.yamsergey.dta.tools.android.inspect.ViewNode;
import io.yamsergey.dta.tools.sugar.Failure;
import io.yamsergey.dta.tools.sugar.Result;
import io.yamsergey.dta.tools.sugar.Success;
import lombok.Builder;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
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
 * Captures scrolling screenshots using hash-based overlap detection and overwrite stitching.
 *
 * <p>Algorithm overview:</p>
 * <ol>
 *   <li>Scroll by 50% of scrollable area height</li>
 *   <li>Use FNV-1a row hashing to detect actual overlap between screenshots</li>
 *   <li>Use "overwrite" stitching - draw the new screenshot over the overlap region
 *       so both sides of any seam come from the same image (eliminates visible seams)</li>
 *   <li>Detect scroll end when 95% of row hashes match between consecutive captures</li>
 * </ol>
 *
 * <p>Capture strategy:</p>
 * <ul>
 *   <li>First screenshot: capture from top to bottom of scrollable area</li>
 *   <li>Subsequent screenshots: detect overlap via hashing, overwrite overlap region</li>
 *   <li>Last screenshot: extend to screen bottom to include static bottom navigation</li>
 * </ul>
 *
 * <p>Example usage:</p>
 * <pre>
 * Result&lt;ScrollScreenshot&gt; result = FixedStepScrollCapture.builder()
 *     .outputFile(new File("long-screenshot.png"))
 *     .scrollToTop(true)
 *     .build()
 *     .capture();
 * </pre>
 */
@Builder
public class FixedStepScrollCapture {

    // Device settings
    private final String deviceSerial;
    private final String adbPath;

    @Builder.Default
    private final int timeoutSeconds = 30;

    // Output settings
    private final File outputFile;

    // Scroll behavior
    @Builder.Default
    private final int scrollDelayMs = 600;

    @Builder.Default
    private final int maxCaptures = 30;

    @Builder.Default
    private final boolean scrollToTop = false;

    @Builder.Default
    private final int scrollToTopSwipes = 10;

    /**
     * Scroll step in pixels. Only used if set explicitly.
     * By default, scrolls 50% of scrollable area height for optimal stitching.
     */
    private final Integer scrollStep;

    /**
     * Swipe duration in milliseconds. Longer = slower, more predictable scroll.
     */
    @Builder.Default
    private final int swipeDurationMs = 500;

    // View targeting
    private final String targetViewId;

    // Debug mode
    @Builder.Default
    private final boolean debugMode = false;

    // Internal components
    private final ScrollableViewFinder viewFinder = new ScrollableViewFinder();
    private final RowHashCalculator hashCalculator = new RowHashCalculator();

    /**
     * Captures a scrolling screenshot using the fixed-geometry algorithm.
     *
     * @return Result containing ScrollScreenshot on success, or Failure with error description
     */
    public Result<ScrollScreenshot> capture() {
        try {
            if (outputFile == null) {
                return new Failure<>(null, "Output file is required");
            }

            String adbCommand = adbPath != null ? adbPath : "adb";

            // Verify ADB and device
            Result<Void> adbCheck = verifyAdb(adbCommand);
            if (adbCheck instanceof Failure<Void> failure) {
                return new Failure<>(null, failure.description());
            }

            List<String> adbPrefix = buildAdbPrefix(adbCommand);

            Result<String> deviceCheck = verifyDevice(adbPrefix);
            if (deviceCheck instanceof Failure<String> failure) {
                return new Failure<>(null, failure.description());
            }

            // Dump view hierarchy to find scrollable view
            Result<ViewHierarchy> hierarchyResult = dumpViewHierarchy();
            if (hierarchyResult instanceof Failure<ViewHierarchy> failure) {
                return new Failure<>(null, "Failed to dump view hierarchy: " + failure.description());
            }

            ViewHierarchy hierarchy = ((Success<ViewHierarchy>) hierarchyResult).value();

            Result<ViewNode> parseResult = ViewHierarchyParser.parse(hierarchy.getXmlContent());
            if (parseResult instanceof Failure<ViewNode> failure) {
                return new Failure<>(null, "Failed to parse view hierarchy: " + failure.description());
            }

            ViewNode rootNode = ((Success<ViewNode>) parseResult).value();

            Optional<ViewNode> scrollableViewOpt = findScrollableView(rootNode);
            if (scrollableViewOpt.isEmpty()) {
                return new Failure<>(null, "Could not find scrollable view");
            }

            ViewNode scrollableView = scrollableViewOpt.get();
            ViewNode.Bounds scrollBounds = scrollableView.getBounds();
            int scrollableHeight = scrollBounds.getHeight();

            // Calculate scroll parameters
            // Scroll 50% of scrollable height, but skip only 40% to leave a 10% safety buffer
            // This accounts for scroll imprecision in Android's input swipe
            int actualScrollStep = (scrollStep != null) ? scrollStep : scrollableHeight / 2;
            // We'll use hash detection to find actual overlap, this is just the minimum expected
            int minExpectedOverlap = (int)(scrollableHeight * 0.4);

            if (debugMode) {
                System.err.printf("Scrollable bounds: [%d,%d,%d,%d] size=%dx%d%n",
                    scrollBounds.getLeft(), scrollBounds.getTop(),
                    scrollBounds.getRight(), scrollBounds.getBottom(),
                    scrollBounds.getWidth(), scrollableHeight);
                System.err.printf("Scroll step: %d pixels (50%% of height)%n", actualScrollStep);
                System.err.printf("Min expected overlap: %d pixels (40%%)%n", minExpectedOverlap);
            }

            // For detecting actual overlap
            ImageOverlapDetector overlapDetector = new ImageOverlapDetector();

            // Create swipe controller
            AdbSwipeController swipeController = AdbSwipeController.builder()
                .deviceSerial(deviceSerial)
                .adbPath(adbPath)
                .timeoutSeconds(timeoutSeconds)
                .build();

            // Scroll to top if requested
            if (scrollToTop) {
                Result<Void> scrollResult = swipeController.scrollToTopSimple(scrollBounds, scrollToTopSwipes);
                if (scrollResult instanceof Failure<Void> failure) {
                    return new Failure<>(null, "Failed to scroll to top: " + failure.description());
                }
                Thread.sleep(scrollDelayMs);
            }

            // ============== CAPTURE FIRST SCREENSHOT ==============
            Result<BufferedImage> firstCapture = captureScreenshot(adbPrefix);
            if (firstCapture instanceof Failure<BufferedImage> failure) {
                return new Failure<>(null, "Failed to capture first screenshot: " + failure.description());
            }

            BufferedImage firstScreen = ((Success<BufferedImage>) firstCapture).value();
            int captureCount = 1;

            if (debugMode) {
                saveDebugImage(firstScreen, captureCount);
            }

            // Compute hashes for scroll-end detection
            long[] prevHashes = hashCalculator.computeRowHashes(firstScreen, scrollBounds);

            // Build the final image using Graphics2D
            // Estimate max height (will resize if needed)
            int estimatedHeight = firstScreen.getHeight() * maxCaptures;
            int imageWidth = firstScreen.getWidth();
            BufferedImage resultImage = new BufferedImage(imageWidth, estimatedHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = resultImage.createGraphics();

            // First screenshot: draw from top to bottom of scrollable area (excludes bottom nav)
            // We'll add the bottom nav from the last screenshot
            int firstSegmentHeight = scrollBounds.getBottom();
            g2d.drawImage(firstScreen,
                0, 0, imageWidth, firstSegmentHeight,      // destination
                0, 0, imageWidth, firstSegmentHeight,      // source
                null);
            int currentY = firstSegmentHeight;

            if (debugMode) {
                System.err.printf("Capture 1: top to scrollable bottom, height=%d%n", firstSegmentHeight);
            }

            boolean reachedEnd = false;
            BufferedImage lastScreen = firstScreen;

            // ============== SUBSEQUENT SCREENSHOTS ==============
            while (captureCount < maxCaptures) {
                // Scroll by 50% of scrollable height
                Result<Void> swipeResult = performScroll(scrollBounds, actualScrollStep);
                if (swipeResult instanceof Failure<Void>) {
                    break;
                }

                Thread.sleep(scrollDelayMs);

                // Capture next screenshot
                Result<BufferedImage> nextCapture = captureScreenshot(adbPrefix);
                if (nextCapture instanceof Failure<BufferedImage> failure) {
                    break;
                }

                BufferedImage screen = ((Success<BufferedImage>) nextCapture).value();
                captureCount++;

                if (debugMode) {
                    saveDebugImage(screen, captureCount);
                }

                // Check for scroll end using hashes
                long[] currHashes = hashCalculator.computeRowHashes(screen, scrollBounds);
                if (isScrollEnd(prevHashes, currHashes)) {
                    reachedEnd = true;
                    lastScreen = screen;
                    if (debugMode) {
                        System.err.printf("Capture %d: scroll end detected%n", captureCount);
                    }
                    break;
                }

                // Find actual overlap using hash comparison
                Optional<ImageOverlapDetector.OverlapResult> overlapOpt =
                    overlapDetector.findOverlap(prevHashes, currHashes);

                int actualOverlap;
                if (overlapOpt.isPresent()) {
                    actualOverlap = overlapOpt.get().overlapRows();
                    if (debugMode) {
                        System.err.printf("Capture %d: detected overlap=%d rows%n", captureCount, actualOverlap);
                    }
                } else {
                    // Fallback to minimum expected overlap
                    actualOverlap = minExpectedOverlap;
                    if (debugMode) {
                        System.err.printf("Capture %d: no overlap detected, using fallback=%d%n", captureCount, actualOverlap);
                    }
                }

                // OVERWRITE approach: draw the new screenshot's scrollable area over the overlap
                // This ensures seamless stitching because both sides of the "seam" come from the same image
                int scrollTop = scrollBounds.getTop();
                int scrollBottom = scrollBounds.getBottom();
                int scrollHeight = scrollBottom - scrollTop;

                // Calculate where to start drawing in destination
                // The overlap region in destination starts at: currentY - actualOverlap
                // We'll draw the entire scrollable area from the new screenshot, overwriting the overlap
                int destStartY = currentY - actualOverlap;

                // Draw the entire scrollable region from the new screenshot
                g2d.drawImage(screen,
                    0, destStartY, imageWidth, destStartY + scrollHeight,
                    0, scrollTop, imageWidth, scrollBottom,
                    null);

                // The new currentY is where we finished drawing
                int newContentAdded = scrollHeight - actualOverlap;
                currentY = destStartY + scrollHeight;

                if (debugMode) {
                    System.err.printf("Capture %d: overlap=%d, overwrote from %d, added=%d, total=%d%n",
                        captureCount, actualOverlap, destStartY, newContentAdded, currentY);
                }

                prevHashes = currHashes;
                lastScreen = screen;
            }

            // ============== EXTEND LAST SCREENSHOT TO SCREEN BOTTOM ==============
            // Include any static bottom navigation/bar
            if (captureCount > 1) {
                int bottomExtra = lastScreen.getHeight() - scrollBounds.getBottom();
                if (bottomExtra > 0) {
                    g2d.drawImage(lastScreen,
                        0, currentY, imageWidth, currentY + bottomExtra,                // destination
                        0, scrollBounds.getBottom(), imageWidth, lastScreen.getHeight(), // source
                        null);
                    currentY += bottomExtra;

                    if (debugMode) {
                        System.err.printf("Extended with bottom nav: +%d pixels%n", bottomExtra);
                    }
                }
            }

            g2d.dispose();

            // Crop to actual height
            int finalHeight = currentY;
            BufferedImage finalImage = resultImage.getSubimage(0, 0, imageWidth, finalHeight);

            if (debugMode) {
                System.err.printf("Final image: %dx%d from %d captures%n",
                    imageWidth, finalHeight, captureCount);
            }

            // Save result
            File outputDir = outputFile.getParentFile();
            if (outputDir != null && !outputDir.exists()) {
                if (!outputDir.mkdirs()) {
                    return new Failure<>(null, "Failed to create output directory");
                }
            }

            // Write to a temp file first, then move (subimage can't be written directly)
            BufferedImage outputImage = new BufferedImage(imageWidth, finalHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D outG2d = outputImage.createGraphics();
            outG2d.drawImage(finalImage, 0, 0, null);
            outG2d.dispose();

            if (!ImageIO.write(outputImage, "PNG", outputFile)) {
                return new Failure<>(null, "Failed to write PNG file");
            }

            ScrollScreenshot screenshot = ScrollScreenshot.builder()
                .file(outputFile)
                .deviceSerial(deviceSerial)
                .width(imageWidth)
                .height(finalHeight)
                .captureCount(captureCount)
                .scrollableViewId(scrollableView.getResourceId())
                .scrollableBounds(scrollBounds)
                .reachedScrollEnd(reachedEnd)
                .build();

            return new Success<>(screenshot,
                String.format("Captured %d screenshots, final size %dx%d%s",
                    captureCount, imageWidth, finalHeight,
                    reachedEnd ? " (reached end)" : ""));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Failure<>(null, "Capture interrupted");
        } catch (Exception e) {
            return new Failure<>(null, "Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Performs a scroll by the specified pixel amount.
     */
    private Result<Void> performScroll(ViewNode.Bounds bounds, int scrollAmount) {
        int centerX = bounds.getCenterX();
        int margin = (int) (bounds.getHeight() * 0.1);  // 10% margin from edges

        int startY = bounds.getBottom() - margin;
        int endY = startY - scrollAmount;

        // Clamp to bounds
        endY = Math.max(bounds.getTop() + margin, endY);

        return executeSwipe(centerX, startY, centerX, endY, swipeDurationMs);
    }

    /**
     * Executes an ADB swipe command.
     */
    private Result<Void> executeSwipe(int x1, int y1, int x2, int y2, int durationMs) {
        try {
            String adbCommand = adbPath != null ? adbPath : "adb";
            List<String> command = new ArrayList<>();
            command.add(adbCommand);

            if (deviceSerial != null && !deviceSerial.isEmpty()) {
                command.add("-s");
                command.add(deviceSerial);
            }

            command.add("shell");
            command.add("input");
            command.add("swipe");
            command.add(String.valueOf(x1));
            command.add(String.valueOf(y1));
            command.add(String.valueOf(x2));
            command.add(String.valueOf(y2));
            command.add(String.valueOf(durationMs));

            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return new Failure<>(null, "Swipe timed out");
            }

            if (process.exitValue() != 0) {
                return new Failure<>(null, "Swipe failed");
            }

            return new Success<>(null, "Swipe completed");

        } catch (IOException | InterruptedException e) {
            return new Failure<>(null, "Swipe error: " + e.getMessage());
        }
    }

    /**
     * Checks if scroll has ended by comparing hash arrays.
     */
    private boolean isScrollEnd(long[] prevHashes, long[] currHashes) {
        if (prevHashes == null || currHashes == null ||
            prevHashes.length != currHashes.length) {
            return false;
        }

        int matches = 0;
        for (int i = 0; i < prevHashes.length; i++) {
            if (prevHashes[i] == currHashes[i]) {
                matches++;
            }
        }

        double matchRatio = (double) matches / prevHashes.length;
        return matchRatio >= 0.95;  // 95% match = scroll ended
    }

    private Optional<ViewNode> findScrollableView(ViewNode root) {
        if (targetViewId != null && !targetViewId.isEmpty()) {
            return viewFinder.findByResourceId(root, targetViewId);
        }
        return viewFinder.findDefaultScrollable(root);
    }

    private Result<ViewHierarchy> dumpViewHierarchy() {
        return ViewHierarchyDumper.builder()
            .deviceSerial(deviceSerial)
            .adbPath(adbPath)
            .timeoutSeconds(timeoutSeconds)
            .compressed(false)
            .build()
            .dump();
    }

    private Result<BufferedImage> captureScreenshot(List<String> adbPrefix) {
        try {
            Path tempFile = Files.createTempFile("fixed_scroll_", ".png");
            String devicePath = "/sdcard/fixed_scroll_temp.png";

            List<String> captureCmd = new ArrayList<>(adbPrefix);
            captureCmd.add("shell");
            captureCmd.add("screencap");
            captureCmd.add("-p");
            captureCmd.add(devicePath);

            ProcessBuilder pb = new ProcessBuilder(captureCmd);
            Process process = pb.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished || process.exitValue() != 0) {
                Files.deleteIfExists(tempFile);
                return new Failure<>(null, "Screencap failed");
            }

            List<String> pullCmd = new ArrayList<>(adbPrefix);
            pullCmd.add("pull");
            pullCmd.add(devicePath);
            pullCmd.add(tempFile.toString());

            pb = new ProcessBuilder(pullCmd);
            process = pb.start();
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished || process.exitValue() != 0) {
                Files.deleteIfExists(tempFile);
                return new Failure<>(null, "Pull failed");
            }

            // Cleanup device file
            cleanupDeviceFile(adbPrefix, devicePath);

            BufferedImage image = ImageIO.read(tempFile.toFile());
            Files.deleteIfExists(tempFile);

            if (image == null) {
                return new Failure<>(null, "Failed to read image");
            }

            return new Success<>(image, "Screenshot captured");

        } catch (IOException | InterruptedException e) {
            return new Failure<>(null, "Screenshot error: " + e.getMessage());
        }
    }

    private void cleanupDeviceFile(List<String> adbPrefix, String devicePath) {
        try {
            List<String> command = new ArrayList<>(adbPrefix);
            command.add("shell");
            command.add("rm");
            command.add("-f");
            command.add(devicePath);
            new ProcessBuilder(command).start().waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Ignore
        }
    }

    private void saveDebugImage(BufferedImage image, int index) {
        if (outputFile != null) {
            String debugPath = outputFile.getAbsolutePath()
                .replace(".png", "-capture" + index + ".png");
            try {
                ImageIO.write(image, "PNG", new File(debugPath));
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    private List<String> buildAdbPrefix(String adbCommand) {
        List<String> prefix = new ArrayList<>();
        prefix.add(adbCommand);
        if (deviceSerial != null && !deviceSerial.isEmpty()) {
            prefix.add("-s");
            prefix.add(deviceSerial);
        }
        return prefix;
    }

    private Result<Void> verifyAdb(String adbCommand) {
        try {
            ProcessBuilder pb = new ProcessBuilder(adbCommand, "version");
            Process process = pb.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished || process.exitValue() != 0) {
                return new Failure<>(null, "ADB not accessible");
            }
            return new Success<>(null, "ADB verified");

        } catch (IOException | InterruptedException e) {
            return new Failure<>(null, "ADB not found");
        }
    }

    private Result<String> verifyDevice(List<String> adbPrefix) {
        try {
            List<String> command = new ArrayList<>(adbPrefix);
            command.add("get-state");

            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished || process.exitValue() != 0) {
                return new Failure<>(null, "No device connected");
            }

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()));
            String state = reader.readLine();
            reader.close();

            if (!"device".equals(state != null ? state.trim() : "")) {
                return new Failure<>(null, "Device not ready: " + state);
            }

            return new Success<>(state, "Device verified");

        } catch (IOException | InterruptedException e) {
            return new Failure<>(null, "Device check failed");
        }
    }
}
