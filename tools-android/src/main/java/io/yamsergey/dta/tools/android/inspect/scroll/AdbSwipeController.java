package io.yamsergey.dta.tools.android.inspect.scroll;

import io.yamsergey.dta.tools.android.inspect.ViewNode;
import io.yamsergey.dta.tools.sugar.Failure;
import io.yamsergey.dta.tools.sugar.Result;
import io.yamsergey.dta.tools.sugar.Success;
import lombok.Builder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Controls swipe gestures on Android devices via ADB.
 *
 * <p>This class executes swipe commands within specified bounds to scroll
 * content in a scrollable view. It supports both scrolling up (to reveal
 * more content below) and scrolling down (to return to the top).</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * AdbSwipeController controller = AdbSwipeController.builder()
 *     .deviceSerial("emulator-5554")
 *     .build();
 *
 * ViewNode.Bounds bounds = scrollableView.getBounds();
 *
 * // Swipe up to scroll content
 * controller.swipeUp(bounds, 0.7, 300);
 *
 * // Scroll to top before capture
 * controller.scrollToTop(bounds);
 * </pre>
 */
@Builder
public class AdbSwipeController {

    /**
     * Optional device serial number. If not specified, uses first available device.
     */
    private final String deviceSerial;

    /**
     * Path to ADB executable. If not specified, assumes 'adb' is in PATH.
     */
    private final String adbPath;

    /**
     * Timeout in seconds for ADB commands.
     */
    @Builder.Default
    private final int timeoutSeconds = 30;

    /**
     * Margin from edges when calculating swipe coordinates (as ratio of dimension).
     * Prevents accidental edge gestures.
     */
    private static final double EDGE_MARGIN_RATIO = 0.15;

    /**
     * Maximum number of swipes when scrolling to top.
     */
    private static final int MAX_SCROLL_TO_TOP_SWIPES = 50;

    /**
     * Default swipe duration in milliseconds.
     */
    private static final int DEFAULT_SWIPE_DURATION_MS = 300;

    /**
     * Delay between swipes when scrolling to top (milliseconds).
     */
    private static final int SCROLL_TO_TOP_DELAY_MS = 200;

    /**
     * Swipes up within the scrollable view bounds to scroll content down.
     *
     * <p>This swipes from bottom to top within the scrollable area,
     * causing the content to scroll upward (revealing content below).</p>
     *
     * @param bounds The scrollable view bounds
     * @param swipeRatio Portion of the height to swipe (0.0-1.0)
     * @param durationMs Swipe animation duration in milliseconds
     * @return Result indicating success or failure
     */
    public Result<Void> swipeUp(ViewNode.Bounds bounds, double swipeRatio, int durationMs) {
        int centerX = bounds.getCenterX();
        int height = bounds.getHeight();

        // Calculate swipe start and end points with margins
        int marginY = (int) (height * EDGE_MARGIN_RATIO);
        int startY = bounds.getBottom() - marginY;
        int endY = bounds.getTop() + marginY;

        // Adjust based on swipe ratio
        int swipeDistance = (int) ((startY - endY) * swipeRatio);
        endY = startY - swipeDistance;

        return executeSwipe(centerX, startY, centerX, endY, durationMs);
    }

    /**
     * Swipes down within the scrollable view bounds to scroll content up.
     *
     * <p>This swipes from top to bottom within the scrollable area,
     * causing the content to scroll downward (revealing content above).</p>
     *
     * @param bounds The scrollable view bounds
     * @param swipeRatio Portion of the height to swipe (0.0-1.0)
     * @param durationMs Swipe animation duration in milliseconds
     * @return Result indicating success or failure
     */
    public Result<Void> swipeDown(ViewNode.Bounds bounds, double swipeRatio, int durationMs) {
        int centerX = bounds.getCenterX();
        int height = bounds.getHeight();

        // Calculate swipe start and end points with margins
        int marginY = (int) (height * EDGE_MARGIN_RATIO);
        int startY = bounds.getTop() + marginY;
        int endY = bounds.getBottom() - marginY;

        // Adjust based on swipe ratio
        int swipeDistance = (int) ((endY - startY) * swipeRatio);
        endY = startY + swipeDistance;

        return executeSwipe(centerX, startY, centerX, endY, durationMs);
    }

    /**
     * Scrolls to the top of the scrollable view.
     *
     * <p>Performs repeated rapid swipes down until the content stops changing,
     * indicating the top has been reached.</p>
     *
     * @param bounds The scrollable view bounds
     * @param hashCalculator Calculator to check if content changed
     * @param captureScreenshot Function to capture current screen for comparison
     * @return Result indicating success or failure
     */
    public Result<Integer> scrollToTop(ViewNode.Bounds bounds,
                                       RowHashCalculator hashCalculator,
                                       ScreenshotSupplier captureScreenshot) {
        long[] prevHashes = null;
        int swipeCount = 0;

        for (int i = 0; i < MAX_SCROLL_TO_TOP_SWIPES; i++) {
            // Swipe down (scroll content up toward top)
            Result<Void> swipeResult = swipeDown(bounds, 0.8, DEFAULT_SWIPE_DURATION_MS);
            if (swipeResult instanceof Failure<Void> failure) {
                return new Failure<>(null, "Scroll to top failed after " + swipeCount + " swipes: " + failure.description());
            }
            swipeCount++;

            // Wait for scroll animation
            try {
                Thread.sleep(SCROLL_TO_TOP_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Failure<>(null, "Scroll to top interrupted after " + swipeCount + " swipes");
            }

            // Capture screenshot and compute hashes
            try {
                java.awt.image.BufferedImage screenshot = captureScreenshot.capture();
                if (screenshot == null) {
                    continue;
                }

                long[] currHashes = hashCalculator.computeRowHashes(screenshot, bounds);

                // Check if content stopped changing (reached top)
                if (prevHashes != null && hashCalculator.areIdentical(prevHashes, currHashes)) {
                    return new Success<>(swipeCount, "Reached top after " + swipeCount + " swipes");
                }

                prevHashes = currHashes;
            } catch (Exception e) {
                // Continue trying even if one capture fails
            }
        }

        return new Success<>(swipeCount,
            "Scroll to top completed after max " + swipeCount + " swipes (may not be at absolute top)");
    }

    /**
     * Simplified scroll to top without hash comparison.
     *
     * <p>Performs a fixed number of rapid swipes down. Use when you don't need
     * precise detection of reaching the top.</p>
     *
     * @param bounds The scrollable view bounds
     * @param swipeCount Number of swipes to perform
     * @return Result indicating success or failure
     */
    public Result<Void> scrollToTopSimple(ViewNode.Bounds bounds, int swipeCount) {
        for (int i = 0; i < swipeCount; i++) {
            Result<Void> swipeResult = swipeDown(bounds, 0.9, 150);
            if (swipeResult instanceof Failure<Void>) {
                return swipeResult;
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Failure<>(null, "Scroll to top interrupted");
            }
        }

        // Final delay to let content settle
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return new Success<>(null, "Scroll to top completed");
    }

    /**
     * Executes an ADB swipe command.
     *
     * @param x1 Start X coordinate
     * @param y1 Start Y coordinate
     * @param x2 End X coordinate
     * @param y2 End Y coordinate
     * @param durationMs Swipe duration in milliseconds
     * @return Result indicating success or failure
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
                return new Failure<>(null, "Swipe command timed out");
            }

            if (process.exitValue() != 0) {
                String error = readProcessError(process);
                return new Failure<>(null, "Swipe failed: " + error);
            }

            return new Success<>(null, "Swipe completed");

        } catch (IOException e) {
            return new Failure<>(null, "Failed to execute swipe: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Failure<>(null, "Swipe interrupted");
        }
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

    /**
     * Functional interface for supplying screenshots during scroll-to-top detection.
     */
    @FunctionalInterface
    public interface ScreenshotSupplier {
        java.awt.image.BufferedImage capture() throws Exception;
    }
}
