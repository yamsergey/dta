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
 * JVMTI hook for NV-WebSocket received text messages.
 *
 * <p>Hooks into {@code ListenerManager.callOnTextMessage(WebSocket, String)} to capture
 * incoming text messages. This internal method is called by the reading thread when
 * a text frame is received.</p>
 */
public class NvWebSocketReceiveTextHook implements MethodHook {

    private static final String TAG = "NvWsRecvTextHook";

    @Override
    public String getTargetClass() {
        return "com.neovisionaries.ws.client.ListenerManager";
    }

    @Override
    public String getTargetMethod() {
        return "callOnTextMessage";
    }

    @Override
    public String getMethodSignature() {
        // Return null to match any method signature with this name
        return null;
    }

    @Override
    public String getId() {
        return "nv-websocket-receive-text";
    }

    @Override
    public void onEnter(Object thisObj, Object[] args) {
        try {
            if (args == null || args.length == 0) {
                return;
            }

            String text = null;
            Object webSocketObj = null;

            // Handle different method signatures:
            // 1. callOnTextMessage(WebSocket, String) - args[0]=WebSocket, args[1]=String
            // 2. callOnTextMessage(String) - args[0]=String (WebSocket accessed via ListenerManager)
            if (args.length >= 2 && args[1] instanceof String) {
                webSocketObj = args[0];
                text = (String) args[1];
            } else if (args[0] instanceof String) {
                text = (String) args[0];
                // Try to get WebSocket from ListenerManager's mWebSocket field
                webSocketObj = getWebSocketFromListenerManager(thisObj);
            }

            if (text == null) {
                return;
            }

            WebSocketConnection conn = null;
            if (webSocketObj != null) {
                conn = WebSocketInspector.getConnectionForObject(webSocketObj);
            }

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
                        // Replace with empty string
                        if (args.length >= 2) {
                            args[1] = "";
                        } else {
                            args[0] = "";
                        }
                        SidekickLog.d(TAG, "Dropped received message (replaced with empty)");
                        rule.incrementMatchCount();
                        return;
                    }

                    if (mockMsg.getTextPayload() != null) {
                        // Replace message content
                        if (args.length >= 2) {
                            args[1] = mockMsg.getTextPayload();
                        } else {
                            args[0] = mockMsg.getTextPayload();
                        }
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
            } else {
                SidekickLog.d(TAG, "Received text message but no connection found: " + text.length() + " chars");
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
