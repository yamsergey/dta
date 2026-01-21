package io.yamsergey.dta.sidekick.network.adapter.websocket;

import io.yamsergey.dta.sidekick.SidekickLog;

import io.yamsergey.dta.sidekick.jvmti.MethodHook;
import io.yamsergey.dta.sidekick.network.WebSocketConnection;
import io.yamsergey.dta.sidekick.network.WebSocketInspector;

/**
 * JVMTI hook for Java-WebSocket onOpen callback.
 *
 * <p>Hooks into {@code WebSocketClient.onWebsocketOpen(WebSocket, Handshakedata)} which is
 * called internally by the library when the connection is established. This is preferred
 * over hooking {@code onOpen(ServerHandshake)} since that's an abstract method that gets
 * overridden by user code in anonymous classes.</p>
 */
public class JavaWebSocketOnOpenHook implements MethodHook {

    private static final String TAG = "JavaWsOnOpenHook";

    @Override
    public String getTargetClass() {
        return "org.java_websocket.client.WebSocketClient";
    }

    @Override
    public String getTargetMethod() {
        return "onWebsocketOpen";
    }

    @Override
    public String getMethodSignature() {
        return "(Lorg/java_websocket/WebSocket;Lorg/java_websocket/handshake/Handshakedata;)V";
    }

    @Override
    public String getId() {
        return "java-websocket-onopen";
    }

    @Override
    public void onEnter(Object thisObj, Object[] args) {
        try {
            WebSocketConnection conn = WebSocketInspector.getConnectionForObject(thisObj);
            if (conn != null) {
                conn.markConnected();
                WebSocketInspector.onConnectionOpened(conn);
                SidekickLog.i(TAG, "WebSocket connected: " + conn.getUrl());
            } else {
                SidekickLog.w(TAG, "No connection found for WebSocket object");
            }
        } catch (Throwable t) {
            SidekickLog.e(TAG, "Error in onEnter", t);
        }
    }

    @Override
    public Object onExit(Object thisObj, Object result) {
        return result;
    }

    @Override
    public Throwable onException(Object thisObj, Throwable throwable) {
        return throwable;
    }
}
