package io.yamsergey.dta.sidekick.network.adapter.websocket;

import io.yamsergey.dta.sidekick.SidekickLog;

import io.yamsergey.dta.sidekick.jvmti.MethodHook;
import io.yamsergey.dta.sidekick.network.WebSocketConnection;
import io.yamsergey.dta.sidekick.network.WebSocketInspector;
import io.yamsergey.dta.sidekick.network.WebSocketMessage;

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

            // Interceptor: onWsSend (binary)
            io.yamsergey.dta.sidekick.interceptor.InterceptorRuntime irt =
                    io.yamsergey.dta.sidekick.interceptor.InterceptorRuntime.getInstance();
            if (irt.isInstalled()) {
                io.yamsergey.dta.sidekick.interceptor.InterceptorPayloads.WsFrameMutation mut =
                        irt.interceptWsSend(null, data, conn != null ? conn.getId() : null);
                if (mut.dropped) { args[0] = new byte[0]; return; }
                if (mut.mutated && mut.binary != null) { args[0] = mut.binary; data = mut.binary; }
            }

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
