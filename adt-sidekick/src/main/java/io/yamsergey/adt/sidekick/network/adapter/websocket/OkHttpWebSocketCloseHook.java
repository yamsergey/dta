package io.yamsergey.adt.sidekick.network.adapter.websocket;

import android.util.Log;

import io.yamsergey.adt.sidekick.jvmti.MethodHook;
import io.yamsergey.adt.sidekick.network.WebSocketConnection;
import io.yamsergey.adt.sidekick.network.WebSocketInspector;

/**
 * JVMTI hook for OkHttp WebSocket close.
 *
 * <p>Hooks into {@code RealWebSocket.close(int, String)} to capture connection close.</p>
 */
public class OkHttpWebSocketCloseHook implements MethodHook {

    private static final String TAG = "OkHttpWsCloseHook";

    @Override
    public String getTargetClass() {
        return "okhttp3.internal.ws.RealWebSocket";
    }

    @Override
    public String getTargetMethod() {
        return "close";
    }

    @Override
    public String getMethodSignature() {
        return "(ILjava/lang/String;)Z";
    }

    @Override
    public String getId() {
        return "okhttp-websocket-close";
    }

    @Override
    public void onEnter(Object thisObj, Object[] args) {
        try {
            int code = 1000;
            String reason = null;

            if (args != null && args.length >= 1 && args[0] instanceof Integer) {
                code = (Integer) args[0];
            }
            if (args != null && args.length >= 2 && args[1] instanceof String) {
                reason = (String) args[1];
            }

            WebSocketConnection conn = WebSocketInspector.getConnectionForObject(thisObj);

            if (conn != null) {
                conn.markClosing();
            }

            // Store close details for onExit
            storeCloseDetails(code, reason);

        } catch (Throwable t) {
            Log.e(TAG, "Error in onEnter", t);
        }
    }

    @Override
    public Object onExit(Object thisObj, Object result) {
        try {
            CloseDetails details = getCloseDetails();
            WebSocketConnection conn = WebSocketInspector.getConnectionForObject(thisObj);

            if (conn != null && details != null) {
                conn.markClosed(details.code, details.reason);
                WebSocketInspector.onConnectionClosed(conn);
                WebSocketInspector.unmapObject(thisObj);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in onExit", t);
        }
        return result;
    }

    // =========================================================================
    // ThreadLocal storage for close details
    // =========================================================================

    private static final ThreadLocal<CloseDetails> closeDetailsHolder = new ThreadLocal<>();

    private static class CloseDetails {
        final int code;
        final String reason;

        CloseDetails(int code, String reason) {
            this.code = code;
            this.reason = reason;
        }
    }

    private void storeCloseDetails(int code, String reason) {
        closeDetailsHolder.set(new CloseDetails(code, reason));
    }

    private CloseDetails getCloseDetails() {
        CloseDetails details = closeDetailsHolder.get();
        closeDetailsHolder.remove();
        return details;
    }
}
