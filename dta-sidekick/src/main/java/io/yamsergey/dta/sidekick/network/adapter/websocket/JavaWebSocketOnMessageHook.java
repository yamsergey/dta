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
 * JVMTI hook for Java-WebSocket received text messages.
 *
 * <p>Hooks into {@code WebSocketClient.onWebsocketMessage(WebSocket, String)} to capture
 * incoming text messages. This concrete method is called by the internal WebSocket engine
 * and then delegates to the abstract onMessage(String).</p>
 */
public class JavaWebSocketOnMessageHook implements MethodHook {

    private static final String TAG = "JavaWsOnMessageHook";

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
        return "(Lorg/java_websocket/WebSocket;Ljava/lang/String;)V";
    }

    @Override
    public String getId() {
        return "java-websocket-onmessage";
    }

    @Override
    public void onEnter(Object thisObj, Object[] args) {
        try {
            // args[0] = WebSocket conn, args[1] = String message
            if (args == null || args.length < 2 || !(args[1] instanceof String)) {
                return;
            }

            String text = (String) args[1];
            WebSocketConnection conn = WebSocketInspector.getConnectionForObject(thisObj);

            if (conn != null) {
                // Mark as connected if still in connecting state
                if (conn.getStatus() == WebSocketConnection.Status.CONNECTING) {
                    conn.markConnected();
                    WebSocketInspector.onConnectionOpened(conn);
                }

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
                        args[1] = "";
                        SidekickLog.d(TAG, "Dropped received message (replaced with empty)");
                        rule.incrementMatchCount();
                        return;
                    }

                    if (mockMsg.getTextPayload() != null) {
                        args[1] = mockMsg.getTextPayload();
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
