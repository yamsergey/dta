package io.yamsergey.adt.sidekick.network.adapter.websocket;

import io.yamsergey.adt.sidekick.SidekickLog;

import java.lang.reflect.Field;
import java.net.URI;

import io.yamsergey.adt.sidekick.jvmti.MethodHook;
import io.yamsergey.adt.sidekick.network.WebSocketConnection;
import io.yamsergey.adt.sidekick.network.WebSocketInspector;

/**
 * JVMTI hook for Java-WebSocket connection.
 *
 * <p>Hooks into {@code WebSocketClient.connect()} to capture connection start.</p>
 */
public class JavaWebSocketConnectHook implements MethodHook {

    private static final String TAG = "JavaWsConnectHook";

    @Override
    public String getTargetClass() {
        return "org.java_websocket.client.WebSocketClient";
    }

    @Override
    public String getTargetMethod() {
        return "connect";
    }

    @Override
    public String getMethodSignature() {
        return "()V";
    }

    @Override
    public String getId() {
        return "java-websocket-connect";
    }

    @Override
    public void onEnter(Object thisObj, Object[] args) {
        try {
            String url = getWebSocketUrl(thisObj);
            if (url == null) {
                url = "unknown";
            }

            WebSocketConnection conn = WebSocketInspector.startConnection(url, "Java-WebSocket");
            WebSocketInspector.mapObjectToConnection(thisObj, conn.getId());

            SidekickLog.i(TAG, "WebSocket connecting: " + url);

        } catch (Throwable t) {
            SidekickLog.e(TAG, "Error in onEnter", t);
        }
    }

    @Override
    public Object onExit(Object thisObj, Object result) {
        // connect() is async, actual connection happens later
        // The connection status will be updated by onOpen callback if hooked
        return result;
    }

    @Override
    public Throwable onException(Object thisObj, Throwable throwable) {
        try {
            WebSocketConnection conn = WebSocketInspector.getConnectionForObject(thisObj);
            if (conn != null) {
                conn.markFailed(throwable.getMessage());
                WebSocketInspector.onConnectionFailed(conn);
            }
        } catch (Throwable t) {
            SidekickLog.e(TAG, "Error in onException", t);
        }
        return throwable;
    }

    private String getWebSocketUrl(Object webSocketClient) {
        try {
            // Try to get uri field from WebSocketClient
            Field uriField = findField(webSocketClient.getClass(), "uri");
            if (uriField != null) {
                uriField.setAccessible(true);
                Object uri = uriField.get(webSocketClient);
                if (uri instanceof URI) {
                    return uri.toString();
                }
            }
        } catch (Exception e) {
            SidekickLog.w(TAG, "Failed to get WebSocket URL", e);
        }
        return null;
    }

    private Field findField(Class<?> clazz, String fieldName) {
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }
}
