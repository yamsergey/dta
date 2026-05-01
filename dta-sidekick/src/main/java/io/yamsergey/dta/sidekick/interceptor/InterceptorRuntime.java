package io.yamsergey.dta.sidekick.interceptor;

import io.yamsergey.dta.sidekick.SidekickLog;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

/**
 * Singleton holding the active interceptor JS script + its scope and
 * log. Network adapter callsites (OkHttp, URLConnection, WebSocket)
 * invoke {@link #onRequest}, {@link #onResponse}, {@link #onWsSend},
 * {@link #onWsReceive} synchronously; the runtime evaluates the
 * corresponding top-level JS function and returns its result.
 *
 * <p>Phase 1 wires the runtime + sidekick endpoints; the actual hook
 * callsites land in Phase 2/3. Until then, {@link #isInstalled} is the
 * cheap gate the adapters will check before bothering to marshal
 * objects into JS.</p>
 *
 * <h3>Threading</h3>
 *
 * Hook handlers run on whatever thread invoked the network adapter —
 * that's the contract (sync) the user signed off on. Rhino requires
 * the {@link Context} to be entered on the calling thread, which we
 * do per-call. {@link #install(String)} replaces the script atomically;
 * in-flight calls finish on whatever script they captured at entry,
 * new calls hit the new script.
 *
 * <h3>Errors</h3>
 *
 * Any throwable from the script — compile error in {@link #install},
 * runtime exception in a handler — is captured to {@link ScriptLog}
 * and the original payload passes through unchanged. The user has
 * explicitly asked us not to cap or sandbox anything else.
 */
public final class InterceptorRuntime {

    private static final String TAG = "Interceptor";

    private static final InterceptorRuntime INSTANCE = new InterceptorRuntime();

    private final ScriptLog log = new ScriptLog();

    /** Holds the currently-installed script and its scope. {@code null} when nothing is installed. */
    private volatile InstalledScript installed;

    private InterceptorRuntime() {}

    public static InterceptorRuntime getInstance() {
        return INSTANCE;
    }

    public ScriptLog logs() {
        return log;
    }

    public boolean isInstalled() {
        return installed != null;
    }

    /**
     * Installs (or replaces) the script. Compile errors are thrown so
     * the caller (the sidekick HTTP endpoint, then MCP) can surface
     * them directly to the agent.
     */
    public synchronized void install(String source) {
        if (source == null || source.trim().isEmpty()) {
            throw new IllegalArgumentException("script source is empty");
        }
        Context cx = Context.enter();
        try {
            // Disable optimisation — Android lacks the bytecode generator
            // Rhino's JIT relies on. Interpreter mode is ABI-compatible.
            cx.setOptimizationLevel(-1);
            cx.setLanguageVersion(Context.VERSION_ES6);

            ScriptScope scopeBuilder = new ScriptScope(log);
            ScriptableObject scope = scopeBuilder.build(cx);
            Script compiled = cx.compileString(source, "interceptor.js", 1, null);
            // Evaluate top-level so handler functions become bindings on the scope.
            compiled.exec(cx, scope);

            this.installed = new InstalledScript(source, scopeBuilder, scope);
            log.log("interceptor installed (" + source.length() + " chars)");
            SidekickLog.i(TAG, "interceptor script installed: " + source.length() + " chars");
        } catch (RhinoException e) {
            String msg = "compile error: " + e.details() + " @ " + e.lineNumber() + ":" + e.columnNumber();
            log.error(msg);
            throw new IllegalArgumentException(msg, e);
        } finally {
            Context.exit();
        }
    }

    public synchronized void clear() {
        if (installed != null) {
            installed = null;
            log.log("interceptor cleared");
            SidekickLog.i(TAG, "interceptor cleared");
        }
    }

    public String getSource() {
        InstalledScript s = installed;
        return s == null ? null : s.source;
    }

    // ----------------------------------------------------------------
    // Hook entry points (Phase 1: wired but not yet called from
    // adapters — those land in Phase 2/3). Each delegates to a JS
    // function of the same name on the scope; missing function = the
    // payload passes through unchanged.
    // ----------------------------------------------------------------

    public Object onRequest(Object req)     { return invoke("onRequest", req); }
    public Object onResponse(Object resp)   { return invoke("onResponse", resp); }
    public Object onWsSend(Object frame)    { return invoke("onWsSend", frame); }
    public Object onWsReceive(Object frame) { return invoke("onWsReceive", frame); }

    // ----------------------------------------------------------------
    // Typed entry points (used by network adapters) — all argument
    // marshalling and Rhino-specific wrangling happens here so the
    // adapters stay engine-agnostic.
    // ----------------------------------------------------------------

    /**
     * Runs the script's {@code onRequest} hook against an outbound
     * HTTP request. Returns the mutation to apply, with
     * {@link InterceptorPayloads.HttpRequestMutation#UNCHANGED} when no
     * script is installed or the script returned the original.
     */
    public InterceptorPayloads.HttpRequestMutation interceptHttpRequest(
            String url, String method,
            java.util.Map<String, String> headers,
            byte[] body, String tag) {
        InstalledScript s = installed;
        if (s == null) return InterceptorPayloads.HttpRequestMutation.UNCHANGED;
        Context cx = Context.enter();
        try {
            cx.setOptimizationLevel(-1);
            Object fn = ScriptableObject.getProperty(s.scope, "onRequest");
            if (!(fn instanceof Function)) return InterceptorPayloads.HttpRequestMutation.UNCHANGED;
            Object payload = InterceptorPayloads.httpRequestToJs(cx, s.scope, url, method, headers, body, tag);
            Function f = (Function) fn;
            Object result = f.call(cx, s.scope, s.scope, new Object[]{ payload });
            if (result == Undefined.instance) return InterceptorPayloads.HttpRequestMutation.UNCHANGED;
            return InterceptorPayloads.readHttpRequest(result, payload);
        } catch (Throwable t) {
            captureError("onRequest", t);
            return InterceptorPayloads.HttpRequestMutation.UNCHANGED;
        } finally {
            Context.exit();
        }
    }

    /**
     * Runs the script's {@code onResponse} hook. {@code requestPeek}
     * may be null — supplied when the adapter has the corresponding
     * outbound request still on hand for the script's reference.
     */
    public InterceptorPayloads.HttpResponseMutation interceptHttpResponse(
            int status, String statusMessage,
            java.util.Map<String, String> headers,
            byte[] body) {
        InstalledScript s = installed;
        if (s == null) return InterceptorPayloads.HttpResponseMutation.UNCHANGED;
        Context cx = Context.enter();
        try {
            cx.setOptimizationLevel(-1);
            Object fn = ScriptableObject.getProperty(s.scope, "onResponse");
            if (!(fn instanceof Function)) return InterceptorPayloads.HttpResponseMutation.UNCHANGED;
            Object payload = InterceptorPayloads.httpResponseToJs(cx, s.scope, status, statusMessage, headers, body, null);
            Function f = (Function) fn;
            Object result = f.call(cx, s.scope, s.scope, new Object[]{ payload });
            if (result == Undefined.instance) return InterceptorPayloads.HttpResponseMutation.UNCHANGED;
            return InterceptorPayloads.readHttpResponse(result, payload);
        } catch (Throwable t) {
            captureError("onResponse", t);
            return InterceptorPayloads.HttpResponseMutation.UNCHANGED;
        } finally {
            Context.exit();
        }
    }

    public InterceptorPayloads.WsFrameMutation interceptWsSend(
            String text, byte[] binary, String connectionId) {
        return interceptWsFrame("onWsSend", InterceptorPayloads.TAG_WS_SEND,
                text, binary, connectionId);
    }

    public InterceptorPayloads.WsFrameMutation interceptWsReceive(
            String text, byte[] binary, String connectionId) {
        return interceptWsFrame("onWsReceive", InterceptorPayloads.TAG_WS_RECV,
                text, binary, connectionId);
    }

    private InterceptorPayloads.WsFrameMutation interceptWsFrame(
            String fnName, String dir, String text, byte[] binary, String connectionId) {
        InstalledScript s = installed;
        if (s == null) return InterceptorPayloads.WsFrameMutation.UNCHANGED;
        Context cx = Context.enter();
        try {
            cx.setOptimizationLevel(-1);
            Object fn = ScriptableObject.getProperty(s.scope, fnName);
            if (!(fn instanceof Function)) return InterceptorPayloads.WsFrameMutation.UNCHANGED;
            Object payload = InterceptorPayloads.wsFrameToJs(cx, s.scope, dir, text, binary, connectionId);
            Function f = (Function) fn;
            Object result = f.call(cx, s.scope, s.scope, new Object[]{ payload });
            if (result == Undefined.instance) return InterceptorPayloads.WsFrameMutation.UNCHANGED;
            return InterceptorPayloads.readWsFrame(result, payload);
        } catch (Throwable t) {
            captureError(fnName, t);
            return InterceptorPayloads.WsFrameMutation.UNCHANGED;
        } finally {
            Context.exit();
        }
    }

    private void captureError(String fnName, Throwable t) {
        String stack = t instanceof RhinoException
            ? ((RhinoException) t).getScriptStackTrace()
            : t.toString();
        log.error(fnName + " threw: " + t.getMessage() + "\n" + stack);
        SidekickLog.w(TAG, fnName + " threw: " + t.getMessage());
    }

    private Object invoke(String functionName, Object payload) {
        InstalledScript s = installed;
        if (s == null) return payload;

        Context cx = Context.enter();
        try {
            cx.setOptimizationLevel(-1);
            Object fn = ScriptableObject.getProperty(s.scope, functionName);
            if (!(fn instanceof Function)) return payload;
            Function f = (Function) fn;
            Object result = f.call(cx, s.scope, s.scope, new Object[]{ payload });
            // Pass-through semantics: if script returns undefined, treat
            // as "no change". Returning null is the explicit drop signal.
            if (result == Undefined.instance) return payload;
            return result;
        } catch (Throwable t) {
            String stack = t instanceof RhinoException
                ? ((RhinoException) t).getScriptStackTrace()
                : t.toString();
            log.error(functionName + " threw: " + t.getMessage() + "\n" + stack);
            SidekickLog.w(TAG, functionName + " threw: " + t.getMessage());
            return payload;
        } finally {
            Context.exit();
        }
    }

    private static final class InstalledScript {
        final String source;
        @SuppressWarnings("unused") final ScriptScope scopeBuilder; // keeps state map alive
        final Scriptable scope;
        InstalledScript(String source, ScriptScope scopeBuilder, Scriptable scope) {
            this.source = source;
            this.scopeBuilder = scopeBuilder;
            this.scope = scope;
        }
    }
}
