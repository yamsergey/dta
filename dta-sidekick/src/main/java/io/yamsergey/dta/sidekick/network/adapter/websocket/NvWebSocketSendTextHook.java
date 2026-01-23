package io.yamsergey.dta.sidekick.network.adapter.websocket;

import io.yamsergey.dta.sidekick.SidekickLog;

import io.yamsergey.dta.sidekick.jvmti.MethodHook;
import io.yamsergey.dta.sidekick.mock.MockManager;
import io.yamsergey.dta.sidekick.mock.MockRule;
import io.yamsergey.dta.sidekick.mock.MockWebSocketMessage;
import io.yamsergey.dta.sidekick.network.WebSocketConnection;
import io.yamsergey.dta.sidekick.network.WebSocketInspector;
import io.yamsergey.dta.sidekick.network.WebSocketMessage;

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
                // Check for mock rules
                MockManager mockManager = MockManager.getInstance();
                MockRule rule = mockManager.findMatchingWebSocketRule(
                        conn.getUrl(),
                        WebSocketMessage.Direction.SENT,
                        text
                );

                if (rule != null && rule.getMockMessage() != null) {
                    MockWebSocketMessage mockMsg = rule.getMockMessage();

                    if (mockMsg.isDrop()) {
                        args[0] = "";
                        SidekickLog.d(TAG, "Dropped sent message (replaced with empty)");
                        rule.incrementMatchCount();
                        return;
                    }

                    if (mockMsg.getTextPayload() != null) {
                        args[0] = mockMsg.getTextPayload();
                        text = mockMsg.getTextPayload();
                        rule.incrementMatchCount();
                        SidekickLog.d(TAG, "Mocked sent text message (rule: " + rule.getName() + ")");
                    }
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
