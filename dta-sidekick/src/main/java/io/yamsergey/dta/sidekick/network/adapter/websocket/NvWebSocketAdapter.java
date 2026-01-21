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
 * {@code com.neovisionaries.ws.client.WebSocket} class.</p>
 *
 * <p>Hook points:</p>
 * <ul>
 *   <li>{@code connect()} - connection establishment</li>
 *   <li>{@code sendText(String)} - text message sent</li>
 *   <li>{@code sendBinary(byte[])} - binary message sent</li>
 *   <li>{@code disconnect()} - connection close</li>
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
            new NvWebSocketDisconnectHook()
        );
    }

    @Override
    public int getPriority() {
        return 70;
    }
}
