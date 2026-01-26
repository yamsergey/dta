package io.yamsergey.dta.sidekick.network.adapter.websocket;

import io.yamsergey.dta.sidekick.Sidekick;
import io.yamsergey.dta.sidekick.SidekickLog;

import io.yamsergey.dta.sidekick.jvmti.MethodHook;
import io.yamsergey.dta.sidekick.mock.MockManager;
import io.yamsergey.dta.sidekick.mock.MockRule;
import io.yamsergey.dta.sidekick.mock.MockWebSocketMessage;
import io.yamsergey.dta.sidekick.mock.adapter.WebSocketMockAdapter;
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
                // Build the original message for inspection and adapter
                WebSocketMessage originalMsg = WebSocketMessage.textMessage(
                        conn.getId(),
                        WebSocketMessage.Direction.SENT,
                        text
                ).build();

                // Check for mock rules
                MockManager mockManager = MockManager.getInstance();
                MockRule rule = mockManager.findMatchingWebSocketRule(
                        conn.getUrl(),
                        WebSocketMessage.Direction.SENT,
                        text
                );

                boolean wasMocked = false;
                String mockRuleId = null;

                if (rule != null && rule.getMockMessage() != null) {
                    // Get the mock adapter from configuration
                    WebSocketMockAdapter adapter = Sidekick.getConfig().getWebSocketMockAdapter();

                    // Let the adapter decide on the final mock
                    MockWebSocketMessage proposedMock = rule.getMockMessage();
                    MockWebSocketMessage finalMock = adapter.onMockMessage(originalMsg, proposedMock);

                    // If adapter returns null, skip mocking
                    if (finalMock == null) {
                        SidekickLog.d(TAG, "Mock skipped by adapter (rule: " + rule.getName() + ")");
                    } else if (finalMock.isDrop()) {
                        args[0] = "";
                        SidekickLog.d(TAG, "Dropped sent message (replaced with empty)");
                        rule.incrementMatchCount();

                        // Capture the dropped message as mocked
                        WebSocketMessage droppedMsg = WebSocketMessage.textMessage(
                                conn.getId(),
                                WebSocketMessage.Direction.SENT,
                                ""
                        ).mocked(true).mockRuleId(rule.getId()).build();
                        WebSocketInspector.onMessage(droppedMsg);
                        return;
                    } else if (finalMock.getTextPayload() != null) {
                        args[0] = finalMock.getTextPayload();
                        text = finalMock.getTextPayload();
                        rule.incrementMatchCount();
                        wasMocked = true;
                        mockRuleId = rule.getId();
                        SidekickLog.d(TAG, "Mocked sent text message (rule: " + rule.getName() + ")");
                    }
                }

                // Capture the final message (possibly modified)
                WebSocketMessage.Builder msgBuilder = WebSocketMessage.textMessage(
                        conn.getId(),
                        WebSocketMessage.Direction.SENT,
                        text
                );
                if (wasMocked) {
                    msgBuilder.mocked(true).mockRuleId(mockRuleId);
                }
                WebSocketInspector.onMessage(msgBuilder.build());
            }
        } catch (Throwable t) {
            SidekickLog.e(TAG, "Error in onEnter", t);
        }
    }
}
