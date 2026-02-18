package io.yamsergey.dta.sidekick.customtabs;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * Represents a Custom Tab launch event.
 *
 * <p>This event is emitted when the app launches a Chrome Custom Tab,
 * allowing external tools to connect to Chrome's DevTools Protocol
 * and capture network traffic from the Custom Tab.</p>
 */
public class CustomTabEvent {

    private final String id;
    private final String url;
    private final Map<String, String> headers;
    private final long timestamp;
    private final String packageName;

    public CustomTabEvent(String url, Map<String, String> headers, String packageName) {
        this.id = UUID.randomUUID().toString();
        this.url = url;
        this.headers = headers != null ? headers : Collections.emptyMap();
        this.timestamp = System.currentTimeMillis();
        this.packageName = packageName;
    }

    /**
     * Returns the unique ID for this event.
     */
    public String getId() {
        return id;
    }

    /**
     * Returns the URL being opened in the Custom Tab.
     */
    public String getUrl() {
        return url;
    }

    /**
     * Returns the custom headers set via CustomTabsIntent.Builder.
     * These are headers the app explicitly added to the Custom Tab request.
     */
    public Map<String, String> getHeaders() {
        return headers;
    }

    /**
     * Returns the timestamp when the Custom Tab was launched.
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Returns the package name of the app launching the Custom Tab.
     */
    public String getPackageName() {
        return packageName;
    }

    /**
     * Returns the event type identifier for SSE.
     */
    public String getEventType() {
        return "customtab_will_launch";
    }
}
