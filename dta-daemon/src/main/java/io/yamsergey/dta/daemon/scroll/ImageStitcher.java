package io.yamsergey.dta.daemon.scroll;

import io.yamsergey.dta.tools.android.inspect.ViewNode;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Stitches multiple image segments into a single long screenshot.
 *
 * <p>This class combines the unique portions of multiple screenshots
 * captured during scrolling into one continuous vertical image.</p>
 *
 * <p>The stitcher can also include static UI elements (top bar, bottom navigation)
 * that should appear at the beginning and end of the final image.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * ImageStitcher stitcher = new ImageStitcher();
 *
 * // Set static UI elements from the first screenshot
 * stitcher.setTopRegion(screenshot, 0, scrollableBounds.getTop());
 * stitcher.setBottomRegion(screenshot, scrollableBounds.getBottom(), screenHeight);
 *
 * // Add segments as you capture them
 * stitcher.addFirstSegment(screenshot1, bounds);
 * stitcher.addSegmentWithOverlap(screenshot2, bounds, overlapRows);
 *
 * // Create final stitched image
 * BufferedImage result = stitcher.stitch();
 * </pre>
 */
public class ImageStitcher {

    private final List<ImageSegment> segments = new ArrayList<>();
    private int outputWidth = 0;

    // Static UI regions (top bar, bottom navigation)
    private BufferedImage topRegionImage;
    private int topRegionHeight = 0;
    private BufferedImage bottomRegionImage;
    private int bottomRegionHeight = 0;

    /**
     * Number of pixels to use for blending between segments.
     * Set to 0 to disable blending (hard cut).
     * This creates a smooth transition to hide gradient discontinuities.
     */
    private static final int BLEND_HEIGHT = 0;

    /**
     * Represents a segment of an image to be stitched.
     *
     * @param sourceImage The original full screenshot
     * @param cropBounds The bounds of the scrollable view (crop region)
     * @param startRow Start row within the cropped region (skip overlapping rows)
     * @param endRow End row within the cropped region
     */
    public record ImageSegment(
        BufferedImage sourceImage,
        ViewNode.Bounds cropBounds,
        int startRow,
        int endRow
    ) {
        /**
         * Returns the number of unique rows this segment contributes.
         */
        public int getUniqueRowCount() {
            return endRow - startRow;
        }

        /**
         * Returns the width of the cropped region.
         */
        public int getWidth() {
            return cropBounds.getWidth();
        }
    }

    /**
     * Adds the first segment (no overlap, full scrollable area).
     *
     * @param sourceImage The full screenshot
     * @param bounds The scrollable view bounds
     */
    public void addFirstSegment(BufferedImage sourceImage, ViewNode.Bounds bounds) {
        int height = Math.min(bounds.getHeight(),
                             sourceImage.getHeight() - bounds.getTop());
        addSegment(new ImageSegment(sourceImage, bounds, 0, height));
    }

    /**
     * Adds a subsequent segment with overlap already removed.
     *
     * @param sourceImage The full screenshot
     * @param bounds The scrollable view bounds
     * @param overlapRows Number of rows that overlap with previous segment
     */
    public void addSegmentWithOverlap(BufferedImage sourceImage,
                                       ViewNode.Bounds bounds,
                                       int overlapRows) {
        int height = Math.min(bounds.getHeight(),
                             sourceImage.getHeight() - bounds.getTop());
        int startRow = Math.min(overlapRows, height);
        addSegment(new ImageSegment(sourceImage, bounds, startRow, height));
    }

    /**
     * Adds a segment directly.
     *
     * @param segment The image segment to add
     */
    public void addSegment(ImageSegment segment) {
        segments.add(segment);
        outputWidth = Math.max(outputWidth, segment.getWidth());
    }

    /**
     * Returns the number of segments added.
     */
    public int getSegmentCount() {
        return segments.size();
    }

    /**
     * Replaces the last segment with a new one using different bounds.
     * Used to extend the last segment to include content that was cut off by margin.
     *
     * @param sourceImage The full screenshot (same as original last segment)
     * @param newBounds The new bounds to use (typically original bounds without margin)
     * @param overlapRows Number of rows that overlap with previous segment
     */
    public void replaceLastSegment(BufferedImage sourceImage, ViewNode.Bounds newBounds, int overlapRows) {
        if (segments.isEmpty()) {
            return;
        }
        segments.remove(segments.size() - 1);
        addSegmentWithOverlap(sourceImage, newBounds, overlapRows);
    }

    /**
     * Calculates the total height of the final stitched image,
     * including top and bottom static regions.
     */
    public int getTotalHeight() {
        int scrollableHeight = segments.stream()
                .mapToInt(ImageSegment::getUniqueRowCount)
                .sum();
        return topRegionHeight + scrollableHeight + bottomRegionHeight;
    }

    /**
     * Clears all segments and static regions.
     */
    public void clear() {
        segments.clear();
        outputWidth = 0;
        topRegionImage = null;
        topRegionHeight = 0;
        bottomRegionImage = null;
        bottomRegionHeight = 0;
    }

    /**
     * Sets the top static region (status bar + app bar) to include at the top of the final image.
     *
     * @param source The full screenshot to extract from
     * @param startY Start Y coordinate (usually 0)
     * @param endY End Y coordinate (usually scrollable view's top)
     */
    public void setTopRegion(BufferedImage source, int startY, int endY) {
        if (endY <= startY || source == null) {
            return;
        }
        int height = Math.min(endY - startY, source.getHeight() - startY);
        if (height <= 0) {
            return;
        }
        topRegionImage = source.getSubimage(0, startY, source.getWidth(), height);
        topRegionHeight = height;
        outputWidth = Math.max(outputWidth, source.getWidth());
    }

    /**
     * Sets the bottom static region (bottom navigation) to include at the bottom of the final image.
     *
     * @param source The full screenshot to extract from
     * @param startY Start Y coordinate (usually scrollable view's bottom)
     * @param endY End Y coordinate (usually screen height)
     */
    public void setBottomRegion(BufferedImage source, int startY, int endY) {
        if (endY <= startY || source == null) {
            return;
        }
        int height = Math.min(endY - startY, source.getHeight() - startY);
        if (height <= 0) {
            return;
        }
        bottomRegionImage = source.getSubimage(0, startY, source.getWidth(), height);
        bottomRegionHeight = height;
        outputWidth = Math.max(outputWidth, source.getWidth());
    }

    /**
     * Stitches all segments into a single image, including top and bottom static regions.
     * Uses alpha blending at segment boundaries to create smooth transitions.
     *
     * @return The combined BufferedImage, or null if no segments
     */
    public BufferedImage stitch() {
        if (segments.isEmpty()) {
            return null;
        }

        int totalHeight = getTotalHeight();
        if (totalHeight <= 0 || outputWidth <= 0) {
            return null;
        }

        // Create output image
        BufferedImage result = new BufferedImage(
            outputWidth, totalHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = result.createGraphics();

        int currentY = 0;

        // Draw top region (status bar + app bar)
        if (topRegionImage != null && topRegionHeight > 0) {
            g2d.drawImage(topRegionImage, 0, currentY, null);
            currentY += topRegionHeight;
        }

        // Draw scrollable content segments with blending
        BufferedImage previousSegmentImage = null;
        int previousSegmentBottom = 0;

        for (int segIdx = 0; segIdx < segments.size(); segIdx++) {
            ImageSegment segment = segments.get(segIdx);
            int uniqueRows = segment.getUniqueRowCount();
            if (uniqueRows <= 0) {
                continue;
            }

            ViewNode.Bounds bounds = segment.cropBounds();
            BufferedImage source = segment.sourceImage();

            // Calculate source coordinates (within the scrollable bounds)
            int srcX = bounds.getLeft();
            int srcY = bounds.getTop() + segment.startRow();
            int srcWidth = bounds.getWidth();
            int srcHeight = uniqueRows;

            // Clamp to image boundaries
            srcX = Math.max(0, srcX);
            srcY = Math.max(0, srcY);
            int maxSrcWidth = source.getWidth() - srcX;
            int maxSrcHeight = source.getHeight() - srcY;
            srcWidth = Math.min(srcWidth, maxSrcWidth);
            srcHeight = Math.min(srcHeight, maxSrcHeight);

            if (srcWidth <= 0 || srcHeight <= 0) {
                continue;
            }

            // For segments after the first, apply blending at the top
            if (segIdx > 0 && previousSegmentImage != null && BLEND_HEIGHT > 0) {
                int blendRows = Math.min(BLEND_HEIGHT, srcHeight);

                // Draw the non-blended portion first (below the blend zone)
                if (srcHeight > blendRows) {
                    g2d.drawImage(
                        source,
                        0, currentY + blendRows,
                        srcWidth, currentY + srcHeight,
                        srcX, srcY + blendRows,
                        srcX + srcWidth, srcY + srcHeight,
                        null
                    );
                }

                // Now blend the top portion with the bottom of previous segment
                blendRegion(result, source, bounds, segment.startRow(),
                           currentY, blendRows, previousSegmentImage,
                           previousSegmentBottom - blendRows);

            } else {
                // First segment or no blending - draw directly
                g2d.drawImage(
                    source,
                    0, currentY,
                    srcWidth, currentY + srcHeight,
                    srcX, srcY,
                    srcX + srcWidth, srcY + srcHeight,
                    null
                );
            }

            // Store for next iteration's blending
            previousSegmentImage = source;
            previousSegmentBottom = bounds.getTop() + segment.startRow() + srcHeight;

            currentY += srcHeight;
        }

        // Draw bottom region (bottom navigation)
        if (bottomRegionImage != null && bottomRegionHeight > 0) {
            g2d.drawImage(bottomRegionImage, 0, currentY, null);
        }

        g2d.dispose();
        return result;
    }

    /**
     * Blends two image regions together using linear alpha interpolation.
     */
    private void blendRegion(BufferedImage result, BufferedImage currSource,
                             ViewNode.Bounds bounds, int currStartRow,
                             int destY, int blendHeight,
                             BufferedImage prevSource, int prevY) {
        int srcX = Math.max(0, bounds.getLeft());
        int width = Math.min(bounds.getWidth(), Math.min(currSource.getWidth() - srcX,
                            prevSource.getWidth() - srcX));

        for (int row = 0; row < blendHeight; row++) {
            // Alpha goes from 0.0 (use previous) to 1.0 (use current) over blend height
            float alpha = (float) row / blendHeight;

            int currY = bounds.getTop() + currStartRow + row;
            int prevYRow = prevY + row;
            int destYRow = destY + row;

            // Check bounds
            if (currY < 0 || currY >= currSource.getHeight() ||
                prevYRow < 0 || prevYRow >= prevSource.getHeight() ||
                destYRow < 0 || destYRow >= result.getHeight()) {
                continue;
            }

            for (int x = 0; x < width; x++) {
                int srcXPos = srcX + x;
                if (srcXPos >= currSource.getWidth() || srcXPos >= prevSource.getWidth()) {
                    continue;
                }

                int currPixel = currSource.getRGB(srcXPos, currY);
                int prevPixel = prevSource.getRGB(srcXPos, prevYRow);

                int blendedPixel = blendPixels(prevPixel, currPixel, alpha);
                result.setRGB(x, destYRow, blendedPixel);
            }
        }
    }

    /**
     * Blends two pixels using linear interpolation.
     */
    private int blendPixels(int pixel1, int pixel2, float alpha) {
        int a1 = (pixel1 >> 24) & 0xFF;
        int r1 = (pixel1 >> 16) & 0xFF;
        int g1 = (pixel1 >> 8) & 0xFF;
        int b1 = pixel1 & 0xFF;

        int a2 = (pixel2 >> 24) & 0xFF;
        int r2 = (pixel2 >> 16) & 0xFF;
        int g2 = (pixel2 >> 8) & 0xFF;
        int b2 = pixel2 & 0xFF;

        int a = (int) (a1 * (1 - alpha) + a2 * alpha);
        int r = (int) (r1 * (1 - alpha) + r2 * alpha);
        int g = (int) (g1 * (1 - alpha) + g2 * alpha);
        int b = (int) (b1 * (1 - alpha) + b2 * alpha);

        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Creates a cropped image from a full screenshot using the scrollable bounds.
     *
     * @param source The full screenshot
     * @param bounds The scrollable view bounds
     * @return Cropped BufferedImage containing only the scrollable area
     */
    public static BufferedImage cropToScrollableBounds(BufferedImage source,
                                                        ViewNode.Bounds bounds) {
        int x = Math.max(0, bounds.getLeft());
        int y = Math.max(0, bounds.getTop());
        int width = Math.min(bounds.getWidth(), source.getWidth() - x);
        int height = Math.min(bounds.getHeight(), source.getHeight() - y);

        if (width <= 0 || height <= 0) {
            return null;
        }

        return source.getSubimage(x, y, width, height);
    }

    /**
     * Creates a cropped image with overlap rows removed from the top.
     *
     * @param source The full screenshot
     * @param bounds The scrollable view bounds
     * @param overlapRows Number of rows to skip from the top
     * @return Cropped BufferedImage with overlap removed
     */
    public static BufferedImage cropWithOverlapRemoved(BufferedImage source,
                                                        ViewNode.Bounds bounds,
                                                        int overlapRows) {
        int x = Math.max(0, bounds.getLeft());
        int y = Math.max(0, bounds.getTop() + overlapRows);
        int width = Math.min(bounds.getWidth(), source.getWidth() - x);
        int height = Math.min(bounds.getHeight() - overlapRows, source.getHeight() - y);

        if (width <= 0 || height <= 0) {
            return null;
        }

        return source.getSubimage(x, y, width, height);
    }
}
