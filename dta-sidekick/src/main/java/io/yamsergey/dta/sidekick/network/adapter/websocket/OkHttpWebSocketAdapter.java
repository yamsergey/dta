package io.yamsergey.dta.sidekick.network.adapter.websocket;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import io.yamsergey.dta.sidekick.jvmti.MethodHook;
import io.yamsergey.dta.sidekick.network.adapter.AdapterType;
import io.yamsergey.dta.sidekick.network.adapter.NetworkAdapter;

/**
 * Network adapter for OkHttp WebSocket connections.
 *
 * <p>Intercepts WebSocket connections and messages via OkHttp's
 * {@code okhttp3.internal.ws.RealWebSocket} class.</p>
 *
 * <p>Hook points:</p>
 * <ul>
 *   <li>{@code connect()} - connection establishment</li>
 *   <li>{@code send(String)} - text message sent</li>
 *   <li>{@code send(ByteString)} - binary message sent</li>
 *   <li>{@code onReadMessage(String)} - text message received</li>
 *   <li>{@code onReadMessage(ByteString)} - binary message received</li>
 *   <li>{@code close()} - connection close</li>
 * </ul>
 */
public class OkHttpWebSocketAdapter implements NetworkAdapter {

    private static final String OKHTTP_WEBSOCKET_CLASS = "okhttp3.internal.ws.RealWebSocket";

    @Override
    public String getId() {
        return "okhttp-websocket";
    }

    @Override
    public String getName() {
        return "OkHttp WebSocket";
    }

    @Override
    public AdapterType getType() {
        return AdapterType.WEBSOCKET;
    }

    @Override
    public boolean isAvailable() {
        try {
            Class.forName(OKHTTP_WEBSOCKET_CLASS);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public List<MethodHook> getHooks() {
        return Arrays.asList(
            new OkHttpWebSocketConnectHook(),
            new OkHttpWebSocketSendTextHook(),
            new OkHttpWebSocketSendBinaryHook(),
            new OkHttpWebSocketReceiveTextHook(),
            new OkHttpWebSocketReceiveBinaryHook(),
            new OkHttpWebSocketCloseHook()
        );
    }

    @Override
    public int getPriority() {
        return 90;
    }
}
