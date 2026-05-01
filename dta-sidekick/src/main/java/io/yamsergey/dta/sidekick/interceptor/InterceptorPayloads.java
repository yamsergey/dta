package io.yamsergey.dta.sidekick.interceptor;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the JavaScript-side view of HTTP requests, responses, and
 * WebSocket frames before each call into the interceptor script, and
 * reads back any mutations afterwards.
 *
 * <p>Adapters work in plain Java types (URL strings, header maps,
 * byte arrays); this class is the only place that knows about Rhino
 * objects. Every shape stays a flat {@link NativeObject} — JS
 * authors get plain property access, no opaque host objects.</p>
 *
 * <p>Conventions:
 * <ul>
 *   <li>{@code body} / {@code text} / {@code binary} are read back as
 *       JS strings, byte arrays, or {@code null}; the adapter caller
 *       decides what to do with each.</li>
 *   <li>{@code headers} is a JS object with case-preserved string keys
 *       and string values. Multi-valued headers collapse to comma-
 *       separated values on the way in; on the way out we also accept
 *       arrays of strings.</li>
 *   <li>Returning {@code null} from the script signals "drop".
 *       Returning {@code undefined} (i.e. forgetting to {@code return})
 *       means "no change" — handled in {@link InterceptorRuntime}.</li>
 * </ul></p>
 */
public final class InterceptorPayloads {

    public static final String TAG_OKHTTP   = "okhttp";
    public static final String TAG_URLCONN  = "urlconn";
    public static final String TAG_WS_SEND  = "send";
    public static final String TAG_WS_RECV  = "recv";

    private InterceptorPayloads() {}

    // ----------------------------------------------------------------
    // HTTP request
    // ----------------------------------------------------------------

    public static NativeObject httpRequestToJs(Context cx, Scriptable scope,
                                               String url, String method,
                                               Map<String, String> headers,
                                               byte[] body, String tag) {
        NativeObject obj = new NativeObject();
        ScriptableObject.putProperty(obj, "url", url);
        ScriptableObject.putProperty(obj, "method", method);
        ScriptableObject.putProperty(obj, "headers", headersToJs(scope, headers));
        ScriptableObject.putProperty(obj, "body", bodyToJs(cx, scope, body));
        ScriptableObject.putProperty(obj, "tag", tag);
        return obj;
    }

    /**
     * Reads back the request mutation. We do <b>not</b> short-circuit on
     * {@code jsObj === original} — JS scripts almost universally mutate
     * the passed-in object in place ({@code req.headers["X"] = "..."; return req;})
     * and that mutation is invisible at the reference level. Always read
     * the fields and let the adapter compare to decide what to rebuild.
     */
    public static HttpRequestMutation readHttpRequest(Object jsObj, Object original) {
        if (jsObj == null) return HttpRequestMutation.DROP;
        if (!(jsObj instanceof NativeObject)) return HttpRequestMutation.UNCHANGED;
        NativeObject obj = (NativeObject) jsObj;
        HttpRequestMutation m = new HttpRequestMutation();
        m.mutated = true;
        m.url = stringField(obj, "url");
        m.method = stringField(obj, "method");
        m.headers = readHeaders(obj.get("headers"));
        m.body = readBody(obj.get("body"));
        return m;
    }

    public static final class HttpRequestMutation {
        public static final HttpRequestMutation DROP = new HttpRequestMutation();
        public static final HttpRequestMutation UNCHANGED = new HttpRequestMutation();
        static {
            DROP.dropped = true;
            UNCHANGED.mutated = false;
        }
        public boolean mutated;
        public boolean dropped;
        public String url;
        public String method;
        public Map<String, String> headers;
        public byte[] body;
    }

    // ----------------------------------------------------------------
    // HTTP response
    // ----------------------------------------------------------------

    public static NativeObject httpResponseToJs(Context cx, Scriptable scope,
                                                int status, String statusMessage,
                                                Map<String, String> headers,
                                                byte[] body,
                                                NativeObject requestPeek) {
        NativeObject obj = new NativeObject();
        ScriptableObject.putProperty(obj, "status", status);
        if (statusMessage != null) ScriptableObject.putProperty(obj, "statusMessage", statusMessage);
        ScriptableObject.putProperty(obj, "headers", headersToJs(scope, headers));
        ScriptableObject.putProperty(obj, "body", bodyToJs(cx, scope, body));
        if (requestPeek != null) ScriptableObject.putProperty(obj, "request", requestPeek);
        return obj;
    }

    public static HttpResponseMutation readHttpResponse(Object jsObj, Object original) {
        if (jsObj == null) return HttpResponseMutation.DROP;
        if (!(jsObj instanceof NativeObject)) return HttpResponseMutation.UNCHANGED;
        NativeObject obj = (NativeObject) jsObj;
        HttpResponseMutation m = new HttpResponseMutation();
        m.mutated = true;
        Object s = obj.get("status");
        if (s instanceof Number) m.status = ((Number) s).intValue();
        m.statusMessage = stringField(obj, "statusMessage");
        m.headers = readHeaders(obj.get("headers"));
        m.body = readBody(obj.get("body"));
        return m;
    }

    public static final class HttpResponseMutation {
        public static final HttpResponseMutation DROP = new HttpResponseMutation();
        public static final HttpResponseMutation UNCHANGED = new HttpResponseMutation();
        static {
            DROP.dropped = true;
            UNCHANGED.mutated = false;
        }
        public boolean mutated;
        public boolean dropped;
        public int status = -1;
        public String statusMessage;
        public Map<String, String> headers;
        public byte[] body;
    }

    // ----------------------------------------------------------------
    // WebSocket frame
    // ----------------------------------------------------------------

    public static NativeObject wsFrameToJs(Context cx, Scriptable scope,
                                           String dir, String text, byte[] binary,
                                           String connectionId) {
        NativeObject obj = new NativeObject();
        ScriptableObject.putProperty(obj, "dir", dir);
        ScriptableObject.putProperty(obj, "text", text == null ? null : text);
        ScriptableObject.putProperty(obj, "binary", binary == null ? null : bytesToJs(cx, scope, binary));
        if (connectionId != null) ScriptableObject.putProperty(obj, "connectionId", connectionId);
        return obj;
    }

    public static WsFrameMutation readWsFrame(Object jsObj, Object original) {
        if (jsObj == null) return WsFrameMutation.DROP;
        if (!(jsObj instanceof NativeObject)) return WsFrameMutation.UNCHANGED;
        NativeObject obj = (NativeObject) jsObj;
        WsFrameMutation m = new WsFrameMutation();
        m.mutated = true;
        Object t = obj.get("text");
        if (t != null && t != Undefined.instance && t != Scriptable.NOT_FOUND) m.text = String.valueOf(t);
        m.binary = readBody(obj.get("binary"));
        return m;
    }

    public static final class WsFrameMutation {
        public static final WsFrameMutation DROP = new WsFrameMutation();
        public static final WsFrameMutation UNCHANGED = new WsFrameMutation();
        static {
            DROP.dropped = true;
            UNCHANGED.mutated = false;
        }
        public boolean mutated;
        public boolean dropped;
        public String text;
        public byte[] binary;
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private static NativeObject headersToJs(Scriptable scope, Map<String, String> headers) {
        NativeObject obj = new NativeObject();
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() == null) continue;
                ScriptableObject.putProperty(obj, e.getKey(), e.getValue() == null ? "" : e.getValue());
            }
        }
        return obj;
    }

    /**
     * JS-string body view: tries UTF-8 decode for body bytes, falls
     * back to a Uint8Array-like NativeArray of byte ints. Keeping it
     * simple for v1 — text is the common case; binary scripts can
     * read from the array.
     */
    private static Object bodyToJs(Context cx, Scriptable scope, byte[] body) {
        if (body == null) return null;
        if (body.length == 0) return "";
        return new String(body, StandardCharsets.UTF_8);
    }

    private static Object bytesToJs(Context cx, Scriptable scope, byte[] body) {
        if (body == null) return null;
        Object[] arr = new Object[body.length];
        for (int i = 0; i < body.length; i++) arr[i] = ((int) body[i]) & 0xff;
        return cx.newArray(scope, arr);
    }

    private static String stringField(NativeObject obj, String key) {
        Object v = obj.get(key);
        if (v == null || v == Undefined.instance || v == Scriptable.NOT_FOUND) return null;
        return String.valueOf(v);
    }

    private static Map<String, String> readHeaders(Object raw) {
        if (raw == null || raw == Undefined.instance || raw == Scriptable.NOT_FOUND) return null;
        if (!(raw instanceof Scriptable)) return null;
        Scriptable obj = (Scriptable) raw;
        Map<String, String> out = new LinkedHashMap<>();
        for (Object idObj : obj.getIds()) {
            String id = String.valueOf(idObj);
            Object v = ScriptableObject.getProperty(obj, id);
            if (v == null || v == Undefined.instance || v == Scriptable.NOT_FOUND) continue;
            if (v instanceof NativeArray) {
                NativeArray arr = (NativeArray) v;
                List<String> parts = new ArrayList<>();
                for (int i = 0; i < arr.size(); i++) {
                    Object el = arr.get(i);
                    if (el != null && el != Undefined.instance) parts.add(String.valueOf(el));
                }
                out.put(id, String.join(", ", parts));
            } else {
                out.put(id, String.valueOf(v));
            }
        }
        return out;
    }

    private static byte[] readBody(Object raw) {
        if (raw == null) return null;
        if (raw == Undefined.instance || raw == Scriptable.NOT_FOUND) return null;
        if (raw instanceof String) return ((String) raw).getBytes(StandardCharsets.UTF_8);
        if (raw instanceof byte[]) return (byte[]) raw;
        if (raw instanceof NativeArray) {
            NativeArray arr = (NativeArray) raw;
            byte[] out = new byte[(int) arr.size()];
            for (int i = 0; i < out.length; i++) {
                Object el = arr.get(i);
                if (el instanceof Number) out[i] = ((Number) el).byteValue();
            }
            return out;
        }
        return null;
    }
}
