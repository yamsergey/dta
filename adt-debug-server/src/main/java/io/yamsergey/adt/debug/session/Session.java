package io.yamsergey.adt.debug.session;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Represents a debug session connected to an Android app via sidekick.
 *
 * <p>In MCP coordination mode, sessions are registered by the MCP server
 * when it attaches to an app. Each session tracks its sidekick URL and
 * the currently selected UI element (set via web UI).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Session {

    private final String id;
    private final String device;
    private final String packageName;
    private final String sidekickUrl;
    private final long createdAt;

    // Selection state (set via web UI, read by MCP)
    private Map<String, Object> selectedElement;
    private long selectedAt;

    // Network request selection (set via web UI, read by MCP)
    private Map<String, Object> selectedNetworkRequest;
    private long networkSelectedAt;

    public Session(String device, String packageName, String sidekickUrl) {
        this.id = device + ":" + packageName;
        this.device = device;
        this.packageName = packageName;
        this.sidekickUrl = sidekickUrl;
        this.createdAt = System.currentTimeMillis();
    }

    public String getId() {
        return id;
    }

    public String getDevice() {
        return device;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getSidekickUrl() {
        return sidekickUrl;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public Map<String, Object> getSelectedElement() {
        return selectedElement;
    }

    public void setSelectedElement(Map<String, Object> selectedElement) {
        this.selectedElement = selectedElement;
        this.selectedAt = System.currentTimeMillis();
    }

    public long getSelectedAt() {
        return selectedAt;
    }

    public void clearSelection() {
        this.selectedElement = null;
        this.selectedAt = 0;
    }

    public Map<String, Object> getSelectedNetworkRequest() {
        return selectedNetworkRequest;
    }

    public void setSelectedNetworkRequest(Map<String, Object> selectedNetworkRequest) {
        this.selectedNetworkRequest = selectedNetworkRequest;
        this.networkSelectedAt = System.currentTimeMillis();
    }

    public long getNetworkSelectedAt() {
        return networkSelectedAt;
    }

    public void clearNetworkSelection() {
        this.selectedNetworkRequest = null;
        this.networkSelectedAt = 0;
    }
}
