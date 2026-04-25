package io.yamsergey.dta.sidekick.network.adapter.websocket;

import io.yamsergey.dta.sidekick.SidekickLog;
import io.yamsergey.dta.sidekick.jvmti.MethodHook;
import io.yamsergey.dta.sidekick.network.WebSocketConnection;
import io.yamsergey.dta.sidekick.network.WebSocketInspector;

/**
 * JVMTI hook for OkHttp WebSocket failure.
 *
 * <p>Hooks into {@code RealWebSocket.failWebSocket(Exception, Response)} to capture
 * connections that terminate abnormally — network errors, server-side failures, or
 * abrupt disconnects that don't go through the normal close handshake.</p>
 *
 * <p>Without this hook, failed connections remain stuck in OPEN state in the daemon
 * even after the underlying socket is dead, because {@code close()} is never called
 * on the failure path.</p>
 */
public class OkHttpWebSocketFailureHook implements MethodHook {

    private static final String TAG = "OkHttpWsFailureHook";

    @Override
    public String getTargetClass() {
        return "okhttp3.internal.ws.RealWebSocket";
    }

    @Override
    public String getTargetMethod() {
        return "failWebSocket";
    }

    @Override
    public String getMethodSignature() {
        // OkHttp 4.x: failWebSocket(Exception e, Response response)
        return "(Ljava/lang/Exception;Lokhttp3/Response;)V";
    }

    @Override
    public String getId() {
        return "okhttp-websocket-failure";
    }

    @Override
    public void onEnter(Object thisObj, Object[] args) {
        try {
            String errorMessage = "Unknown failure";
            if (args != null && args.length >= 1 && args[0] instanceof Throwable) {
                Throwable t = (Throwable) args[0];
                String msg = t.getMessage();
                errorMessage = msg != null ? msg : t.getClass().getSimpleName();
            }

            WebSocketConnection conn = WebSocketInspector.getConnectionForObject(thisObj);
            if (conn != null) {
                conn.markFailed(errorMessage);
                WebSocketInspector.onConnectionClosed(conn);
                WebSocketInspector.unmapObject(thisObj);
                SidekickLog.i(TAG, "WebSocket failed: " + errorMessage);
            }
        } catch (Throwable t) {
            SidekickLog.e(TAG, "Error in onEnter", t);
        }
    }
}
