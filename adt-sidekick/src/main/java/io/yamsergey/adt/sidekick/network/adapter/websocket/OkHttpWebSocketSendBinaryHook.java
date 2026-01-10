package io.yamsergey.adt.sidekick.network.adapter.websocket;

import android.util.Log;

import java.lang.reflect.Method;

import io.yamsergey.adt.sidekick.jvmti.MethodHook;
import io.yamsergey.adt.sidekick.network.WebSocketConnection;
import io.yamsergey.adt.sidekick.network.WebSocketInspector;
import io.yamsergey.adt.sidekick.network.WebSocketMessage;

/**
 * JVMTI hook for OkHttp WebSocket binary message sending.
 *
 * <p>Hooks into {@code RealWebSocket.send(ByteString)} to capture outgoing binary messages.</p>
 */
public class OkHttpWebSocketSendBinaryHook implements MethodHook {

    private static final String TAG = "OkHttpWsSendBinaryHook";

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
        return "(Lokio/ByteString;)Z";
    }

    @Override
    public String getId() {
        return "okhttp-websocket-send-binary";
    }

    @Override
    public void onEnter(Object thisObj, Object[] args) {
        try {
            if (args == null || args.length == 0 || args[0] == null) {
                return;
            }

            Object byteString = args[0];
            byte[] data = extractByteArray(byteString);

            WebSocketConnection conn = WebSocketInspector.getConnectionForObject(thisObj);

            if (conn != null && data != null) {
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
            Log.e(TAG, "Error in onEnter", t);
        }
    }

    private byte[] extractByteArray(Object byteString) {
        try {
            Method toByteArray = byteString.getClass().getMethod("toByteArray");
            return (byte[]) toByteArray.invoke(byteString);
        } catch (Exception e) {
            Log.w(TAG, "Failed to extract byte array from ByteString", e);
            return null;
        }
    }
}
