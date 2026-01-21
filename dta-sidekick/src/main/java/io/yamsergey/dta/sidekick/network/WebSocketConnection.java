package io.yamsergey.dta.sidekick.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Represents a WebSocket connection with its messages.
 *
 * <p>Tracks connection lifecycle (connecting → open → closing → closed)
 * and stores all messages exchanged over the connection.</p>
 */
public class WebSocketConnection {

    private final String id;
    private final String url;
    private final String source;  // e.g., "OkHttp", "Java-WebSocket", "nv-websocket"
    private final long createdAt;
    private final Map<String, String> headers;
    private final List<WebSocketMessage> messages;

    private volatile long connectedAt;
    private volatile long closedAt;
    private volatile Status status;
    private volatile int closeCode;
    private volatile String closeReason;
    private volatile String errorMessage;

    public WebSocketConnection(String url, String source) {
        this.id = UUID.randomUUID().toString();
        this.url = url;
        this.source = source;
        this.createdAt = System.currentTimeMillis();
        this.headers = new ConcurrentHashMap<>();
        this.messages = new CopyOnWriteArrayList<>();
        this.status = Status.CONNECTING;
    }

    // Getters
    public String getId() { return id; }
    public String getUrl() { return url; }
    public String getSource() { return source; }
    public long getCreatedAt() { return createdAt; }
    public long getConnectedAt() { return connectedAt; }
    public long getClosedAt() { return closedAt; }
    public Status getStatus() { return status; }
    public int getCloseCode() { return closeCode; }
    public String getCloseReason() { return closeReason; }
    public String getErrorMessage() { return errorMessage; }

    public Map<String, String> getHeaders() {
        return Collections.unmodifiableMap(headers);
    }

    public List<WebSocketMessage> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    public int getMessageCount() {
        return messages.size();
    }

    public int getSentMessageCount() {
        int count = 0;
        for (WebSocketMessage msg : messages) {
            if (msg.isSent()) count++;
        }
        return count;
    }

    public int getReceivedMessageCount() {
        int count = 0;
        for (WebSocketMessage msg : messages) {
            if (msg.isReceived()) count++;
        }
        return count;
    }

    /**
     * Gets the connection duration in milliseconds.
     *
     * @return duration, or -1 if not yet connected
     */
    public long getDuration() {
        if (connectedAt <= 0) return -1;
        long endTime = closedAt > 0 ? closedAt : System.currentTimeMillis();
        return endTime - connectedAt;
    }

    public boolean isOpen() { return status == Status.OPEN; }
    public boolean isClosed() { return status == Status.CLOSED; }
    public boolean isFailed() { return status == Status.FAILED; }

    // Modifiers
    public void addHeader(String name, String value) {
        headers.put(name, value);
    }

    public void markConnected() {
        this.connectedAt = System.currentTimeMillis();
        this.status = Status.OPEN;
    }

    public void markClosing() {
        this.status = Status.CLOSING;
    }

    public void markClosed(int code, String reason) {
        this.closedAt = System.currentTimeMillis();
        this.closeCode = code;
        this.closeReason = reason;
        this.status = Status.CLOSED;
    }

    public void markFailed(String errorMessage) {
        this.closedAt = System.currentTimeMillis();
        this.errorMessage = errorMessage;
        this.status = Status.FAILED;
    }

    public void addMessage(WebSocketMessage message) {
        messages.add(message);
    }

    /**
     * Connection status.
     */
    public enum Status {
        /** Connection is being established */
        CONNECTING,
        /** Connection is open and ready for messages */
        OPEN,
        /** Connection is closing */
        CLOSING,
        /** Connection is closed normally */
        CLOSED,
        /** Connection failed with an error */
        FAILED
    }

    @Override
    public String toString() {
        return "WebSocketConnection{" +
                "id='" + id + '\'' +
                ", url='" + url + '\'' +
                ", status=" + status +
                ", messages=" + messages.size() +
                '}';
    }
}
