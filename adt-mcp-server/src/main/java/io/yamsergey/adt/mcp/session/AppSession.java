package io.yamsergey.adt.mcp.session;

import lombok.Builder;
import lombok.Data;

/**
 * Represents an active connection to an app via sidekick.
 *
 * <p>Each session tracks:
 * <ul>
 *   <li>Device serial number (which emulator/device)</li>
 *   <li>Package name (which app)</li>
 *   <li>Local port (for HTTP calls to sidekick)</li>
 *   <li>Device port (where sidekick listens)</li>
 * </ul>
 */
@Data
@Builder
public class AppSession {
    private final String id;
    private final String device;
    private final String packageName;
    private final int localPort;
    private final int devicePort;

    /**
     * Gets the sidekick HTTP base URL for this session.
     */
    public String getSidekickUrl() {
        return "http://localhost:" + localPort;
    }

    /**
     * Creates a session ID from device and package.
     */
    public static String createId(String device, String packageName) {
        return device + ":" + packageName;
    }
}
