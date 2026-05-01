package io.yamsergey.dta.sidekick.interceptor;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Constructs the Rhino {@link ScriptableObject} the user's interceptor
 * script runs in. Wires up the three primitives the agent always has:
 *
 * <ul>
 *   <li>{@code log(...args)} — appends a stringified line to {@link ScriptLog}.</li>
 *   <li>{@code state} — a JS object whose keys persist across hook
 *       invocations within a single {@code interceptor_set} install.
 *       Cleared when the script is replaced or removed.</li>
 *   <li>{@code sleep(ms)} — synchronously blocks the calling app
 *       thread. Yes, fully blocking; that's the contract — interceptors
 *       are sync, and users have explicitly asked for unrestricted
 *       latency control.</li>
 * </ul>
 *
 * <p>Phase 1 wires only these helpers. Request/Response/Frame
 * marshalling lands when the network adapters are wired in Phase 2/3.</p>
 */
public final class ScriptScope {

    private final ScriptLog log;
    private final ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();

    public ScriptScope(ScriptLog log) {
        this.log = log;
    }

    /** Builds and returns a fresh top-level scope. Must be called inside an active Rhino {@link Context}. */
    public ScriptableObject build(Context cx) {
        ScriptableObject scope = cx.initStandardObjects();
        ScriptableObject.putProperty(scope, "log", new LogFn(log));
        ScriptableObject.putProperty(scope, "state", buildStateObject(scope));
        ScriptableObject.putProperty(scope, "sleep", new SleepFn());
        return scope;
    }

    public ConcurrentHashMap<String, Object> stateMap() {
        return state;
    }

    private ScriptableObject buildStateObject(ScriptableObject scope) {
        NativeObject obj = new NativeObject();
        ScriptableObject.putProperty(obj, "get", new BaseFunction() {
            @Override public Object call(Context cx, org.mozilla.javascript.Scriptable s, org.mozilla.javascript.Scriptable t, Object[] args) {
                if (args.length < 1) return Undefined.instance;
                Object v = state.get(String.valueOf(args[0]));
                return v == null ? Undefined.instance : v;
            }
        });
        ScriptableObject.putProperty(obj, "set", new BaseFunction() {
            @Override public Object call(Context cx, org.mozilla.javascript.Scriptable s, org.mozilla.javascript.Scriptable t, Object[] args) {
                if (args.length < 2) return Undefined.instance;
                state.put(String.valueOf(args[0]), args[1]);
                return Undefined.instance;
            }
        });
        ScriptableObject.putProperty(obj, "delete", new BaseFunction() {
            @Override public Object call(Context cx, org.mozilla.javascript.Scriptable s, org.mozilla.javascript.Scriptable t, Object[] args) {
                if (args.length >= 1) state.remove(String.valueOf(args[0]));
                return Undefined.instance;
            }
        });
        ScriptableObject.putProperty(obj, "clear", new BaseFunction() {
            @Override public Object call(Context cx, org.mozilla.javascript.Scriptable s, org.mozilla.javascript.Scriptable t, Object[] args) {
                state.clear();
                return Undefined.instance;
            }
        });
        return obj;
    }

    /** {@code log(...args)} — stringifies each arg, joins with single space. */
    private static final class LogFn extends BaseFunction {
        private final ScriptLog out;
        LogFn(ScriptLog out) { this.out = out; }
        @Override
        public Object call(Context cx, org.mozilla.javascript.Scriptable s, org.mozilla.javascript.Scriptable t, Object[] args) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(' ');
                sb.append(stringify(args[i]));
            }
            out.log(sb.toString());
            return Undefined.instance;
        }
        private static String stringify(Object v) {
            if (v == null || v == Undefined.instance) return "undefined";
            if (v instanceof org.mozilla.javascript.Scriptable) {
                org.mozilla.javascript.Scriptable s = (org.mozilla.javascript.Scriptable) v;
                try {
                    Object json = org.mozilla.javascript.NativeJSON.stringify(
                            Context.getCurrentContext(), s.getParentScope() != null ? s.getParentScope() : s,
                            s, null, "");
                    return json == null ? String.valueOf(v) : json.toString();
                } catch (Throwable ignored) {
                    return String.valueOf(v);
                }
            }
            return String.valueOf(v);
        }
    }

    /** {@code sleep(ms)} — blocks the current thread. */
    private static final class SleepFn extends BaseFunction {
        @Override
        public Object call(Context cx, org.mozilla.javascript.Scriptable s, org.mozilla.javascript.Scriptable t, Object[] args) {
            if (args.length < 1) return Undefined.instance;
            long ms = (long) Context.toNumber(args[0]);
            if (ms > 0) {
                try {
                    Thread.sleep(ms);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return Undefined.instance;
        }
    }

    @SuppressWarnings("unused")
    public Function noopHandler() {
        return new BaseFunction() {
            @Override public Object call(Context cx, org.mozilla.javascript.Scriptable s, org.mozilla.javascript.Scriptable t, Object[] args) {
                return args.length > 0 ? args[0] : Undefined.instance;
            }
        };
    }
}
