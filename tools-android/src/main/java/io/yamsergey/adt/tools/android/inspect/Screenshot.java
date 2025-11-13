package io.yamsergey.adt.tools.android.inspect;

import lombok.Builder;
import lombok.Getter;

import java.io.File;

/**
 * Represents a screenshot captured from an Android device.
 *
 * <p>This class encapsulates the screenshot file along with metadata about the
 * source device and capture details.</p>
 */
@Getter
@Builder
public class Screenshot {

    /**
     * The screenshot file (PNG format).
     */
    private final File file;

    /**
     * The serial number of the device from which the screenshot was captured.
     * May be null if no specific device was targeted.
     */
    private final String deviceSerial;

    /**
     * The width of the screenshot in pixels.
     */
    private final Integer width;

    /**
     * The height of the screenshot in pixels.
     */
    private final Integer height;

    /**
     * Returns a formatted string representation of the screenshot.
     *
     * @return A string containing screenshot metadata
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Screenshot{\n");
        if (deviceSerial != null) {
            sb.append("  device='").append(deviceSerial).append("'\n");
        }
        if (width != null && height != null) {
            sb.append("  dimensions=").append(width).append("x").append(height).append("\n");
        }
        sb.append("  file='").append(file.getAbsolutePath()).append("'\n");
        sb.append("}");
        return sb.toString();
    }
}
