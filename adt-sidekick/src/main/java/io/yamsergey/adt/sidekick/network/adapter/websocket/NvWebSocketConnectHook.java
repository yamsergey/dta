package io.yamsergey.adt.sidekick.network.adapter.websocket;

import io.yamsergey.adt.sidekick.SidekickLog;

import java.lang.reflect.Method;
import java.net.URI;

import io.yamsergey.adt.sidekick.jvmti.MethodHook;
import io.yamsergey.adt.sidekick.network.WebSocketConnection;
import io.yamsergey.adt.sidekick.network.WebSocketInspector;

/**
 * JVMTI hook for NV-WebSocket connection.
 *
 * <p>Hooks into {@code WebSocket.connect()} to capture connection start.</p>
 */
public class NvWebSocketConnectHook implements MethodHook {

    private static final String TAG = "NvWsConnectHook";

    @Override
    public String getTargetClass() {
        return "com.neovisionaries.ws.client.WebSocket";
    }

    @Override
    public String getTargetMethod() {
        return "connect";
    }

    @Override
    public String getMethodSignature() {
        return null; // Match any signature
    }

    @Override
    public String getId() {
        return "nv-websocket-connect";
    }

    @Override
    public void onEnter(Object thisObj, Object[] args) {
        try {
            String url = getWebSocketUrl(thisObj);
            if (url == null) {
                url = "unknown";
            }

            WebSocketConnection conn = WebSocketInspector.startConnection(url, "NV-WebSocket");
            WebSocketInspector.mapObjectToConnection(thisObj, conn.getId());

            SidekickLog.i(TAG, "WebSocket connecting: " + url);

        } catch (Throwable t) {
            SidekickLog.e(TAG, "Error in onEnter", t);
        }
    }

    @Override
    public Object onExit(Object thisObj, Object result) {
        try {
            WebSocketConnection conn = WebSocketInspector.getConnectionForObject(thisObj);
            if (conn != null) {
                conn.markConnected();
                WebSocketInspector.onConnectionOpened(conn);
            }
        } catch (Throwable t) {
            SidekickLog.e(TAG, "Error in onExit", t);
        }
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

    private String getWebSocketUrl(Object webSocket) {
        try {
            // Try to get URI via getURI() method
            Method getUri = webSocket.getClass().getMethod("getURI");
            Object uri = getUri.invoke(webSocket);
            if (uri instanceof URI) {
                return uri.toString();
            } else if (uri != null) {
                return uri.toString();
            }
        } catch (Exception e) {
            SidekickLog.w(TAG, "Failed to get WebSocket URL", e);
        }
        return null;
    }
}
