package io.yamsergey.dta.sidekick.network.adapter.websocket;

import io.yamsergey.dta.sidekick.SidekickLog;

import io.yamsergey.dta.sidekick.jvmti.MethodHook;
import io.yamsergey.dta.sidekick.network.WebSocketConnection;
import io.yamsergey.dta.sidekick.network.WebSocketInspector;
import io.yamsergey.dta.sidekick.network.WebSocketMessage;

/**
 * JVMTI hook for NV-WebSocket received binary messages.
 *
 * <p>Hooks into {@code ListenerManager.callOnBinaryMessage(WebSocket, byte[])} to capture
 * incoming binary messages. This internal method is called by the reading thread when
 * a binary frame is received.</p>
 */
public class NvWebSocketReceiveBinaryHook implements MethodHook {

    private static final String TAG = "NvWsRecvBinaryHook";

    @Override
    public String getTargetClass() {
        return "com.neovisionaries.ws.client.ListenerManager";
    }

    @Override
    public String getTargetMethod() {
        return "callOnBinaryMessage";
    }

    @Override
    public String getMethodSignature() {
        // Return null to match any method signature with this name
        return null;
    }

    @Override
    public String getId() {
        return "nv-websocket-receive-binary";
    }

    @Override
    public void onEnter(Object thisObj, Object[] args) {
        try {
            if (args == null || args.length == 0) {
                return;
            }

            byte[] data = null;
            Object webSocketObj = null;

            // Handle different method signatures:
            // 1. callOnBinaryMessage(WebSocket, byte[]) - args[0]=WebSocket, args[1]=byte[]
            // 2. callOnBinaryMessage(byte[]) - args[0]=byte[] (WebSocket accessed via ListenerManager)
            if (args.length >= 2 && args[1] instanceof byte[]) {
                webSocketObj = args[0];
                data = (byte[]) args[1];
            } else if (args[0] instanceof byte[]) {
                data = (byte[]) args[0];
                // Try to get WebSocket from ListenerManager's mWebSocket field
                webSocketObj = getWebSocketFromListenerManager(thisObj);
            }

            if (data == null) {
                return;
            }

            WebSocketConnection conn = null;
            if (webSocketObj != null) {
                conn = WebSocketInspector.getConnectionForObject(webSocketObj);
            }

            if (conn != null) {
                int maxSize = WebSocketInspector.getMaxMessagePayloadSize();
                byte[] capturedData = data.length <= maxSize ? data : null;

                WebSocketMessage msg = WebSocketMessage.binaryMessage(
                        conn.getId(),
                        WebSocketMessage.Direction.RECEIVED,
                        capturedData
                ).payloadSize(data.length).build();

                WebSocketInspector.onMessage(msg);
                SidekickLog.d(TAG, "Captured received binary message: " + data.length + " bytes");
            } else {
                SidekickLog.d(TAG, "Received binary message but no connection found: " + data.length + " bytes");
            }
        } catch (Throwable t) {
            SidekickLog.e(TAG, "Error in onEnter", t);
        }
    }

    private Object getWebSocketFromListenerManager(Object listenerManager) {
        try {
            java.lang.reflect.Field field = listenerManager.getClass().getDeclaredField("mWebSocket");
            field.setAccessible(true);
            return field.get(listenerManager);
        } catch (Exception e) {
            SidekickLog.w(TAG, "Could not get WebSocket from ListenerManager", e);
            return null;
        }
    }
}
