package io.yamsergey.dta.sidekick.network.adapter.websocket;

import io.yamsergey.dta.sidekick.SidekickLog;

import io.yamsergey.dta.sidekick.jvmti.MethodHook;
import io.yamsergey.dta.sidekick.network.WebSocketConnection;
import io.yamsergey.dta.sidekick.network.WebSocketInspector;

/**
 * JVMTI hook for Java-WebSocket connection close.
 *
 * <p>Hooks into {@code WebSocketClient.onClose(int, String, boolean)} to capture connection close.</p>
 */
public class JavaWebSocketOnCloseHook implements MethodHook {

    private static final String TAG = "JavaWsOnCloseHook";

    @Override
    public String getTargetClass() {
        return "org.java_websocket.client.WebSocketClient";
    }

    @Override
    public String getTargetMethod() {
        return "onClose";
    }

    @Override
    public String getMethodSignature() {
        return "(ILjava/lang/String;Z)V";
    }

    @Override
    public String getId() {
        return "java-websocket-onclose";
    }

    @Override
    public void onEnter(Object thisObj, Object[] args) {
        try {
            int code = 1000;
            String reason = null;
            boolean remote = false;

            if (args != null && args.length >= 1 && args[0] instanceof Integer) {
                code = (Integer) args[0];
            }
            if (args != null && args.length >= 2 && args[1] instanceof String) {
                reason = (String) args[1];
            }
            if (args != null && args.length >= 3 && args[2] instanceof Boolean) {
                remote = (Boolean) args[2];
            }

            WebSocketConnection conn = WebSocketInspector.getConnectionForObject(thisObj);

            if (conn != null) {
                String fullReason = reason;
                if (remote) {
                    fullReason = (reason != null ? reason + " " : "") + "(remote)";
                }

                conn.markClosed(code, fullReason);
                WebSocketInspector.onConnectionClosed(conn);
                WebSocketInspector.unmapObject(thisObj);
            }
        } catch (Throwable t) {
            SidekickLog.e(TAG, "Error in onEnter", t);
        }
    }
}
