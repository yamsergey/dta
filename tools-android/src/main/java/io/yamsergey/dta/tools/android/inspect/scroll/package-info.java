/**
 * Provides scrolling screenshot capture functionality for Android devices.
 *
 * <p>This package contains classes for capturing long screenshots of scrollable
 * content on Android devices. The key components are:</p>
 *
 * <ul>
 *   <li>{@link io.yamsergey.dta.tools.android.inspect.scroll.ScrollScreenshotCapture} -
 *       Main orchestrator that coordinates the entire capture process</li>
 *   <li>{@link io.yamsergey.dta.tools.android.inspect.scroll.ScrollableViewFinder} -
 *       Finds scrollable views in the UI hierarchy</li>
 *   <li>{@link io.yamsergey.dta.tools.android.inspect.scroll.RowHashCalculator} -
 *       Computes row hashes for overlap detection</li>
 *   <li>{@link io.yamsergey.dta.tools.android.inspect.scroll.ImageOverlapDetector} -
 *       Detects overlap between consecutive screenshots</li>
 *   <li>{@link io.yamsergey.dta.tools.android.inspect.scroll.ImageStitcher} -
 *       Stitches unique content into a single image</li>
 *   <li>{@link io.yamsergey.dta.tools.android.inspect.scroll.AdbSwipeController} -
 *       Controls swipe gestures via ADB</li>
 *   <li>{@link io.yamsergey.dta.tools.android.inspect.scroll.ScrollScreenshot} -
 *       Result model containing the captured screenshot</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>
 * Result&lt;ScrollScreenshot&gt; result = ScrollScreenshotCapture.builder()
 *     .outputFile(new File("long-screenshot.png"))
 *     .scrollToTop(true)
 *     .maxCaptures(20)
 *     .build()
 *     .capture();
 *
 * if (result instanceof Success&lt;ScrollScreenshot&gt; success) {
 *     System.out.println("Captured: " + success.value().getFile());
 * }
 * </pre>
 *
 * <h2>Algorithm Overview</h2>
 * <ol>
 *   <li>Dump UI hierarchy to find scrollable views</li>
 *   <li>Optionally scroll to top of content</li>
 *   <li>Take screenshot and crop to scrollable bounds</li>
 *   <li>Compute row hashes for overlap detection</li>
 *   <li>Swipe to scroll content</li>
 *   <li>Repeat capture/hash/swipe until:
 *     <ul>
 *       <li>Scroll end detected (no new content)</li>
 *       <li>Max captures reached</li>
 *     </ul>
 *   </li>
 *   <li>Stitch unique portions into final image</li>
 * </ol>
 *
 * @see io.yamsergey.dta.tools.android.inspect.scroll.ScrollScreenshotCapture
 */
package io.yamsergey.dta.tools.android.inspect.scroll;
