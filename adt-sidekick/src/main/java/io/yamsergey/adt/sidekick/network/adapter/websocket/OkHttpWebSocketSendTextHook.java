package io.yamsergey.adt.sidekick.network.adapter.websocket;

import android.util.Log;

import io.yamsergey.adt.sidekick.jvmti.MethodHook;
import io.yamsergey.adt.sidekick.network.WebSocketConnection;
import io.yamsergey.adt.sidekick.network.WebSocketInspector;
import io.yamsergey.adt.sidekick.network.WebSocketMessage;

/**
 * JVMTI hook for OkHttp WebSocket text message sending.
 *
 * <p>Hooks into {@code RealWebSocket.send(String)} to capture outgoing text messages.</p>
 */
public class OkHttpWebSocketSendTextHook implements MethodHook {

    private static final String TAG = "OkHttpWsSendTextHook";

    @Override
    public String getTargetClass() {
        return "okhttp3.internal.ws.RealWebSocket";
    }

    @Override
    public String getTargetMethod() {
        return "send";
    }

    @Override
    public String getMethodSignature() {
        return "(Ljava/lang/String;)Z";
    }

    @Override
    public String getId() {
        return "okhttp-websocket-send-text";
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
                        WebSocketMessage.Direction.SENT,
                        text
                ).build();

                WebSocketInspector.onMessage(msg);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in onEnter", t);
        }
    }
}
