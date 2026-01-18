package io.yamsergey.adt.sidekick.network.adapter.websocket;

import io.yamsergey.adt.sidekick.SidekickLog;

import io.yamsergey.adt.sidekick.jvmti.MethodHook;
import io.yamsergey.adt.sidekick.network.WebSocketConnection;
import io.yamsergey.adt.sidekick.network.WebSocketInspector;
import io.yamsergey.adt.sidekick.network.WebSocketMessage;

/**
 * JVMTI hook for NV-WebSocket text message sending.
 *
 * <p>Hooks into {@code WebSocket.sendText(String)} to capture outgoing text messages.</p>
 */
public class NvWebSocketSendTextHook implements MethodHook {

    private static final String TAG = "NvWsSendTextHook";

    @Override
    public String getTargetClass() {
        return "com.neovisionaries.ws.client.WebSocket";
    }

    @Override
    public String getTargetMethod() {
        return "sendText";
    }

    @Override
    public String getMethodSignature() {
        return "(Ljava/lang/String;)Lcom/neovisionaries/ws/client/WebSocket;";
    }

    @Override
    public String getId() {
        return "nv-websocket-send-text";
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
            SidekickLog.e(TAG, "Error in onEnter", t);
        }
    }
}
