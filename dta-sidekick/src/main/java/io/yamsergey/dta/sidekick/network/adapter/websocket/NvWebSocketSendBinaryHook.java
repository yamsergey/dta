package io.yamsergey.dta.sidekick.network.adapter.websocket;

import io.yamsergey.dta.sidekick.SidekickLog;

import io.yamsergey.dta.sidekick.jvmti.MethodHook;
import io.yamsergey.dta.sidekick.network.WebSocketConnection;
import io.yamsergey.dta.sidekick.network.WebSocketInspector;
import io.yamsergey.dta.sidekick.network.WebSocketMessage;

/**
 * JVMTI hook for NV-WebSocket binary message sending.
 *
 * <p>Hooks into {@code WebSocket.sendBinary(byte[])} to capture outgoing binary messages.</p>
 */
public class NvWebSocketSendBinaryHook implements MethodHook {

    private static final String TAG = "NvWsSendBinaryHook";

    @Override
    public String getTargetClass() {
        return "com.neovisionaries.ws.client.WebSocket";
    }

    @Override
    public String getTargetMethod() {
        return "sendBinary";
    }

    @Override
    public String getMethodSignature() {
        return "([B)Lcom/neovisionaries/ws/client/WebSocket;";
    }

    @Override
    public String getId() {
        return "nv-websocket-send-binary";
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
