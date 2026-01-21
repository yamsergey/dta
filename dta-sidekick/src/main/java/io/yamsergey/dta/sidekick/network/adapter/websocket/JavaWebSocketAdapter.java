package io.yamsergey.dta.sidekick.network.adapter.websocket;

import java.util.Arrays;
import java.util.List;

import io.yamsergey.dta.sidekick.jvmti.MethodHook;
import io.yamsergey.dta.sidekick.network.adapter.AdapterType;
import io.yamsergey.dta.sidekick.network.adapter.NetworkAdapter;

/**
 * Network adapter for Java-WebSocket library (org.java-websocket:Java-WebSocket).
 *
 * <p>Intercepts WebSocket connections and messages via
 * {@code org.java_websocket.client.WebSocketClient} class.</p>
 *
 * <p>Hook points:</p>
 * <ul>
 *   <li>{@code connect()} - connection initiation</li>
 *   <li>{@code onOpen(ServerHandshake)} - connection established</li>
 *   <li>{@code send(String)} - text message sent</li>
 *   <li>{@code send(byte[])} - binary message sent</li>
 *   <li>{@code onMessage(String)} - text message received</li>
 *   <li>{@code onClose(int, String, boolean)} - connection close</li>
 * </ul>
 */
public class JavaWebSocketAdapter implements NetworkAdapter {

    private static final String JAVA_WEBSOCKET_CLASS = "org.java_websocket.client.WebSocketClient";

    @Override
    public String getId() {
        return "java-websocket";
    }

    @Override
    public String getName() {
        return "Java-WebSocket";
    }

    @Override
    public AdapterType getType() {
        return AdapterType.WEBSOCKET;
    }

    @Override
    public boolean isAvailable() {
        try {
            Class.forName(JAVA_WEBSOCKET_CLASS);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public List<MethodHook> getHooks() {
        return Arrays.asList(
            new JavaWebSocketConnectHook(),
            new JavaWebSocketOnOpenHook(),
            new JavaWebSocketSendTextHook(),
            new JavaWebSocketSendBinaryHook(),
            new JavaWebSocketOnMessageHook(),
            new JavaWebSocketOnCloseHook()
        );
    }

    @Override
    public int getPriority() {
        return 80;
    }
}
