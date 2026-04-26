package io.yamsergey.dta.sidekick.chromeintent;

import java.util.UUID;

/**
 * Emitted when the host app fires {@code Intent.ACTION_VIEW} for an
 * http(s) URL that resolves to a Chromium-based browser. The daemon uses
 * this to correlate a Chrome tab it observes via CDP {@code Target.targetCreated}
 * back to the originating app call.
 *
 * <p>Distinct from {@link io.yamsergey.dta.sidekick.customtabs.CustomTabEvent}
 * because the daemon-side capture path differs (persistent browser CDP +
 * {@code setAutoAttach} versus per-Custom-Tab CDP + about:blank swap).</p>
 */
public class ChromeLaunchEvent {

    private final String id;
    private final String url;
    private final long timestamp;
    private final String packageName;
    private final String targetBrowserPackage;

    public ChromeLaunchEvent(String url, String packageName, String targetBrowserPackage) {
        this.id = UUID.randomUUID().toString();
        this.url = url;
        this.timestamp = System.currentTimeMillis();
        this.packageName = packageName;
        this.targetBrowserPackage = targetBrowserPackage;
    }

    public String getId() {
        return id;
    }

    public String getUrl() {
        return url;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getPackageName() {
        return packageName;
    }

    /**
     * Package name of the resolved browser activity (e.g. {@code com.android.chrome}).
     * The daemon uses this to know which {@code @<package>_devtools_remote} socket
     * to attach to.
     */
    public String getTargetBrowserPackage() {
        return targetBrowserPackage;
    }

    public String getEventType() {
        return "chrome_will_launch";
    }
}
