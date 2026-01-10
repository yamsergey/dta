package io.yamsergey.adt.sidekick.network.adapter.websocket;

import android.util.Log;

import io.yamsergey.adt.sidekick.jvmti.MethodHook;
import io.yamsergey.adt.sidekick.network.WebSocketConnection;
import io.yamsergey.adt.sidekick.network.WebSocketInspector;
import io.yamsergey.adt.sidekick.network.WebSocketMessage;

/**
 * JVMTI hook for Java-WebSocket received text messages.
 *
 * <p>Hooks into {@code WebSocketClient.onMessage(String)} to capture incoming text messages.</p>
 */
public class JavaWebSocketOnMessageHook implements MethodHook {

    private static final String TAG = "JavaWsOnMessageHook";

    @Override
    public String getTargetClass() {
        return "org.java_websocket.client.WebSocketClient";
    }

    @Override
    public String getTargetMethod() {
        return "onMessage";
    }

    @Override
    public String getMethodSignature() {
        return "(Ljava/lang/String;)V";
    }

    @Override
    public String getId() {
        return "java-websocket-onmessage";
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
                // Mark as connected if still in connecting state
                if (conn.getStatus() == WebSocketConnection.Status.CONNECTING) {
                    conn.markConnected();
                    WebSocketInspector.onConnectionOpened(conn);
                }

                WebSocketMessage msg = WebSocketMessage.textMessage(
                        conn.getId(),
                        WebSocketMessage.Direction.RECEIVED,
                        text
                ).build();

                WebSocketInspector.onMessage(msg);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in onEnter", t);
        }
    }
}
