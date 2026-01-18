package io.yamsergey.adt.sidekick.network.adapter.websocket;

import io.yamsergey.adt.sidekick.SidekickLog;

import io.yamsergey.adt.sidekick.jvmti.MethodHook;
import io.yamsergey.adt.sidekick.network.WebSocketConnection;
import io.yamsergey.adt.sidekick.network.WebSocketInspector;
import io.yamsergey.adt.sidekick.network.WebSocketMessage;

/**
 * JVMTI hook for Java-WebSocket binary message sending.
 *
 * <p>Hooks into {@code WebSocketClient.send(byte[])} to capture outgoing binary messages.</p>
 */
public class JavaWebSocketSendBinaryHook implements MethodHook {

    private static final String TAG = "JavaWsSendBinaryHook";

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
        return "([B)V";
    }

    @Override
    public String getId() {
        return "java-websocket-send-binary";
    }

    @Override
    public void onEnter(Object thisObj, Object[] args) {
        try {
            if (args == null || args.length == 0 || !(args[0] instanceof byte[])) {
                return;
            }

            byte[] data = (byte[]) args[0];
            WebSocketConnection conn = WebSocketInspector.getConnectionForObject(thisObj);

            if (conn != null) {
                // Mark as connected if still in connecting state
                if (conn.getStatus() == WebSocketConnection.Status.CONNECTING) {
                    conn.markConnected();
                    WebSocketInspector.onConnectionOpened(conn);
                }

                int maxSize = WebSocketInspector.getMaxMessagePayloadSize();
                byte[] capturedData = data.length <= maxSize ? data : null;

                WebSocketMessage msg = WebSocketMessage.binaryMessage(
                        conn.getId(),
                        WebSocketMessage.Direction.SENT,
                        capturedData
                ).payloadSize(data.length).build();

                WebSocketInspector.onMessage(msg);
            }
        } catch (Throwable t) {
            SidekickLog.e(TAG, "Error in onEnter", t);
        }
    }
}
