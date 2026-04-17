package io.yamsergey.dta.daemon.scroll;

import io.yamsergey.dta.tools.android.inspect.ViewNode;
import lombok.Builder;
import lombok.Getter;

import java.io.File;

/**
 * Represents a scrolling screenshot captured from an Android device.
 *
 * <p>This class encapsulates the result of a scrolling screenshot capture operation,
 * including the final stitched image and metadata about the capture process.</p>
 *
 * <p>A scrolling screenshot is created by:</p>
 * <ol>
 *   <li>Identifying a scrollable view in the UI hierarchy</li>
 *   <li>Taking multiple screenshots while scrolling through content</li>
 *   <li>Detecting overlap between consecutive screenshots</li>
 *   <li>Stitching unique content into a single long image</li>
 * </ol>
 */
@Getter
@Builder
public class ScrollScreenshot {

    /**
     * The final stitched screenshot file.
     */
    private final File file;

    /**
     * The serial number of the device from which the screenshot was captured.
     * May be null if no specific device was targeted.
     */
    private final String deviceSerial;

    /**
     * The width of the final stitched image in pixels.
     */
    private final Integer width;

    /**
     * The height of the final stitched image in pixels.
     * This is the total height after stitching all unique content.
     */
    private final Integer height;

    /**
     * The number of individual screenshots captured during the scroll process.
     */
    private final int captureCount;

    /**
     * The resource ID of the scrollable view that was captured.
     * May be null if the view has no resource ID.
     */
    private final String scrollableViewId;

    /**
     * The bounds of the scrollable view on screen.
     */
    private final ViewNode.Bounds scrollableBounds;

    /**
     * Whether the capture reached the end of scrollable content naturally,
     * or stopped due to max captures limit.
     */
    private final boolean reachedScrollEnd;
}
