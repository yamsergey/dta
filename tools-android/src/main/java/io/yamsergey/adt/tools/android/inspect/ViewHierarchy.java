package io.yamsergey.adt.tools.android.inspect;

import lombok.Builder;
import lombok.Getter;

import java.io.File;

/**
 * Represents a UI view hierarchy captured from an Android device.
 *
 * <p>This class encapsulates the XML content of a view hierarchy dump along with
 * metadata about the source device and output location.</p>
 *
 * <p>The XML content follows the UIAutomator hierarchy format, which includes:</p>
 * <ul>
 *   <li>Node structure with parent-child relationships</li>
 *   <li>View attributes (class, bounds, text, content-desc, etc.)</li>
 *   <li>View properties (clickable, enabled, focused, etc.)</li>
 * </ul>
 */
@Getter
@Builder
public class ViewHierarchy {

    /**
     * The XML content of the view hierarchy.
     */
    private final String xmlContent;

    /**
     * The serial number of the device from which the hierarchy was captured.
     * May be null if no specific device was targeted.
     */
    private final String deviceSerial;

    /**
     * The output file where the hierarchy was saved.
     * May be null if the hierarchy was only kept in memory.
     */
    private final File outputFile;

    /**
     * Returns a formatted string representation of the view hierarchy.
     *
     * @return A string containing hierarchy metadata and XML content
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ViewHierarchy{\n");
        if (deviceSerial != null) {
            sb.append("  device='").append(deviceSerial).append("'\n");
        }
        if (outputFile != null) {
            sb.append("  outputFile='").append(outputFile.getAbsolutePath()).append("'\n");
        }
        sb.append("  xmlContent=\n").append(xmlContent);
        sb.append("}");
        return sb.toString();
    }
}
