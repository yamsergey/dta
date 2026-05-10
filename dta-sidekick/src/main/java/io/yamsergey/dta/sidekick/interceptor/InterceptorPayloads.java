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
     * Reads back the request mutation. Fields are compared to the
     * Java-side originals; if everything matches, returns
     * {@link HttpRequestMutation#UNCHANGED} so the adapter skips the
     * OkHttp round-trip entirely.
     *
     * <p>Why field comparison and not {@code jsObj === original}: scripts
     * almost universally mutate the wrapper in place
     * ({@code req.headers["X"] = "..."; return req;}) — reference
     * equality wouldn't detect that. We check actual values instead.</p>
     *
     * <p>Why bother detecting unchanged at all: even when the script is
     * a pass-through, rebuilding the OkHttp Request via reflection
     * subtly changes things — Content-Type/Length canonicalization,
     * Authenticator interactions, BridgeInterceptor re-encoding. The
     * user's bug report: a script that only rewrote URLs matching one
     * pattern silently broke {@code POST /oauth/token} with 401
     * access_denied. Returning the original wrapper untouched should
     * be a true zero-touch path.</p>
     */
    public static HttpRequestMutation readHttpRequest(Object jsObj,
            String origUrl, String origMethod,
            Map<String, String> origHeaders, byte[] origBody) {
        if (jsObj == null) return HttpRequestMutation.DROP;
        if (!(jsObj instanceof NativeObject)) return HttpRequestMutation.UNCHANGED;
        NativeObject obj = (NativeObject) jsObj;

        String newUrl = stringField(obj, "url");
        String newMethod = stringField(obj, "method");
        Map<String, String> newHeaders = readHeaders(obj.get("headers"));
        byte[] newBody = readBody(obj.get("body"));

        if (java.util.Objects.equals(newUrl, origUrl)
                && java.util.Objects.equals(newMethod, origMethod)
                && headersEqual(newHeaders, origHeaders)
                && java.util.Arrays.equals(newBody, origBody)) {
            return HttpRequestMutation.UNCHANGED;
        }

        HttpRequestMutation m = new HttpRequestMutation();
        m.mutated = true;
        m.url = newUrl;
        m.method = newMethod;
        m.headers = newHeaders;
        m.body = newBody;
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

    /**
     * Builds the JS view of an HTTP response.
     *
     * <p>Top-level {@code url} and {@code method} expose what request the
     * response is for, so scripts can match by URL the same way they do
     * in {@code onRequest}: {@code if (resp.url.includes("/profile")) ...}.
     * Both are informational — {@link #readHttpResponse} ignores them on
     * the way back; the script can't redirect a response by mutating
     * {@code resp.url}.</p>
     *
     * <p>{@code requestPeek} is the corresponding request wrapper at
     * the moment the request went on the wire (URL, method, headers,
     * body). Available as {@code resp.request} when present. Useful for
     * scripts that want richer cross-correlation than URL alone (e.g.
     * inspect the request body to decide whether to mutate the
     * response).</p>
     */
    public static NativeObject httpResponseToJs(Context cx, Scriptable scope,
                                                String url, String method,
                                                int status, String statusMessage,
                                                Map<String, String> headers,
                                                byte[] body,
                                                NativeObject requestPeek) {
        NativeObject obj = new NativeObject();
        if (url != null) ScriptableObject.putProperty(obj, "url", url);
        if (method != null) ScriptableObject.putProperty(obj, "method", method);
        ScriptableObject.putProperty(obj, "status", status);
        if (statusMessage != null) ScriptableObject.putProperty(obj, "statusMessage", statusMessage);
        ScriptableObject.putProperty(obj, "headers", headersToJs(scope, headers));
        ScriptableObject.putProperty(obj, "body", bodyToJs(cx, scope, body));
        if (requestPeek != null) ScriptableObject.putProperty(obj, "request", requestPeek);
        return obj;
    }

    public static HttpResponseMutation readHttpResponse(Object jsObj,
            int origStatus, String origStatusMessage,
            Map<String, String> origHeaders, byte[] origBody) {
        if (jsObj == null) return HttpResponseMutation.DROP;
        if (!(jsObj instanceof NativeObject)) return HttpResponseMutation.UNCHANGED;
        NativeObject obj = (NativeObject) jsObj;

        int newStatus = -1;
        Object s = obj.get("status");
        if (s instanceof Number) newStatus = ((Number) s).intValue();
        String newStatusMessage = stringField(obj, "statusMessage");
        Map<String, String> newHeaders = readHeaders(obj.get("headers"));
        byte[] newBody = readBody(obj.get("body"));

        // Same no-op short-circuit as readHttpRequest — see that method's
        // doc for rationale.
        if (newStatus == origStatus
                && java.util.Objects.equals(newStatusMessage, origStatusMessage)
                && headersEqual(newHeaders, origHeaders)
                && java.util.Arrays.equals(newBody, origBody)) {
            return HttpResponseMutation.UNCHANGED;
        }

        HttpResponseMutation m = new HttpResponseMutation();
        m.mutated = true;
        m.status = newStatus;
        m.statusMessage = newStatusMessage;
        m.headers = newHeaders;
        m.body = newBody;
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

    public static WsFrameMutation readWsFrame(Object jsObj, String origText, byte[] origBinary) {
        if (jsObj == null) return WsFrameMutation.DROP;
        if (!(jsObj instanceof NativeObject)) return WsFrameMutation.UNCHANGED;
        NativeObject obj = (NativeObject) jsObj;

        String newText = null;
        Object t = obj.get("text");
        if (t != null && t != Undefined.instance && t != Scriptable.NOT_FOUND) newText = String.valueOf(t);
        byte[] newBinary = readBody(obj.get("binary"));

        if (java.util.Objects.equals(newText, origText)
                && java.util.Arrays.equals(newBinary, origBinary)) {
            return WsFrameMutation.UNCHANGED;
        }

        WsFrameMutation m = new WsFrameMutation();
        m.mutated = true;
        m.text = newText;
        m.binary = newBinary;
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

    /**
     * Compares two header maps for equality, treating null and empty as
     * the same. Order-insensitive (we use LinkedHashMap on the read-back
     * side but Java/OkHttp callers may pass any Map).
     */
    private static boolean headersEqual(Map<String, String> a, Map<String, String> b) {
        int aSize = a == null ? 0 : a.size();
        int bSize = b == null ? 0 : b.size();
        if (aSize != bSize) return false;
        if (aSize == 0) return true;
        for (Map.Entry<String, String> e : a.entrySet()) {
            if (!java.util.Objects.equals(b.get(e.getKey()), e.getValue())) return false;
        }
        return true;
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
