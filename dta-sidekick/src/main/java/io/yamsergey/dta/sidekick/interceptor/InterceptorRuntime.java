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

    /**
     * Where the persisted script lives on disk (one file per app, since
     * sidekick runs inside the host app's process and {@code filesDir} is
     * already per-app). Set by {@link #setPersistenceFile(java.io.File)}
     * during sidekick init. Null when persistence is disabled or hasn't
     * been wired yet — the runtime stays fully functional, just doesn't
     * survive process restart.
     */
    private volatile java.io.File persistenceFile;

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
     * Configures the persistence file. Called once from sidekick init.
     * After this returns, every {@link #install} writes the source to
     * disk and {@link #clear} deletes it; on the next process launch,
     * call {@link #autoReinstallFromDisk} to pick up the persisted
     * script automatically.
     */
    public void setPersistenceFile(java.io.File file) {
        this.persistenceFile = file;
    }

    /**
     * Reads the persisted script (if any) and installs it. Compile
     * errors are caught and recorded to the ring buffer — sidekick
     * stays running normally. The persisted file is left on disk so
     * the developer can fix and re-install via MCP.
     *
     * <p>Logs an explicit "auto-reinstalled from disk" entry so users
     * see at a glance that a persisted script ran on this launch
     * (versus an MCP-driven install that would normally produce the
     * "interceptor installed (N chars)" entry).</p>
     */
    public synchronized void autoReinstallFromDisk() {
        java.io.File file = persistenceFile;
        if (file == null || !file.exists()) return;
        String source;
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            source = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            SidekickLog.w(TAG, "Failed to read persisted interceptor: " + e.getMessage());
            return;
        }
        if (source.isEmpty()) return;
        try {
            installInternal(source, /* persist */ false);
            String version = io.yamsergey.dta.sidekick.SidekickVersion.get();
            log.log("interceptor auto-reinstalled from disk (" + source.length() + " chars) [sidekick=" + version + "]");
            SidekickLog.i(TAG, "interceptor auto-reinstalled from disk: " + source.length() + " chars");
        } catch (IllegalArgumentException e) {
            log.error("interceptor auto-reinstall failed: " + e.getMessage());
            SidekickLog.w(TAG, "interceptor auto-reinstall failed: " + e.getMessage());
        }
    }

    /**
     * Installs (or replaces) the script. Compile errors are thrown so
     * the caller (the sidekick HTTP endpoint, then MCP) can surface
     * them directly to the agent. The script is also persisted to
     * {@link #persistenceFile} so it survives app process restart;
     * a compile failure does not write to disk.
     */
    public synchronized void install(String source) {
        installInternal(source, /* persist */ true);
    }

    private void installInternal(String source, boolean persist) {
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
            if (persist) {
                // Only the explicit-install path writes to disk. The
                // auto-reinstall path skips this — the script is already
                // on disk and re-writing would be redundant — and emits
                // its own log line so users see "auto-reinstalled"
                // distinct from "installed".
                writePersisted(source);
                // Tag the install entry with the sidekick AAR version.
                // Agents can spot version-mismatch failures (old AAR, new
                // MCP) at a glance — they show up here rather than as
                // opaque crashes.
                String version = io.yamsergey.dta.sidekick.SidekickVersion.get();
                log.log("interceptor installed (" + source.length() + " chars) [sidekick=" + version + "]");
                SidekickLog.i(TAG, "interceptor script installed: " + source.length() + " chars (sidekick " + version + ")");
            }
        } catch (RhinoException e) {
            String msg = "compile error: " + e.details() + " @ " + e.lineNumber() + ":" + e.columnNumber();
            log.error(msg);
            throw new IllegalArgumentException(msg, e);
        } finally {
            Context.exit();
        }
    }

    private void writePersisted(String source) {
        java.io.File file = persistenceFile;
        if (file == null) return;
        try {
            java.io.File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            java.nio.file.Files.write(file.toPath(),
                source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            // Persist failure isn't fatal — the runtime still has the
            // script in memory for this process. Log so the developer
            // notices it won't survive a restart.
            log.error("interceptor persist failed: " + e.getMessage());
            SidekickLog.w(TAG, "interceptor persist failed: " + e.getMessage());
        }
    }

    private void deletePersisted() {
        java.io.File file = persistenceFile;
        if (file == null) return;
        try { file.delete(); } catch (Exception ignored) {}
    }

    public synchronized void clear() {
        if (installed != null) {
            installed = null;
            log.log("interceptor cleared");
            SidekickLog.i(TAG, "interceptor cleared");
        }
        // Always delete the persisted file even if nothing was loaded
        // in memory — covers the case where an auto-reinstall is
        // pending (process restart between install and clear) or the
        // file is leftover from a prior bad state.
        deletePersisted();
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
            return InterceptorPayloads.readHttpRequest(result, url, method, headers, body);
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
            return InterceptorPayloads.readHttpResponse(result, status, statusMessage, headers, body);
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
            return InterceptorPayloads.readWsFrame(result, text, binary);
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
