package io.yamsergey.dta.sidekick.network.adapter.websocket;

import io.yamsergey.dta.sidekick.SidekickLog;

import io.yamsergey.dta.sidekick.jvmti.MethodHook;
import io.yamsergey.dta.sidekick.network.WebSocketConnection;
import io.yamsergey.dta.sidekick.network.WebSocketInspector;
import io.yamsergey.dta.sidekick.network.WebSocketMessage;

/**
 * JVMTI hook for OkHttp WebSocket received text messages.
 *
 * <p>Hooks into {@code RealWebSocket.onReadMessage(String)} to capture incoming text messages.
 * This method is called by WebSocketReader when a text frame is received.</p>
 */
public class OkHttpWebSocketReceiveTextHook implements MethodHook {

    private static final String TAG = "OkHttpWsRecvTextHook";

    @Override
    public String getTargetClass() {
        return "okhttp3.internal.ws.RealWebSocket";
    }

    @Override
    public String getTargetMethod() {
        return "onReadMessage";
    }

    @Override
    public String getMethodSignature() {
        return "(Ljava/lang/String;)V";
    }

    @Override
    public String getId() {
        return "okhttp-websocket-receive-text";
    }

    @Override
    public void onEnter(Object thisObj, Object[] args) {
        try {
            if (args == null || args.length == 0 || !(args[0] instanceof String)) {
                return;
            }

            String text = (String) args[0];
            WebSocketConnection conn = WebSocketInspector.getConnectionForObject(thisObj);

            if (conn != null) {
                WebSocketMessage msg = WebSocketMessage.textMessage(
                        conn.getId(),
                        WebSocketMessage.Direction.RECEIVED,
                        text
                ).build();

                WebSocketInspector.onMessage(msg);
                SidekickLog.d(TAG, "Captured received text message: " + text.length() + " chars");
            }
        } catch (Throwable t) {
            SidekickLog.e(TAG, "Error in onEnter", t);
        }
    }
}
