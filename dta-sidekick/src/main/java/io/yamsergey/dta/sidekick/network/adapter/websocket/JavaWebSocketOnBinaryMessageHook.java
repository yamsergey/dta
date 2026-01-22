package io.yamsergey.dta.sidekick.network.adapter.websocket;

import io.yamsergey.dta.sidekick.SidekickLog;

import java.nio.ByteBuffer;

import io.yamsergey.dta.sidekick.jvmti.MethodHook;
import io.yamsergey.dta.sidekick.network.WebSocketConnection;
import io.yamsergey.dta.sidekick.network.WebSocketInspector;
import io.yamsergey.dta.sidekick.network.WebSocketMessage;

/**
 * JVMTI hook for Java-WebSocket received binary messages.
 *
 * <p>Hooks into {@code WebSocketClient.onWebsocketMessage(WebSocket, ByteBuffer)} to capture
 * incoming binary messages. This concrete method is called by the internal WebSocket engine
 * and then delegates to the abstract onMessage(ByteBuffer).</p>
 */
public class JavaWebSocketOnBinaryMessageHook implements MethodHook {

    private static final String TAG = "JavaWsOnBinaryMsgHook";

    @Override
    public String getTargetClass() {
        return "org.java_websocket.client.WebSocketClient";
    }

    @Override
    public String getTargetMethod() {
        return "onWebsocketMessage";
    }

    @Override
    public String getMethodSignature() {
        return "(Lorg/java_websocket/WebSocket;Ljava/nio/ByteBuffer;)V";
    }

    @Override
    public String getId() {
        return "java-websocket-onbinarymessage";
    }

    @Override
    public void onEnter(Object thisObj, Object[] args) {
        try {
            // args[0] = WebSocket conn, args[1] = ByteBuffer blob
            if (args == null || args.length < 2 || !(args[1] instanceof ByteBuffer)) {
                return;
            }

            ByteBuffer buffer = (ByteBuffer) args[1];
            byte[] data = extractByteArray(buffer);

            WebSocketConnection conn = WebSocketInspector.getConnectionForObject(thisObj);

            if (conn != null && data != null) {
                int maxSize = WebSocketInspector.getMaxMessagePayloadSize();
                byte[] capturedData = data.length <= maxSize ? data : null;

                WebSocketMessage msg = WebSocketMessage.binaryMessage(
                        conn.getId(),
                        WebSocketMessage.Direction.RECEIVED,
                        capturedData
                ).payloadSize(data.length).build();

                WebSocketInspector.onMessage(msg);
                SidekickLog.d(TAG, "Captured received binary message: " + data.length + " bytes");
            }
        } catch (Throwable t) {
            SidekickLog.e(TAG, "Error in onEnter", t);
        }
    }

    private byte[] extractByteArray(ByteBuffer buffer) {
        try {
            // Create a copy to avoid modifying the original buffer's position
            ByteBuffer duplicate = buffer.duplicate();
            duplicate.rewind();
            byte[] data = new byte[duplicate.remaining()];
            duplicate.get(data);
            return data;
        } catch (Exception e) {
            SidekickLog.w(TAG, "Failed to extract byte array from ByteBuffer", e);
            return null;
        }
    }
}
