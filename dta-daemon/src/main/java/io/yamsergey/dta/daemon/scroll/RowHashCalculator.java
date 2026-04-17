package io.yamsergey.dta.daemon.scroll;

import io.yamsergey.dta.tools.android.inspect.ViewNode;

import java.awt.image.BufferedImage;

/**
 * Computes hash values for each row of an image region.
 *
 * <p>Row hashes are used to detect overlap between consecutive screenshots
 * by comparing which rows are identical. The hashing algorithm uses only
 * the center portion of each row to avoid artifacts from:</p>
 * <ul>
 *   <li>Scrollbar visibility changes</li>
 *   <li>Edge shadows or rounded corners</li>
 *   <li>Status bar clock/battery updates</li>
 * </ul>
 *
 * <p>The algorithm uses FNV-1a hash for fast, reliable row comparison.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * RowHashCalculator calculator = new RowHashCalculator();
 * BufferedImage screenshot = ImageIO.read(screenshotFile);
 * ViewNode.Bounds bounds = scrollableView.getBounds();
 *
 * long[] hashes = calculator.computeRowHashes(screenshot, bounds);
 * </pre>
 */
public class RowHashCalculator {

    /**
     * Start position for hash calculation as ratio of width (20% from left).
     */
    private static final double START_RATIO = 0.20;

    /**
     * End position for hash calculation as ratio of width (80% from left).
     */
    private static final double END_RATIO = 0.80;

    /**
     * FNV-1a 64-bit offset basis.
     */
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;

    /**
     * FNV-1a 64-bit prime.
     */
    private static final long FNV_PRIME = 0x100000001b3L;

    /**
     * Pixel sampling step for high-DPI screens.
     * Sample every Nth pixel to improve performance.
     */
    private static final int SAMPLE_STEP = 2;

    /**
     * Computes hash values for all rows within the specified bounds.
     *
     * @param image The source screenshot image
     * @param bounds The bounds of the scrollable view to analyze
     * @return Array of hash values, one per row within the bounds
     */
    public long[] computeRowHashes(BufferedImage image, ViewNode.Bounds bounds) {
        int top = bounds.getTop();
        int bottom = bounds.getBottom();
        int left = bounds.getLeft();
        int width = bounds.getWidth();

        // Clamp to image boundaries
        top = Math.max(0, top);
        bottom = Math.min(image.getHeight(), bottom);
        left = Math.max(0, left);
        int right = Math.min(image.getWidth(), left + width);
        width = right - left;

        int height = bottom - top;
        if (height <= 0 || width <= 0) {
            return new long[0];
        }

        // Calculate the center region to hash
        int hashStartX = left + (int) (width * START_RATIO);
        int hashEndX = left + (int) (width * END_RATIO);

        long[] rowHashes = new long[height];

        for (int y = top; y < bottom; y++) {
            rowHashes[y - top] = computeRowHash(image, y, hashStartX, hashEndX);
        }

        return rowHashes;
    }

    /**
     * Computes hash values for the full image (no bounds restriction).
     *
     * @param image The source image
     * @return Array of hash values, one per row
     */
    public long[] computeRowHashes(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();

        int hashStartX = (int) (width * START_RATIO);
        int hashEndX = (int) (width * END_RATIO);

        long[] rowHashes = new long[height];

        for (int y = 0; y < height; y++) {
            rowHashes[y] = computeRowHash(image, y, hashStartX, hashEndX);
        }

        return rowHashes;
    }

    /**
     * Computes FNV-1a hash for a single row.
     *
     * @param image The source image
     * @param y The row index
     * @param startX Start X position for hashing
     * @param endX End X position for hashing
     * @return Hash value for the row
     */
    private long computeRowHash(BufferedImage image, int y, int startX, int endX) {
        long hash = FNV_OFFSET_BASIS;

        for (int x = startX; x < endX; x += SAMPLE_STEP) {
            int rgb = image.getRGB(x, y);

            // FNV-1a: XOR then multiply
            hash ^= (rgb & 0xFF);          // Blue
            hash *= FNV_PRIME;
            hash ^= ((rgb >> 8) & 0xFF);   // Green
            hash *= FNV_PRIME;
            hash ^= ((rgb >> 16) & 0xFF);  // Red
            hash *= FNV_PRIME;
        }

        return hash;
    }

    /**
     * Checks if two row hash arrays are identical (100% match).
     *
     * @param hashes1 First hash array
     * @param hashes2 Second hash array
     * @return true if all hashes match, false otherwise
     */
    public boolean areIdentical(long[] hashes1, long[] hashes2) {
        if (hashes1.length != hashes2.length) {
            return false;
        }

        for (int i = 0; i < hashes1.length; i++) {
            if (hashes1[i] != hashes2[i]) {
                return false;
            }
        }

        return true;
    }

    /**
     * Counts matching rows between two hash arrays.
     *
     * @param hashes1 First hash array
     * @param hashes2 Second hash array
     * @return Number of rows with matching hashes
     */
    public int countMatchingRows(long[] hashes1, long[] hashes2) {
        int minLength = Math.min(hashes1.length, hashes2.length);
        int matches = 0;

        for (int i = 0; i < minLength; i++) {
            if (hashes1[i] == hashes2[i]) {
                matches++;
            }
        }

        return matches;
    }
}
