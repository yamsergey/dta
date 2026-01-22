package io.yamsergey.dta.sidekick.network.adapter.websocket;

import java.util.Arrays;
import java.util.List;

import io.yamsergey.dta.sidekick.jvmti.MethodHook;
import io.yamsergey.dta.sidekick.network.adapter.AdapterType;
import io.yamsergey.dta.sidekick.network.adapter.NetworkAdapter;

/**
 * Network adapter for nv-websocket-client library (com.neovisionaries:nv-websocket-client).
 *
 * <p>Intercepts WebSocket connections and messages via
 * {@code com.neovisionaries.ws.client.WebSocket} and
 * {@code com.neovisionaries.ws.client.ListenerManager} classes.</p>
 *
 * <p>Hook points:</p>
 * <ul>
 *   <li>{@code WebSocket.connect()} - connection establishment</li>
 *   <li>{@code WebSocket.sendText(String)} - text message sent</li>
 *   <li>{@code WebSocket.sendBinary(byte[])} - binary message sent</li>
 *   <li>{@code WebSocket.disconnect()} - connection close</li>
 *   <li>{@code ListenerManager.callOnTextMessage(WebSocket, String)} - text message received</li>
 *   <li>{@code ListenerManager.callOnBinaryMessage(WebSocket, byte[])} - binary message received</li>
 * </ul>
 */
public class NvWebSocketAdapter implements NetworkAdapter {

    private static final String NV_WEBSOCKET_CLASS = "com.neovisionaries.ws.client.WebSocket";

    @Override
    public String getId() {
        return "nv-websocket";
    }

    @Override
    public String getName() {
        return "NV-WebSocket";
    }

    @Override
    public AdapterType getType() {
        return AdapterType.WEBSOCKET;
    }

    @Override
    public boolean isAvailable() {
        try {
            Class.forName(NV_WEBSOCKET_CLASS);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public List<MethodHook> getHooks() {
        return Arrays.asList(
            new NvWebSocketConnectHook(),
            new NvWebSocketSendTextHook(),
            new NvWebSocketSendBinaryHook(),
            new NvWebSocketReceiveTextHook(),
            new NvWebSocketReceiveBinaryHook(),
            new NvWebSocketDisconnectHook()
        );
    }

    @Override
    public int getPriority() {
        return 70;
    }
}
