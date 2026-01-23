package io.yamsergey.dta.sidekick.mock;

/**
 * Global mock configuration settings.
 */
public class MockConfig {

    private volatile boolean enabled = true;
    private volatile boolean httpMockingEnabled = true;
    private volatile boolean webSocketMockingEnabled = true;

    public MockConfig() {
    }

    // Getters
    public boolean isEnabled() {
        return enabled;
    }

    public boolean isHttpMockingEnabled() {
        return httpMockingEnabled;
    }

    public boolean isWebSocketMockingEnabled() {
        return webSocketMockingEnabled;
    }

    // Setters
    public MockConfig setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    public MockConfig setHttpMockingEnabled(boolean httpMockingEnabled) {
        this.httpMockingEnabled = httpMockingEnabled;
        return this;
    }

    public MockConfig setWebSocketMockingEnabled(boolean webSocketMockingEnabled) {
        this.webSocketMockingEnabled = webSocketMockingEnabled;
        return this;
    }

    /**
     * Returns true if HTTP mocking is active (global enabled AND HTTP enabled).
     */
    public boolean isHttpMockingActive() {
        return enabled && httpMockingEnabled;
    }

    /**
     * Returns true if WebSocket mocking is active (global enabled AND WebSocket enabled).
     */
    public boolean isWebSocketMockingActive() {
        return enabled && webSocketMockingEnabled;
    }
}
