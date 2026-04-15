package io.yamsergey.dta.sidekick.webview;

import java.util.UUID;

/**
 * Event emitted just before {@code WebView.loadUrl} proceeds, carrying the
 * captured URL and the process PID (which keys the {@code
 * webview_devtools_remote_<pid>} debug socket).
 *
 * <p>The host uses this event to attach CDP and enable Network capture before
 * ACKing, so the subsequent load runs with capture already armed — no racing,
 * no missed early requests. Mirrors {@link io.yamsergey.dta.sidekick.customtabs.CustomTabEvent}
 * in shape and usage.</p>
 */
public class WebViewLoadEvent {

    private final String id;
    private final int pid;
    private final String url;
    private final String packageName;
    private final long timestamp;

    public WebViewLoadEvent(int pid, String url, String packageName) {
        this.id = UUID.randomUUID().toString();
        this.pid = pid;
        this.url = url;
        this.packageName = packageName;
        this.timestamp = System.currentTimeMillis();
    }

    public String getId() { return id; }
    public int getPid() { return pid; }
    public String getUrl() { return url; }
    public String getPackageName() { return packageName; }
    public long getTimestamp() { return timestamp; }

    public String getEventType() { return "webview_will_load"; }
}
