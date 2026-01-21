package io.yamsergey.dta.sidekick.network;

import io.yamsergey.dta.sidekick.SidekickLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * WebSocket Inspector for capturing and monitoring WebSocket connections.
 *
 * <p>This inspector captures WebSocket connections and messages using
 * {@link WebSocketConnection} and {@link WebSocketMessage} objects.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // Start a new connection
 * WebSocketConnection conn = WebSocketInspector.startConnection("wss://example.com/ws", "OkHttp");
 *
 * // Mark as connected
 * conn.markConnected();
 * WebSocketInspector.onConnectionOpened(conn);
 *
 * // Record messages
 * WebSocketMessage msg = WebSocketMessage.textMessage(conn.getId(), Direction.SENT, "Hello");
 * WebSocketInspector.onMessage(msg);
 *
 * // Close connection
 * conn.markClosed(1000, "Normal closure");
 * WebSocketInspector.onConnectionClosed(conn);
 * }</pre>
 */
public final class WebSocketInspector {

    private static final String TAG = "WebSocketInspector";
    private static final int MAX_CONNECTIONS = 100;
    private static final int MAX_MESSAGES_PER_CONNECTION = 1000;

    // Storage
    private static final List<WebSocketConnection> connections = new CopyOnWriteArrayList<>();
    private static final Map<String, WebSocketConnection> connectionsById = new ConcurrentHashMap<>();

    // Object-to-Connection mapping for tracking connections across method calls
    private static final Map<Object, String> objectToConnectionId = new ConcurrentHashMap<>();

    // Listeners
    private static final List<ConnectionListener> listeners = new CopyOnWriteArrayList<>();

    // Settings
    private static volatile boolean enabled = true;
    private static volatile boolean captureTextMessages = true;
    private static volatile boolean captureBinaryMessages = true;
    private static volatile int maxMessagePayloadSize = 64 * 1024; // 64 KB

    // Prevent instantiation
    private WebSocketInspector() {}

    // =========================================================================
    // Connection Management
    // =========================================================================

    /**
     * Starts tracking a new WebSocket connection.
     *
     * @param url    the WebSocket URL
     * @param source the source library (e.g., "OkHttp", "Java-WebSocket")
     * @return the new connection object
     */
    public static WebSocketConnection startConnection(String url, String source) {
        if (!enabled) {
            return new WebSocketConnection(url, source);
        }

        WebSocketConnection conn = new WebSocketConnection(url, source);
        addConnection(conn);

        SidekickLog.d(TAG, "Started WebSocket connection: " + conn.getId() + " " + url);
        notifyConnectionStarted(conn);

        return conn;
    }

    /**
     * Gets a connection by ID.
     *
     * @param id the connection ID
     * @return the connection, or null if not found
     */
    public static WebSocketConnection getConnection(String id) {
        return connectionsById.get(id);
    }

    /**
     * Gets all captured connections.
     *
     * @return unmodifiable list of connections (newest first)
     */
    public static List<WebSocketConnection> getConnections() {
        List<WebSocketConnection> result = new ArrayList<>(connections);
        Collections.reverse(result);
        return Collections.unmodifiableList(result);
    }

    /**
     * Gets active (open) connections.
     *
     * @return list of open connections
     */
    public static List<WebSocketConnection> getActiveConnections() {
        List<WebSocketConnection> result = new ArrayList<>();
        for (WebSocketConnection conn : connections) {
            if (conn.isOpen()) {
                result.add(conn);
            }
        }
        return result;
    }

    /**
     * Clears all captured connections.
     */
    public static void clearConnections() {
        connections.clear();
        connectionsById.clear();
        objectToConnectionId.clear();
        SidekickLog.d(TAG, "Cleared all connections");
    }

    /**
     * Gets the count of captured connections.
     */
    public static int getConnectionCount() {
        return connections.size();
    }

    // =========================================================================
    // Object-to-Connection Mapping
    // =========================================================================

    /**
     * Associates an object (e.g., WebSocket instance) with a connection ID.
     *
     * <p>This is used by hooks to track which WebSocket object corresponds
     * to which connection across different method calls.</p>
     *
     * @param obj          the WebSocket object
     * @param connectionId the connection ID
     */
    public static void mapObjectToConnection(Object obj, String connectionId) {
        if (obj != null && connectionId != null) {
            objectToConnectionId.put(obj, connectionId);
        }
    }

    /**
     * Gets the connection ID for a WebSocket object.
     *
     * @param obj the WebSocket object
     * @return the connection ID, or null if not mapped
     */
    public static String getConnectionIdForObject(Object obj) {
        return obj != null ? objectToConnectionId.get(obj) : null;
    }

    /**
     * Gets the connection for a WebSocket object.
     *
     * @param obj the WebSocket object
     * @return the connection, or null if not found
     */
    public static WebSocketConnection getConnectionForObject(Object obj) {
        String id = getConnectionIdForObject(obj);
        return id != null ? getConnection(id) : null;
    }

    /**
     * Removes the object-to-connection mapping.
     *
     * @param obj the WebSocket object
     */
    public static void unmapObject(Object obj) {
        if (obj != null) {
            objectToConnectionId.remove(obj);
        }
    }

    // =========================================================================
    // Event Handlers
    // =========================================================================

    /**
     * Called when a connection is opened.
     *
     * @param connection the opened connection
     */
    public static void onConnectionOpened(WebSocketConnection connection) {
        if (!enabled || connection == null) return;

        SidekickLog.i(TAG, "WebSocket connected: " + connection.getId() + " " + connection.getUrl());
        notifyConnectionOpened(connection);
    }

    /**
     * Called when a message is sent or received.
     *
     * @param message the message
     */
    public static void onMessage(WebSocketMessage message) {
        if (!enabled || message == null) return;

        WebSocketConnection conn = getConnection(message.getConnectionId());
        if (conn == null) {
            SidekickLog.w(TAG, "Message for unknown connection: " + message.getConnectionId());
            return;
        }

        // Check message type settings
        if (message.isText() && !captureTextMessages) return;
        if (message.isBinary() && !captureBinaryMessages) return;

        // Limit messages per connection
        if (conn.getMessageCount() >= MAX_MESSAGES_PER_CONNECTION) {
            SidekickLog.w(TAG, "Max messages reached for connection: " + conn.getId());
            return;
        }

        conn.addMessage(message);

        String direction = message.isSent() ? ">>>" : "<<<";
        String preview = message.isText()
                ? truncate(message.getTextPayload(), 50)
                : "[binary " + message.getPayloadSize() + " bytes]";
        SidekickLog.d(TAG, direction + " " + conn.getUrl() + " " + preview);

        notifyMessage(message);
    }

    /**
     * Called when a connection is closed.
     *
     * @param connection the closed connection
     */
    public static void onConnectionClosed(WebSocketConnection connection) {
        if (!enabled || connection == null) return;

        SidekickLog.i(TAG, "WebSocket closed: " + connection.getId() +
                " code=" + connection.getCloseCode() +
                " reason=" + connection.getCloseReason());
        notifyConnectionClosed(connection);
    }

    /**
     * Called when a connection fails.
     *
     * @param connection the failed connection
     */
    public static void onConnectionFailed(WebSocketConnection connection) {
        if (!enabled || connection == null) return;

        SidekickLog.e(TAG, "WebSocket failed: " + connection.getId() +
                " error=" + connection.getErrorMessage());
        notifyConnectionFailed(connection);
    }

    // =========================================================================
    // Listeners
    // =========================================================================

    /**
     * Adds a listener for WebSocket events.
     */
    public static void addListener(ConnectionListener listener) {
        listeners.add(listener);
    }

    /**
     * Removes a WebSocket listener.
     */
    public static void removeListener(ConnectionListener listener) {
        listeners.remove(listener);
    }

    // =========================================================================
    // Settings
    // =========================================================================

    public static void setEnabled(boolean enabled) {
        WebSocketInspector.enabled = enabled;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setCaptureTextMessages(boolean capture) {
        captureTextMessages = capture;
    }

    public static void setCaptureBinaryMessages(boolean capture) {
        captureBinaryMessages = capture;
    }

    public static void setMaxMessagePayloadSize(int size) {
        maxMessagePayloadSize = size;
    }

    public static int getMaxMessagePayloadSize() {
        return maxMessagePayloadSize;
    }

    // =========================================================================
    // Internal Methods
    // =========================================================================

    private static void addConnection(WebSocketConnection conn) {
        // Limit the number of stored connections
        while (connections.size() >= MAX_CONNECTIONS) {
            WebSocketConnection oldest = connections.remove(0);
            connectionsById.remove(oldest.getId());
        }

        connections.add(conn);
        connectionsById.put(conn.getId(), conn);
    }

    private static void notifyConnectionStarted(WebSocketConnection conn) {
        for (ConnectionListener listener : listeners) {
            try {
                listener.onConnectionStarted(conn);
            } catch (Exception e) {
                SidekickLog.e(TAG, "Error in listener", e);
            }
        }
    }

    private static void notifyConnectionOpened(WebSocketConnection conn) {
        for (ConnectionListener listener : listeners) {
            try {
                listener.onConnectionOpened(conn);
            } catch (Exception e) {
                SidekickLog.e(TAG, "Error in listener", e);
            }
        }
    }

    private static void notifyMessage(WebSocketMessage msg) {
        for (ConnectionListener listener : listeners) {
            try {
                listener.onMessage(msg);
            } catch (Exception e) {
                SidekickLog.e(TAG, "Error in listener", e);
            }
        }
    }

    private static void notifyConnectionClosed(WebSocketConnection conn) {
        for (ConnectionListener listener : listeners) {
            try {
                listener.onConnectionClosed(conn);
            } catch (Exception e) {
                SidekickLog.e(TAG, "Error in listener", e);
            }
        }
    }

    private static void notifyConnectionFailed(WebSocketConnection conn) {
        for (ConnectionListener listener : listeners) {
            try {
                listener.onConnectionFailed(conn);
            } catch (Exception e) {
                SidekickLog.e(TAG, "Error in listener", e);
            }
        }
    }

    private static String truncate(String s, int maxLength) {
        if (s == null) return null;
        return s.length() <= maxLength ? s : s.substring(0, maxLength) + "...";
    }

    /**
     * Listener interface for WebSocket events.
     */
    public interface ConnectionListener {
        default void onConnectionStarted(WebSocketConnection connection) {}
        default void onConnectionOpened(WebSocketConnection connection) {}
        default void onMessage(WebSocketMessage message) {}
        default void onConnectionClosed(WebSocketConnection connection) {}
        default void onConnectionFailed(WebSocketConnection connection) {}
    }
}
