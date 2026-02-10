package io.yamsergey.dta.sidekick;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.yamsergey.dta.sidekick.mock.adapter.DefaultHttpMockAdapter;
import io.yamsergey.dta.sidekick.mock.adapter.DefaultWebSocketMockAdapter;
import io.yamsergey.dta.sidekick.mock.adapter.HttpMockAdapter;
import io.yamsergey.dta.sidekick.mock.adapter.WebSocketMockAdapter;
import io.yamsergey.dta.sidekick.network.adapter.NetworkAdapter;

/**
 * Configuration for Sidekick network interception.
 *
 * <p>Use this class to customize which network adapters are enabled and to
 * register custom adapters before Sidekick initialization.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // In your Application.onCreate() BEFORE Sidekick initializes:
 * Sidekick.configure(SidekickConfig.builder()
 *     .disableOkHttp()                     // Don't intercept OkHttp
 *     .disableUrlConnection()              // Don't intercept URLConnection
 *     .addAdapter(new MyCustomAdapter())   // Add custom adapter
 *     .build());
 * }</pre>
 *
 * <h3>Default Adapters</h3>
 * All adapters are enabled by default:
 * <ul>
 *   <li><b>HTTP:</b> OkHttp, URLConnection</li>
 *   <li><b>WebSocket:</b> OkHttp WebSocket, Java-WebSocket, nv-websocket-client</li>
 * </ul>
 */
public final class SidekickConfig {

    // Debug logging (disabled by default)
    private final boolean debugLoggingEnabled;
    private final boolean fileLoggingEnabled;

    // Adapter enable/disable flags
    private final boolean okHttpEnabled;
    private final boolean urlConnectionEnabled;
    private final boolean okHttpWebSocketEnabled;
    private final boolean javaWebSocketEnabled;
    private final boolean nvWebSocketEnabled;

    // Disabled adapter IDs (for fine-grained control)
    private final Set<String> disabledAdapterIds;

    // Custom adapters
    private final List<NetworkAdapter> customAdapters;

    // Mock adapters for dynamic response customization
    private final HttpMockAdapter httpMockAdapter;
    private final WebSocketMockAdapter webSocketMockAdapter;

    private SidekickConfig(Builder builder) {
        this.debugLoggingEnabled = builder.debugLoggingEnabled;
        this.fileLoggingEnabled = builder.fileLoggingEnabled;
        this.okHttpEnabled = builder.okHttpEnabled;
        this.urlConnectionEnabled = builder.urlConnectionEnabled;
        this.okHttpWebSocketEnabled = builder.okHttpWebSocketEnabled;
        this.javaWebSocketEnabled = builder.javaWebSocketEnabled;
        this.nvWebSocketEnabled = builder.nvWebSocketEnabled;
        this.disabledAdapterIds = Collections.unmodifiableSet(new HashSet<>(builder.disabledAdapterIds));
        this.customAdapters = Collections.unmodifiableList(new ArrayList<>(builder.customAdapters));
        this.httpMockAdapter = builder.httpMockAdapter;
        this.webSocketMockAdapter = builder.webSocketMockAdapter;
    }

    /**
     * Creates a new builder with default settings (all adapters enabled).
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a default configuration (all adapters enabled, no custom adapters).
     */
    public static SidekickConfig defaults() {
        return new Builder().build();
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    /**
     * Returns whether debug logging is enabled.
     */
    public boolean isDebugLoggingEnabled() {
        return debugLoggingEnabled;
    }

    /**
     * Returns whether file logging is enabled.
     */
    public boolean isFileLoggingEnabled() {
        return fileLoggingEnabled;
    }

    public boolean isOkHttpEnabled() {
        return okHttpEnabled && !disabledAdapterIds.contains("okhttp");
    }

    public boolean isUrlConnectionEnabled() {
        return urlConnectionEnabled && !disabledAdapterIds.contains("urlconnection");
    }

    public boolean isOkHttpWebSocketEnabled() {
        return okHttpWebSocketEnabled && !disabledAdapterIds.contains("okhttp-websocket");
    }

    public boolean isJavaWebSocketEnabled() {
        return javaWebSocketEnabled && !disabledAdapterIds.contains("java-websocket");
    }

    public boolean isNvWebSocketEnabled() {
        return nvWebSocketEnabled && !disabledAdapterIds.contains("nv-websocket");
    }

    /**
     * Checks if any HTTP adapter is enabled.
     */
    public boolean isHttpInterceptionEnabled() {
        return isOkHttpEnabled() || isUrlConnectionEnabled();
    }

    /**
     * Checks if any WebSocket adapter is enabled.
     */
    public boolean isWebSocketInterceptionEnabled() {
        return isOkHttpWebSocketEnabled() || isJavaWebSocketEnabled() || isNvWebSocketEnabled();
    }

    /**
     * Checks if a specific adapter ID is enabled.
     */
    public boolean isAdapterEnabled(String adapterId) {
        if (disabledAdapterIds.contains(adapterId)) {
            return false;
        }

        // Check known adapters
        switch (adapterId) {
            case "okhttp":
                return okHttpEnabled;
            case "urlconnection":
                return urlConnectionEnabled;
            case "okhttp-websocket":
                return okHttpWebSocketEnabled;
            case "java-websocket":
                return javaWebSocketEnabled;
            case "nv-websocket":
                return nvWebSocketEnabled;
            default:
                // Unknown adapters are enabled by default (custom adapters)
                return true;
        }
    }

    /**
     * Gets the custom adapters registered with this configuration.
     */
    public List<NetworkAdapter> getCustomAdapters() {
        return customAdapters;
    }

    /**
     * Gets the HTTP mock adapter for dynamic response customization.
     */
    public HttpMockAdapter getHttpMockAdapter() {
        return httpMockAdapter;
    }

    /**
     * Gets the WebSocket mock adapter for dynamic message customization.
     */
    public WebSocketMockAdapter getWebSocketMockAdapter() {
        return webSocketMockAdapter;
    }

    // =========================================================================
    // Builder
    // =========================================================================

    public static final class Builder {
        private boolean debugLoggingEnabled = false;
        private boolean fileLoggingEnabled = false;
        private boolean okHttpEnabled = true;
        private boolean urlConnectionEnabled = true;
        private boolean okHttpWebSocketEnabled = true;
        private boolean javaWebSocketEnabled = true;
        private boolean nvWebSocketEnabled = true;
        private final Set<String> disabledAdapterIds = new HashSet<>();
        private final List<NetworkAdapter> customAdapters = new ArrayList<>();
        private HttpMockAdapter httpMockAdapter = DefaultHttpMockAdapter.getInstance();
        private WebSocketMockAdapter webSocketMockAdapter = DefaultWebSocketMockAdapter.getInstance();

        private Builder() {}

        // =====================================================================
        // Debug Logging
        // =====================================================================

        /**
         * Enables debug logging to logcat (disabled by default).
         *
         * <p>When enabled, Sidekick will log detailed debug information
         * to logcat. This is useful during development but should be
         * disabled in production to avoid polluting logcat.</p>
         */
        public Builder enableDebugLogging() {
            this.debugLoggingEnabled = true;
            return this;
        }

        /**
         * Disables debug logging (this is the default).
         */
        public Builder disableDebugLogging() {
            this.debugLoggingEnabled = false;
            return this;
        }

        /**
         * Enables file logging to {@code <cacheDir>/sidekick.log}.
         *
         * <p>Debug logging is automatically enabled when file logging is active.
         * Pull the log file with:</p>
         * <pre>{@code
         * dta-cli inspect log-pull --package com.example.app
         * }</pre>
         */
        public Builder enableFileLogging() {
            this.fileLoggingEnabled = true;
            return this;
        }

        // =====================================================================
        // HTTP Adapters
        // =====================================================================

        /**
         * Enables OkHttp interception (enabled by default).
         */
        public Builder enableOkHttp() {
            this.okHttpEnabled = true;
            this.disabledAdapterIds.remove("okhttp");
            return this;
        }

        /**
         * Disables OkHttp interception.
         */
        public Builder disableOkHttp() {
            this.okHttpEnabled = false;
            return this;
        }

        /**
         * Enables URLConnection interception (enabled by default).
         */
        public Builder enableUrlConnection() {
            this.urlConnectionEnabled = true;
            this.disabledAdapterIds.remove("urlconnection");
            return this;
        }

        /**
         * Disables URLConnection interception.
         */
        public Builder disableUrlConnection() {
            this.urlConnectionEnabled = false;
            return this;
        }

        /**
         * Disables all HTTP interception.
         */
        public Builder disableHttpInterception() {
            this.okHttpEnabled = false;
            this.urlConnectionEnabled = false;
            return this;
        }

        // =====================================================================
        // WebSocket Adapters
        // =====================================================================

        /**
         * Enables OkHttp WebSocket interception (enabled by default).
         */
        public Builder enableOkHttpWebSocket() {
            this.okHttpWebSocketEnabled = true;
            this.disabledAdapterIds.remove("okhttp-websocket");
            return this;
        }

        /**
         * Disables OkHttp WebSocket interception.
         */
        public Builder disableOkHttpWebSocket() {
            this.okHttpWebSocketEnabled = false;
            return this;
        }

        /**
         * Enables Java-WebSocket library interception (enabled by default).
         */
        public Builder enableJavaWebSocket() {
            this.javaWebSocketEnabled = true;
            this.disabledAdapterIds.remove("java-websocket");
            return this;
        }

        /**
         * Disables Java-WebSocket library interception.
         */
        public Builder disableJavaWebSocket() {
            this.javaWebSocketEnabled = false;
            return this;
        }

        /**
         * Enables nv-websocket-client library interception (enabled by default).
         */
        public Builder enableNvWebSocket() {
            this.nvWebSocketEnabled = true;
            this.disabledAdapterIds.remove("nv-websocket");
            return this;
        }

        /**
         * Disables nv-websocket-client library interception.
         */
        public Builder disableNvWebSocket() {
            this.nvWebSocketEnabled = false;
            return this;
        }

        /**
         * Disables all WebSocket interception.
         */
        public Builder disableWebSocketInterception() {
            this.okHttpWebSocketEnabled = false;
            this.javaWebSocketEnabled = false;
            this.nvWebSocketEnabled = false;
            return this;
        }

        // =====================================================================
        // Fine-grained Control
        // =====================================================================

        /**
         * Disables a specific adapter by ID.
         *
         * @param adapterId the adapter ID to disable
         */
        public Builder disableAdapter(String adapterId) {
            this.disabledAdapterIds.add(adapterId);
            return this;
        }

        /**
         * Enables a previously disabled adapter by ID.
         *
         * @param adapterId the adapter ID to enable
         */
        public Builder enableAdapter(String adapterId) {
            this.disabledAdapterIds.remove(adapterId);
            return this;
        }

        // =====================================================================
        // Custom Adapters
        // =====================================================================

        /**
         * Adds a custom network adapter.
         *
         * <p>Custom adapters are registered after the default adapters.</p>
         *
         * @param adapter the custom adapter to add
         */
        public Builder addAdapter(NetworkAdapter adapter) {
            if (adapter != null) {
                this.customAdapters.add(adapter);
            }
            return this;
        }

        /**
         * Adds multiple custom network adapters.
         *
         * @param adapters the custom adapters to add
         */
        public Builder addAdapters(NetworkAdapter... adapters) {
            if (adapters != null) {
                for (NetworkAdapter adapter : adapters) {
                    if (adapter != null) {
                        this.customAdapters.add(adapter);
                    }
                }
            }
            return this;
        }

        // =====================================================================
        // Mock Adapters
        // =====================================================================

        /**
         * Sets a custom HTTP mock adapter for dynamic response customization.
         *
         * <p>The adapter is called when an HTTP request matches a mock rule,
         * allowing you to modify the response or skip mocking entirely.</p>
         *
         * <h4>Example:</h4>
         * <pre>{@code
         * .httpMockAdapter((transaction, proposedResponse) -> {
         *     // Add request ID to response
         *     String requestId = transaction.getRequestHeaders().get("X-Request-Id");
         *     return proposedResponse.toBuilder()
         *         .body("{\"requestId\": \"" + requestId + "\"}")
         *         .build();
         * })
         * }</pre>
         *
         * @param adapter the HTTP mock adapter, or null to use the default
         */
        public Builder httpMockAdapter(HttpMockAdapter adapter) {
            this.httpMockAdapter = adapter != null ? adapter : DefaultHttpMockAdapter.getInstance();
            return this;
        }

        /**
         * Sets a custom WebSocket mock adapter for dynamic message customization.
         *
         * <p>The adapter is called when a WebSocket message matches a mock rule,
         * allowing you to modify the message or skip mocking entirely.</p>
         *
         * <h4>Example:</h4>
         * <pre>{@code
         * .webSocketMockAdapter((originalMessage, proposedMock) -> {
         *     // Echo back with modification
         *     String payload = originalMessage.getTextPayload();
         *     if (payload != null && payload.contains("ping")) {
         *         return MockWebSocketMessage.textMessage(
         *             payload.replace("ping", "pong")
         *         );
         *     }
         *     return proposedMock;
         * })
         * }</pre>
         *
         * @param adapter the WebSocket mock adapter, or null to use the default
         */
        public Builder webSocketMockAdapter(WebSocketMockAdapter adapter) {
            this.webSocketMockAdapter = adapter != null ? adapter : DefaultWebSocketMockAdapter.getInstance();
            return this;
        }

        /**
         * Builds the configuration.
         */
        public SidekickConfig build() {
            return new SidekickConfig(this);
        }
    }
}
