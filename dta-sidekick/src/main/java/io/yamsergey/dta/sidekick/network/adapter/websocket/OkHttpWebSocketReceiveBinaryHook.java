package io.yamsergey.dta.sidekick.network.adapter.websocket;

import io.yamsergey.dta.sidekick.SidekickLog;

import java.lang.reflect.Method;

import io.yamsergey.dta.sidekick.jvmti.MethodHook;
import io.yamsergey.dta.sidekick.network.WebSocketConnection;
import io.yamsergey.dta.sidekick.network.WebSocketInspector;
import io.yamsergey.dta.sidekick.network.WebSocketMessage;

/**
 * JVMTI hook for OkHttp WebSocket received binary messages.
 *
 * <p>Hooks into {@code RealWebSocket.onReadMessage(ByteString)} to capture incoming binary messages.
 * This method is called by WebSocketReader when a binary frame is received.</p>
 */
public class OkHttpWebSocketReceiveBinaryHook implements MethodHook {

    private static final String TAG = "OkHttpWsRecvBinaryHook";

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
        return "(Lokio/ByteString;)V";
    }

    @Override
    public String getId() {
        return "okhttp-websocket-receive-binary";
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

            // Interceptor: onWsReceive (binary). Drop replaces with empty
            // ByteString. Mutation rebuilds args[0] via okio.ByteString.of.
            io.yamsergey.dta.sidekick.interceptor.InterceptorRuntime irt =
                    io.yamsergey.dta.sidekick.interceptor.InterceptorRuntime.getInstance();
            if (irt.isInstalled() && data != null) {
                io.yamsergey.dta.sidekick.interceptor.InterceptorPayloads.WsFrameMutation mut =
                        irt.interceptWsReceive(null, data,
                                conn != null ? conn.getId() : null);
                if (mut.dropped) {
                    try { args[0] = byteString.getClass().getField("EMPTY").get(null); } catch (Throwable ignored) {}
                    return;
                }
                if (mut.mutated && mut.binary != null) {
                    try {
                        java.lang.reflect.Method of = byteString.getClass().getMethod("of", byte[].class);
                        Object replaced = of.invoke(null, (Object) mut.binary);
                        if (replaced != null) {
                            args[0] = replaced;
                            data = mut.binary;
                        }
                    } catch (Throwable ignored) {}
                }
            }

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

    private byte[] extractByteArray(Object byteString) {
        try {
            Method toByteArray = byteString.getClass().getMethod("toByteArray");
            return (byte[]) toByteArray.invoke(byteString);
        } catch (Exception e) {
            SidekickLog.w(TAG, "Failed to extract byte array from ByteString", e);
            return null;
        }
    }
}
