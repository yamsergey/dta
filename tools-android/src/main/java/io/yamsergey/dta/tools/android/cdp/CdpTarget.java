package io.yamsergey.dta.tools.android.cdp;

/**
 * Represents a Chrome DevTools Protocol target (tab).
 *
 * @param id                    unique target identifier
 * @param type                  target type (e.g., "page", "worker", "service_worker")
 * @param title                 page title
 * @param url                   page URL
 * @param webSocketDebuggerUrl  WebSocket URL for debugging this target
 * @param devtoolsFrontendUrl   DevTools frontend URL
 * @param faviconUrl            favicon URL (may be null)
 */
public record CdpTarget(
    String id,
    String type,
    String title,
    String url,
    String webSocketDebuggerUrl,
    String devtoolsFrontendUrl,
    String faviconUrl
) {
    /**
     * Returns true if this target is a page (not a worker or service worker).
     */
    public boolean isPage() {
        return "page".equals(type);
    }

    /**
     * Returns true if the target URL starts with the given prefix.
     * Useful for finding a tab by URL when the exact URL might differ due to redirects.
     */
    public boolean urlStartsWith(String prefix) {
        return url != null && url.startsWith(prefix);
    }

    /**
     * Returns true if the target URL contains the given substring.
     */
    public boolean urlContains(String substring) {
        return url != null && url.contains(substring);
    }
}
