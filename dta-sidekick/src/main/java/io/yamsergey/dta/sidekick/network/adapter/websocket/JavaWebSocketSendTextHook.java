package io.yamsergey.dta.sidekick.network.adapter.websocket;

import io.yamsergey.dta.sidekick.SidekickLog;

import io.yamsergey.dta.sidekick.jvmti.MethodHook;
import io.yamsergey.dta.sidekick.network.WebSocketConnection;
import io.yamsergey.dta.sidekick.network.WebSocketInspector;
import io.yamsergey.dta.sidekick.network.WebSocketMessage;

/**
 * JVMTI hook for Java-WebSocket text message sending.
 *
 * <p>Hooks into {@code WebSocketClient.send(String)} to capture outgoing text messages.</p>
 */
public class JavaWebSocketSendTextHook implements MethodHook {

    private static final String TAG = "JavaWsSendTextHook";

    @Override
    public String getTargetClass() {
        return "org.java_websocket.client.WebSocketClient";
    }

    @Override
    public String getTargetMethod() {
        return "send";
    }

    @Override
    public String getMethodSignature() {
        return "(Ljava/lang/String;)V";
    }

    @Override
    public String getId() {
        return "java-websocket-send-text";
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
                        WebSocketMessage.Direction.SENT,
                        text
                ).build();

                WebSocketInspector.onMessage(msg);
            }
        } catch (Throwable t) {
            SidekickLog.e(TAG, "Error in onEnter", t);
        }
    }
}
