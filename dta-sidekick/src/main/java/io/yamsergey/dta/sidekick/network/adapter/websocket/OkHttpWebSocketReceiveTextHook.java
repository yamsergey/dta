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
 * JVMTI hook for OkHttp WebSocket received text messages.
 *
 * <p>Hooks into {@code RealWebSocket.onReadMessage(String)} to capture incoming text messages.
 * This method is called by WebSocketReader when a text frame is received.</p>
 */
public class OkHttpWebSocketReceiveTextHook implements MethodHook {

    private static final String TAG = "OkHttpWsRecvTextHook";

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
        return "(Ljava/lang/String;)V";
    }

    @Override
    public String getId() {
        return "okhttp-websocket-receive-text";
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
                        WebSocketMessage.Direction.RECEIVED,
                        text
                );

                if (rule != null && rule.getMockMessage() != null) {
                    MockWebSocketMessage mockMsg = rule.getMockMessage();

                    if (mockMsg.isDrop()) {
                        // For dropping, we can't really prevent the method from running,
                        // but we can replace the content with empty string
                        args[0] = "";
                        SidekickLog.d(TAG, "Dropped received message (replaced with empty)");
                        rule.incrementMatchCount();
                        return;
                    }

                    if (mockMsg.getTextPayload() != null) {
                        // Replace message content
                        args[0] = mockMsg.getTextPayload();
                        text = mockMsg.getTextPayload();
                        rule.incrementMatchCount();
                        SidekickLog.d(TAG, "Mocked received text message (rule: " + rule.getName() + ")");
                    }
                }

                WebSocketMessage msg = WebSocketMessage.textMessage(
                        conn.getId(),
                        WebSocketMessage.Direction.RECEIVED,
                        text
                ).build();

                WebSocketInspector.onMessage(msg);
                SidekickLog.d(TAG, "Captured received text message: " + text.length() + " chars");
            }
        } catch (Throwable t) {
            SidekickLog.e(TAG, "Error in onEnter", t);
        }
    }
}
