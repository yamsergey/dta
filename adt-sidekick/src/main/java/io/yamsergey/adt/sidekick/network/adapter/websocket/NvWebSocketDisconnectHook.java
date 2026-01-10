package io.yamsergey.adt.sidekick.network.adapter.websocket;

import android.util.Log;

import io.yamsergey.adt.sidekick.jvmti.MethodHook;
import io.yamsergey.adt.sidekick.network.WebSocketConnection;
import io.yamsergey.adt.sidekick.network.WebSocketInspector;

/**
 * JVMTI hook for NV-WebSocket connection close.
 *
 * <p>Hooks into {@code WebSocket.disconnect()} to capture connection close.</p>
 */
public class NvWebSocketDisconnectHook implements MethodHook {

    private static final String TAG = "NvWsDisconnectHook";

    @Override
    public String getTargetClass() {
        return "com.neovisionaries.ws.client.WebSocket";
    }

    @Override
    public String getTargetMethod() {
        return "disconnect";
    }

    @Override
    public String getMethodSignature() {
        // Only match the no-args disconnect() to avoid switch table corruption in
        // disconnect(int, String, long) which has a complex switch statement
        return "()Lcom/neovisionaries/ws/client/WebSocket;";
    }

    @Override
    public String getId() {
        return "nv-websocket-disconnect";
    }

    @Override
    public void onEnter(Object thisObj, Object[] args) {
        try {
            WebSocketConnection conn = WebSocketInspector.getConnectionForObject(thisObj);

            if (conn != null) {
                conn.markClosing();
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in onEnter", t);
        }
    }

    @Override
    public Object onExit(Object thisObj, Object result) {
        try {
            WebSocketConnection conn = WebSocketInspector.getConnectionForObject(thisObj);

            if (conn != null) {
                // NV-WebSocket disconnect() doesn't provide close code/reason in this method
                // Use standard normal closure code
                conn.markClosed(1000, "disconnected");
                WebSocketInspector.onConnectionClosed(conn);
                WebSocketInspector.unmapObject(thisObj);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in onExit", t);
        }
        return result;
    }
}
