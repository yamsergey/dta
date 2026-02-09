package io.yamsergey.dta.cli.inspect;

import io.yamsergey.dta.tools.android.inspect.scroll.FixedStepScrollCapture;
import io.yamsergey.dta.tools.android.inspect.scroll.ScrollScreenshot;
import io.yamsergey.dta.tools.android.inspect.scroll.ScrollScreenshotCapture;
import io.yamsergey.dta.tools.sugar.Failure;
import io.yamsergey.dta.tools.sugar.Result;
import io.yamsergey.dta.tools.sugar.Success;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Callable;

/**
 * CLI command for capturing scrolling screenshots from an Android device.
 *
 * <p>This command captures long screenshots of scrollable content by:</p>
 * <ul>
 *   <li>Auto-detecting scrollable views in the UI hierarchy</li>
 *   <li>Taking multiple screenshots while scrolling</li>
 *   <li>Detecting overlap and stitching unique content</li>
 *   <li>Producing a single tall PNG image</li>
 * </ul>
 *
 * <p>Example usage:</p>
 * <pre>
 * # Basic usage - auto-detect scrollable view
 * dta-cli inspect scroll-screenshot -o long.png
 *
 * # Scroll to top first, then capture
 * dta-cli inspect scroll-screenshot --scroll-to-top -o long.png
 *
 * # Target specific scrollable view by resource ID
 * dta-cli inspect scroll-screenshot --view-id "com.app:id/recycler" -o long.png
 *
 * # Control scroll behavior
 * dta-cli inspect scroll-screenshot --delay 500 --max-captures 20 -o long.png
 *
 * # Capture from specific device
 * dta-cli inspect scroll-screenshot -d emulator-5554 --scroll-to-top -o screen.png
 * </pre>
 */
@Command(name = "scroll-screenshot",
         description = "Capture scrolling/long screenshot of scrollable content. " +
                       "Uses hash-based overlap detection by default for seamless stitching.")
public class ScrollScreenshotCommand implements Callable<Integer> {

    @Parameters(index = "0",
                description = "Output PNG file path (default: scroll-screenshot-TIMESTAMP.png)",
                arity = "0..1")
    private String outputPath;

    @Option(names = {"-o", "--output"},
            description = "Output PNG file path (alternative to positional argument).")
    private String outputPathOption;

    @Option(names = {"-d", "--device"},
            description = "Device serial number. If not specified, uses first available device.")
    private String deviceSerial;

    @Option(names = {"--view-id"},
            description = "Resource ID of scrollable view to capture (e.g., 'com.app:id/recycler'). " +
                         "If not specified, auto-detects the largest scrollable view.")
    private String viewId;

    @Option(names = {"--scroll-to-top"},
            description = "Scroll to top of content before starting capture.")
    private boolean scrollToTop;

    @Option(names = {"--scroll-to-top-swipes"},
            description = "Number of swipes to perform when scrolling to top (default: 10).")
    private Integer scrollToTopSwipes;

    @Option(names = {"--delay"},
            description = "Delay in milliseconds between scroll actions (default: 500). " +
                         "Increase if content loads slowly or animations are slow.")
    private Integer delayMs;

    @Option(names = {"--max-captures"},
            description = "Maximum number of screenshots to capture (default: 30). " +
                         "Prevents infinite scrolling on endless feeds.")
    private Integer maxCaptures;

    @Option(names = {"--swipe-ratio"},
            description = "Portion of scrollable height to swipe per scroll (0.0-1.0, default: 0.7). " +
                         "Lower values create more overlap for better stitching.")
    private Double swipeRatio;

    @Option(names = {"--swipe-duration"},
            description = "Swipe animation duration in milliseconds (default: 300).")
    private Integer swipeDurationMs;

    @Option(names = {"--timeout"},
            description = "Timeout in seconds for ADB commands (default: 30).")
    private Integer timeoutSeconds;

    @Option(names = {"--adb-path"},
            description = "Path to ADB executable. If not specified, uses 'adb' from PATH.")
    private String adbPath;

    @Option(names = {"--debug"},
            description = "Save individual captures for debugging overlap detection.")
    private boolean debugMode;

    @Option(names = {"--fixed-step"},
            description = "Use fixed-step algorithm instead of hash-based overlap detection (default). " +
                         "Hash-based is recommended for seamless stitching. " +
                         "Fixed-step is more predictable but may have minor alignment issues.")
    private boolean fixedStep;

    @Option(names = {"--scroll-step"},
            description = "Scroll step in pixels for fixed-step mode (default: 400). " +
                         "Smaller values = more reliable but more captures.")
    private Integer scrollStepPixels;

    @Override
    public Integer call() throws Exception {
        // Determine output path
        String finalOutputPath = outputPathOption != null ? outputPathOption : outputPath;

        if (finalOutputPath == null || finalOutputPath.isEmpty()) {
            // Generate default filename with timestamp
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            finalOutputPath = "scroll-screenshot-" + timestamp + ".png";
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
        ScrollScreenshotCapture.ScrollScreenshotCaptureBuilder builder = ScrollScreenshotCapture.builder()
                .outputFile(outputFile)
                .scrollToTop(scrollToTop);

        if (deviceSerial != null && !deviceSerial.isEmpty()) {
            builder.deviceSerial(deviceSerial);
        }

        if (viewId != null && !viewId.isEmpty()) {
            builder.targetViewId(viewId);
        }

        if (scrollToTopSwipes != null && scrollToTopSwipes > 0) {
            builder.scrollToTopSwipes(scrollToTopSwipes);
        }

        if (delayMs != null && delayMs > 0) {
            builder.scrollDelayMs(delayMs);
        }

        if (maxCaptures != null && maxCaptures > 0) {
            builder.maxCaptures(maxCaptures);
        }

        if (swipeRatio != null && swipeRatio > 0 && swipeRatio <= 1.0) {
            builder.swipeRatio(swipeRatio);
        }

        if (swipeDurationMs != null && swipeDurationMs > 0) {
            builder.swipeDurationMs(swipeDurationMs);
        }

        if (timeoutSeconds != null && timeoutSeconds > 0) {
            builder.timeoutSeconds(timeoutSeconds);
        }

        if (adbPath != null && !adbPath.isEmpty()) {
            builder.adbPath(adbPath);
        }

        if (debugMode) {
            builder.debugMode(true);
        }

        // Execute the capture
        System.err.println("Capturing scrolling screenshot...");
        if (deviceSerial != null) {
            System.err.println("Device: " + deviceSerial);
        }
        if (viewId != null) {
            System.err.println("Target view: " + viewId);
        }
        if (scrollToTop) {
            System.err.println("Will scroll to top first");
        }

        Result<ScrollScreenshot> result;

        if (fixedStep) {
            // Use fixed-step algorithm
            System.err.println("Using fixed-step algorithm");
            FixedStepScrollCapture.FixedStepScrollCaptureBuilder fixedBuilder = FixedStepScrollCapture.builder()
                    .outputFile(outputFile)
                    .scrollToTop(scrollToTop);

            if (deviceSerial != null && !deviceSerial.isEmpty()) {
                fixedBuilder.deviceSerial(deviceSerial);
            }
            if (viewId != null && !viewId.isEmpty()) {
                fixedBuilder.targetViewId(viewId);
            }
            if (scrollToTopSwipes != null && scrollToTopSwipes > 0) {
                fixedBuilder.scrollToTopSwipes(scrollToTopSwipes);
            }
            if (delayMs != null && delayMs > 0) {
                fixedBuilder.scrollDelayMs(delayMs);
            }
            if (maxCaptures != null && maxCaptures > 0) {
                fixedBuilder.maxCaptures(maxCaptures);
            }
            if (scrollStepPixels != null && scrollStepPixels > 0) {
                fixedBuilder.scrollStep(scrollStepPixels);
            }
            if (swipeDurationMs != null && swipeDurationMs > 0) {
                fixedBuilder.swipeDurationMs(swipeDurationMs);
            }
            if (timeoutSeconds != null && timeoutSeconds > 0) {
                fixedBuilder.timeoutSeconds(timeoutSeconds);
            }
            if (adbPath != null && !adbPath.isEmpty()) {
                fixedBuilder.adbPath(adbPath);
            }
            if (debugMode) {
                fixedBuilder.debugMode(true);
            }

            result = fixedBuilder.build().capture();
        } else {
            // Use hash-based overlap detection algorithm
            result = builder.build().capture();
        }

        return switch (result) {
            case Success<ScrollScreenshot> success -> {
                ScrollScreenshot screenshot = success.value();
                String description = success.description() != null
                    ? success.description()
                    : "Scrolling screenshot captured successfully";

                System.err.println("Success: " + description);
                System.err.println("Output: " + screenshot.getFile().getAbsolutePath());
                System.err.println("Dimensions: " + screenshot.getWidth() + "x" + screenshot.getHeight());
                System.err.println("Captures: " + screenshot.getCaptureCount());

                if (screenshot.getScrollableViewId() != null) {
                    System.err.println("Scrollable view: " + screenshot.getScrollableViewId());
                }

                if (screenshot.isReachedScrollEnd()) {
                    System.err.println("Status: Reached end of scrollable content");
                } else {
                    System.err.println("Status: Stopped at max captures limit");
                }

                yield 0;
            }
            case Failure<ScrollScreenshot> failure -> {
                String description = failure.description() != null
                    ? failure.description()
                    : "Failed to capture scrolling screenshot";
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
