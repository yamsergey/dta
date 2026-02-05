package io.yamsergey.dta.sidekick.network.adapter;

/**
 * Types of network adapters supported by the interception framework.
 */
public enum AdapterType {
    /**
     * HTTP/HTTPS request adapters (OkHttp, URLConnection, etc.)
     */
    HTTP,

    /**
     * WebSocket connection adapters
     */
    WEBSOCKET,

    /**
     * Chrome Custom Tabs adapter (for detecting Custom Tab launches)
     */
    CUSTOM_TABS
}
