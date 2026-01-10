package io.yamsergey.adt.sidekick.network.adapter.websocket;

import android.util.Log;

import java.lang.reflect.Method;

import io.yamsergey.adt.sidekick.jvmti.MethodHook;
import io.yamsergey.adt.sidekick.network.WebSocketConnection;
import io.yamsergey.adt.sidekick.network.WebSocketInspector;

/**
 * JVMTI hook for OkHttp WebSocket connection.
 *
 * <p>Hooks into {@code RealWebSocket.connect()} to capture connection start.</p>
 */
public class OkHttpWebSocketConnectHook implements MethodHook {

    private static final String TAG = "OkHttpWsConnectHook";

    @Override
    public String getTargetClass() {
        return "okhttp3.internal.ws.RealWebSocket";
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
        return "okhttp-websocket-connect";
    }

    @Override
    public void onEnter(Object thisObj, Object[] args) {
        try {
            // Get URL from the RealWebSocket instance
            String url = getWebSocketUrl(thisObj);
            if (url == null) {
                url = "unknown";
            }

            // Start connection tracking
            WebSocketConnection conn = WebSocketInspector.startConnection(url, "OkHttp");

            // Map the WebSocket object to connection for later lookups
            WebSocketInspector.mapObjectToConnection(thisObj, conn.getId());

            Log.i(TAG, "WebSocket connecting: " + url);

        } catch (Throwable t) {
            Log.e(TAG, "Error in onEnter", t);
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
            Log.e(TAG, "Error in onExit", t);
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
            Log.e(TAG, "Error in onException", t);
        }
        return throwable;
    }

    private String getWebSocketUrl(Object webSocket) {
        try {
            // Try to get originalRequest field
            java.lang.reflect.Field requestField = webSocket.getClass().getDeclaredField("originalRequest");
            requestField.setAccessible(true);
            Object request = requestField.get(webSocket);

            if (request != null) {
                Method urlMethod = request.getClass().getMethod("url");
                Object url = urlMethod.invoke(request);
                return url != null ? url.toString() : null;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to get WebSocket URL", e);
        }
        return null;
    }
}
