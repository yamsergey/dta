package io.yamsergey.dta.sidekick.interceptor;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pins the shape of the {@code resp} object passed to {@code onResponse}.
 *
 * <p>Regression target: a real bug where {@code resp.url} was undefined,
 * scripts that called {@code resp.url.indexOf("/...")} threw a TypeError,
 * the runtime caught the throw silently (response passed through
 * unchanged), and the only signal was a buried entry in the interceptor
 * ring buffer. Now {@code resp.url}, {@code resp.method}, and a full
 * {@code resp.request} peek are populated by the adapters and surfaced
 * via {@link InterceptorRuntime#interceptHttpResponse}.</p>
 */
public class ResponseWrapperShapeTest {

    /** A minimal runnable script that records resp.url and friends into a global. */
    private static final String SCRIPT =
            "var seenUrl, seenMethod, seenStatus, seenReqUrl, seenReqMethod, seenReqHeader;\n" +
            "function onResponse(resp) {\n" +
            "  seenUrl = resp.url;\n" +
            "  seenMethod = resp.method;\n" +
            "  seenStatus = resp.status;\n" +
            "  if (resp.request) {\n" +
            "    seenReqUrl = resp.request.url;\n" +
            "    seenReqMethod = resp.request.method;\n" +
            "    seenReqHeader = resp.request.headers['X-Trace'];\n" +
            "  }\n" +
            "  return resp;\n" +
            "}\n";

    @Test
    public void onResponse_seesUrlAndMethodAndRequestPeek() throws Exception {
        InterceptorRuntime rt = InterceptorRuntime.getInstance();
        rt.install(SCRIPT);
        try {
            Map<String, String> reqHeaders = new LinkedHashMap<>();
            reqHeaders.put("X-Trace", "abc-123");
            byte[] reqBody = "ping".getBytes(StandardCharsets.UTF_8);

            Map<String, String> respHeaders = new LinkedHashMap<>();
            respHeaders.put("Content-Type", "application/json");
            byte[] respBody = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);

            InterceptorPayloads.HttpResponseMutation mut = rt.interceptHttpResponse(
                    "https://api.example.com/users/42",
                    "POST",
                    reqHeaders,
                    reqBody,
                    InterceptorPayloads.TAG_OKHTTP,
                    200,
                    "OK",
                    respHeaders,
                    respBody);

            // Script returns resp unchanged → UNCHANGED (no rewrite needed).
            assertEquals(false, mut.mutated);

            // Peek at the script's globals to verify the wrapper shape.
            Object scope = installedScope(rt);
            assertEquals("https://api.example.com/users/42", peek(scope, "seenUrl"));
            assertEquals("POST", peek(scope, "seenMethod"));
            assertEquals(200, ((Number) peek(scope, "seenStatus")).intValue());
            assertEquals("https://api.example.com/users/42", peek(scope, "seenReqUrl"));
            assertEquals("POST", peek(scope, "seenReqMethod"));
            assertEquals("abc-123", peek(scope, "seenReqHeader"));
        } finally {
            rt.clear();
        }
    }

    @Test
    public void onResponse_canMatchByUrl_andMutateStatus() throws Exception {
        InterceptorRuntime rt = InterceptorRuntime.getInstance();
        // The natural-looking script that used to TypeError because
        // resp.url was undefined. With the fix, this works on the
        // first request matching /profile.
        rt.install(
                "function onResponse(resp) {\n" +
                "  if (resp.url && resp.url.indexOf('/profile') >= 0) {\n" +
                "    resp.status = 401;\n" +
                "    resp.body = '{\"error\":\"unauthorized\"}';\n" +
                "  }\n" +
                "  return resp;\n" +
                "}\n");
        try {
            // Non-matching URL → unchanged.
            InterceptorPayloads.HttpResponseMutation noMatch = rt.interceptHttpResponse(
                    "https://api.example.com/health", "GET",
                    new LinkedHashMap<>(), null,
                    InterceptorPayloads.TAG_OKHTTP,
                    200, "OK", new LinkedHashMap<>(), "{}".getBytes(StandardCharsets.UTF_8));
            assertEquals(false, noMatch.mutated);

            // Matching URL → status flipped to 401, body replaced.
            Map<String, String> respHeaders = new LinkedHashMap<>();
            respHeaders.put("Content-Type", "application/json");
            InterceptorPayloads.HttpResponseMutation match = rt.interceptHttpResponse(
                    "https://api.example.com/profile/me", "GET",
                    new LinkedHashMap<>(), null,
                    InterceptorPayloads.TAG_OKHTTP,
                    200, "OK", respHeaders, "{\"id\":1}".getBytes(StandardCharsets.UTF_8));

            assertTrue("expected mutation when URL matches", match.mutated);
            assertEquals(401, match.status);
            assertEquals("{\"error\":\"unauthorized\"}",
                    new String(match.body, StandardCharsets.UTF_8));
        } finally {
            rt.clear();
        }
    }

    @Test
    public void onResponse_throwsAreCapturedToRingBufferAndPassThrough() throws Exception {
        InterceptorRuntime rt = InterceptorRuntime.getInstance();
        // resp.notAField will be undefined; calling .indexOf throws.
        rt.install(
                "function onResponse(resp) {\n" +
                "  resp.notAField.indexOf('boom');\n" +
                "  return resp;\n" +
                "}\n");
        try {
            long highestSeqBefore = highestSeq(rt.logs().since(0));
            InterceptorPayloads.HttpResponseMutation mut = rt.interceptHttpResponse(
                    "https://api.example.com/x", "GET",
                    new LinkedHashMap<>(), null,
                    InterceptorPayloads.TAG_OKHTTP,
                    200, "OK", new LinkedHashMap<>(), null);

            // Caught throw → response passes through unchanged.
            assertEquals(false, mut.mutated);
            assertEquals(false, mut.dropped);

            // Error landed in the ring buffer for later inspection via
            // /interceptor/logs since=<highestSeqBefore>.
            java.util.List<ScriptLog.Entry> newEntries = rt.logs().since(highestSeqBefore);
            assertTrue("expected at least one new log entry capturing the throw; got " +
                    newEntries.size() + " new entries",
                    newEntries.size() >= 1);
            // And it should be at ERROR level — the script threw.
            boolean sawError = newEntries.stream()
                    .anyMatch(e -> e.level == ScriptLog.Level.ERROR);
            assertTrue("expected an ERROR-level entry capturing the throw", sawError);
        } finally {
            rt.clear();
        }
    }

    private static long highestSeq(java.util.List<ScriptLog.Entry> entries) {
        long max = 0;
        for (ScriptLog.Entry e : entries) max = Math.max(max, e.seq);
        return max;
    }

    // ---- reflection helpers (no public peek API on InterceptorRuntime) ----

    private static Object installedScope(InterceptorRuntime rt) throws Exception {
        java.lang.reflect.Field f = InterceptorRuntime.class.getDeclaredField("installed");
        f.setAccessible(true);
        Object installed = f.get(rt);
        java.lang.reflect.Field scopeField = installed.getClass().getDeclaredField("scope");
        scopeField.setAccessible(true);
        return scopeField.get(installed);
    }

    private static Object peek(Object scope, String name) {
        org.mozilla.javascript.ScriptableObject so = (org.mozilla.javascript.ScriptableObject) scope;
        Object v = so.get(name, so);
        if (v == org.mozilla.javascript.UniqueTag.NOT_FOUND) return null;
        return v;
    }
}
