package io.yamsergey.dta.tools.android.inspect.scroll;

import java.util.Optional;

/**
 * Detects overlap between consecutive screenshots using row hash comparison.
 *
 * <p>When scrolling, consecutive screenshots will have overlapping content.
 * This class finds the exact overlap offset by comparing row hashes from
 * the bottom of the previous screenshot with the top of the current screenshot.</p>
 *
 * <p>The algorithm tests each possible vertical offset and finds the one
 * with the maximum number of matching rows.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * ImageOverlapDetector detector = new ImageOverlapDetector();
 *
 * // Find overlap between two screenshots
 * Optional&lt;OverlapResult&gt; result = detector.findOverlap(prevHashes, currHashes);
 * if (result.isPresent()) {
 *     int uniqueRows = currHashes.length - result.get().overlapRows();
 * }
 *
 * // Check if scroll has ended
 * if (detector.isScrollEnd(prevHashes, currHashes)) {
 *     // No new content, stop scrolling
 * }
 * </pre>
 */
public class ImageOverlapDetector {

    /**
     * Minimum overlap ratio required for a valid match.
     * At least 5% of the image height must overlap.
     */
    private static final double MIN_OVERLAP_RATIO = 0.05;

    /**
     * Minimum ratio of matching rows within the overlap region.
     * 70% of rows in the overlap region must match.
     * Lower threshold needed because rendering variations and animations
     * can cause pixel differences even in "identical" content.
     */
    private static final double MATCH_THRESHOLD = 0.70;

    /**
     * Threshold for considering scroll has ended.
     * If 98% or more rows match at any offset, content hasn't changed.
     */
    private static final double SCROLL_END_THRESHOLD = 0.98;

    /**
     * Minimum consecutive matching rows to consider a valid anchor point.
     */
    private static final int MIN_CONSECUTIVE_MATCHES = 5;

    /**
     * Result of overlap detection between two screenshots.
     *
     * @param overlapRows Number of rows that overlap between the two images
     * @param matchRatio Ratio of matching rows within the overlap region (0.0-1.0)
     * @param matchingRows Absolute count of matching rows
     */
    public record OverlapResult(int overlapRows, double matchRatio, int matchingRows) {

        /**
         * Returns the number of unique (non-overlapping) rows in the current image.
         *
         * @param currentImageHeight Total height of the current image
         * @return Number of unique rows
         */
        public int getUniqueRows(int currentImageHeight) {
            return currentImageHeight - overlapRows;
        }
    }

    /**
     * Finds the overlap between two consecutive screenshots.
     *
     * <p>The overlap is defined as: how many rows from the TOP of currHashes
     * match rows at the BOTTOM of prevHashes.</p>
     *
     * <p>For example, if overlap=100, then:</p>
     * <ul>
     *   <li>currHashes[0] should match prevHashes[prevHeight-100]</li>
     *   <li>currHashes[1] should match prevHashes[prevHeight-99]</li>
     *   <li>... and so on</li>
     * </ul>
     *
     * @param prevHashes Row hashes from the previous screenshot
     * @param currHashes Row hashes from the current screenshot
     * @return Optional containing OverlapResult if overlap found, empty otherwise
     */
    public Optional<OverlapResult> findOverlap(long[] prevHashes, long[] currHashes) {
        if (prevHashes == null || currHashes == null ||
            prevHashes.length == 0 || currHashes.length == 0) {
            return Optional.empty();
        }

        int prevHeight = prevHashes.length;
        int currHeight = currHashes.length;

        // Minimum and maximum overlap to test
        int minOverlap = Math.max(20, (int) (currHeight * MIN_OVERLAP_RATIO));
        int maxOverlap = Math.min(prevHeight, currHeight) - 20;

        if (maxOverlap <= minOverlap) {
            return Optional.empty();
        }

        int bestOverlap = 0;
        int bestMatchingRows = 0;
        double bestMatchRatio = 0;

        // Scan all possible overlaps to find the LARGEST overlap that meets threshold
        // Start from larger overlaps - we want to find the maximum valid overlap
        // to avoid content duplication
        for (int overlap = maxOverlap; overlap >= minOverlap; overlap--) {
            int matchingRows = countMatchingRowsAtOverlap(prevHashes, currHashes, overlap);
            double matchRatio = (double) matchingRows / overlap;

            // Accept the first (largest) overlap that meets the threshold
            // This prevents duplication by maximizing the overlap we skip
            if (matchRatio >= MATCH_THRESHOLD) {
                bestOverlap = overlap;
                bestMatchingRows = matchingRows;
                bestMatchRatio = matchRatio;
                break; // Take the largest valid overlap
            }
        }

        // If we found a good match, try to refine it
        if (bestOverlap > 0 && bestMatchRatio >= MATCH_THRESHOLD) {
            // Look for the exact boundary where matches start
            // This helps find the precise cut point
            int refinedOverlap = refineOverlapBoundary(prevHashes, currHashes, bestOverlap);
            if (refinedOverlap > 0) {
                bestOverlap = refinedOverlap;
                bestMatchingRows = countMatchingRowsAtOverlap(prevHashes, currHashes, refinedOverlap);
                bestMatchRatio = (double) bestMatchingRows / refinedOverlap;
            }

            return Optional.of(new OverlapResult(bestOverlap, bestMatchRatio, bestMatchingRows));
        }

        return Optional.empty();
    }

    /**
     * Refines the overlap by searching for a slightly larger overlap that still matches well.
     * Only allows increasing the overlap to prevent content duplication.
     */
    private int refineOverlapBoundary(long[] prevHashes, long[] currHashes, int initialOverlap) {
        int prevHeight = prevHashes.length;
        int maxPossible = Math.min(prevHeight, currHashes.length) - 1;

        // Only search for LARGER overlaps to avoid duplication
        // Check up to 30 rows beyond initial overlap
        int searchRange = 30;
        int bestOverlap = initialOverlap;

        for (int delta = 1; delta <= searchRange; delta++) {
            int testOverlap = initialOverlap + delta;
            if (testOverlap >= maxPossible) {
                break;
            }

            int matchingRows = countMatchingRowsAtOverlap(prevHashes, currHashes, testOverlap);
            double matchRatio = (double) matchingRows / testOverlap;

            // Accept larger overlap if it still meets a slightly relaxed threshold
            if (matchRatio >= MATCH_THRESHOLD - 0.05) {
                bestOverlap = testOverlap;
            } else {
                // Stop if match quality drops too much
                break;
            }
        }

        return bestOverlap;
    }

    /**
     * Checks if scrolling has reached the end (no new content).
     *
     * <p>Scroll end is detected when the current screenshot is nearly identical
     * to the previous one, indicating no new content was scrolled into view.</p>
     *
     * @param prevHashes Row hashes from the previous screenshot
     * @param currHashes Row hashes from the current screenshot
     * @return true if scroll has ended, false if there's new content
     */
    public boolean isScrollEnd(long[] prevHashes, long[] currHashes) {
        if (prevHashes == null || currHashes == null ||
            prevHashes.length == 0 || currHashes.length == 0) {
            return false;
        }

        // Check if images are nearly identical (didn't scroll at all)
        if (prevHashes.length == currHashes.length) {
            int matchingRows = 0;
            for (int i = 0; i < prevHashes.length; i++) {
                if (prevHashes[i] == currHashes[i]) {
                    matchingRows++;
                }
            }
            double matchRatio = (double) matchingRows / prevHashes.length;
            if (matchRatio >= SCROLL_END_THRESHOLD) {
                return true;
            }
        }

        // Also check if the overlap is so large that there's almost no new content
        Optional<OverlapResult> overlap = findOverlap(prevHashes, currHashes);
        if (overlap.isPresent()) {
            int uniqueRows = overlap.get().getUniqueRows(currHashes.length);
            // If less than 2% of the image is unique, consider it scroll end
            double uniqueRatio = (double) uniqueRows / currHashes.length;
            return uniqueRatio < 0.02;
        }

        return false;
    }

    /**
     * Counts matching rows at a specific overlap amount.
     *
     * <p>Compares the last 'overlap' rows of prevHashes with
     * the first 'overlap' rows of currHashes.</p>
     *
     * @param prevHashes Row hashes from previous screenshot
     * @param currHashes Row hashes from current screenshot
     * @param overlap Number of rows to compare
     * @return Count of matching rows
     */
    private int countMatchingRowsAtOverlap(long[] prevHashes, long[] currHashes, int overlap) {
        int prevHeight = prevHashes.length;
        int matchingRows = 0;

        for (int i = 0; i < overlap; i++) {
            int prevIndex = prevHeight - overlap + i;
            int currIndex = i;

            if (prevIndex >= 0 && prevIndex < prevHeight &&
                currIndex >= 0 && currIndex < currHashes.length) {
                if (prevHashes[prevIndex] == currHashes[currIndex]) {
                    matchingRows++;
                }
            }
        }

        return matchingRows;
    }

    /**
     * Calculates the match ratio between two hash arrays at a given offset.
     *
     * @param prevHashes Previous screenshot row hashes
     * @param currHashes Current screenshot row hashes
     * @param overlap The overlap amount to test
     * @return Match ratio (0.0-1.0)
     */
    public double calculateMatchRatio(long[] prevHashes, long[] currHashes, int overlap) {
        if (overlap <= 0) {
            return 0.0;
        }
        int matchingRows = countMatchingRowsAtOverlap(prevHashes, currHashes, overlap);
        return (double) matchingRows / overlap;
    }
}
