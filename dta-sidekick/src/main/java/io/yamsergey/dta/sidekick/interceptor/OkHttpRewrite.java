package io.yamsergey.dta.sidekick.interceptor;

import io.yamsergey.dta.sidekick.SidekickLog;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Reflection-driven helpers for applying interceptor mutations to
 * {@code okhttp3.Request} and {@code okhttp3.Response} objects.
 * Sidekick can't reference these classes at compile time — they live
 * in the host app's classloader.
 */
public final class OkHttpRewrite {

    private static final String TAG = "OkHttpRewrite";

    private OkHttpRewrite() {}

    /**
     * Rebuilds a new {@code okhttp3.Request} from {@code original}
     * applying {@code mut}. Fields left null on the mutation pass
     * through from the original. Returns null on failure (caller
     * should keep the original).
     */
    public static Object rewriteRequest(Object original, InterceptorPayloads.HttpRequestMutation mut) {
        try {
            ClassLoader cl = original.getClass().getClassLoader();
            // Request.newBuilder() returns Request.Builder
            Method newBuilder = original.getClass().getMethod("newBuilder");
            Object builder = newBuilder.invoke(original);

            Class<?> builderClass = builder.getClass();

            if (mut.url != null) {
                Method urlStr = builderClass.getMethod("url", String.class);
                urlStr.invoke(builder, mut.url);
            }

            if (mut.headers != null) {
                Object headers = buildHeaders(mut.headers, cl);
                if (headers != null) {
                    Method headersM = builderClass.getMethod("headers", cl.loadClass("okhttp3.Headers"));
                    headersM.invoke(builder, headers);
                }
            }

            // method() expects (String, RequestBody?). We only invoke
            // it when method or body actually changed; otherwise leave
            // the original method+body in place via newBuilder().
            if (mut.method != null || mut.body != null) {
                String method = mut.method != null ? mut.method
                        : (String) original.getClass().getMethod("method").invoke(original);
                Object requestBody;
                if (mut.body != null) {
                    String contentType = mut.headers != null ? findHeader(mut.headers, "Content-Type") : null;
                    requestBody = buildRequestBody(mut.body, contentType, cl);
                } else {
                    requestBody = original.getClass().getMethod("body").invoke(original);
                }
                Method methodM = builderClass.getMethod("method", String.class, cl.loadClass("okhttp3.RequestBody"));
                methodM.invoke(builder, method, requestBody);
            }

            return builderClass.getMethod("build").invoke(builder);
        } catch (Throwable t) {
            SidekickLog.w(TAG, "rewriteRequest failed: " + t.getMessage());
            return null;
        }
    }

    /**
     * Rebuilds a new {@code okhttp3.Response} from {@code original}
     * applying {@code mut}. Returns null on failure.
     */
    public static Object rewriteResponse(Object call, Object original,
                                         InterceptorPayloads.HttpResponseMutation mut) {
        if (original == null) return null;
        try {
            ClassLoader cl = original.getClass().getClassLoader();
            Method newBuilder = original.getClass().getMethod("newBuilder");
            Object builder = newBuilder.invoke(original);
            Class<?> builderClass = builder.getClass();

            if (mut.status >= 0) {
                builderClass.getMethod("code", int.class).invoke(builder, mut.status);
            }
            if (mut.statusMessage != null) {
                builderClass.getMethod("message", String.class).invoke(builder, mut.statusMessage);
            }
            if (mut.headers != null) {
                Object headers = buildHeaders(mut.headers, cl);
                if (headers != null) {
                    builderClass.getMethod("headers", cl.loadClass("okhttp3.Headers")).invoke(builder, headers);
                }
            }
            if (mut.body != null) {
                String contentType = mut.headers != null ? findHeader(mut.headers, "Content-Type") : null;
                Object body = buildResponseBody(mut.body, contentType, cl);
                if (body != null) {
                    builderClass.getMethod("body", cl.loadClass("okhttp3.ResponseBody")).invoke(builder, body);
                }
            }
            return builderClass.getMethod("build").invoke(builder);
        } catch (Throwable t) {
            SidekickLog.w(TAG, "rewriteResponse failed: " + t.getMessage());
            return null;
        }
    }

    /** Sets {@code RealCall.originalRequest} via reflection. */
    public static boolean setRealCallRequest(Object call, Object newRequest) {
        try {
            java.lang.reflect.Field f = call.getClass().getDeclaredField("originalRequest");
            f.setAccessible(true);
            f.set(call, newRequest);
            return true;
        } catch (Throwable t) {
            SidekickLog.w(TAG, "setRealCallRequest failed: " + t.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------
    // Headers / RequestBody / ResponseBody construction
    // ------------------------------------------------------------

    private static Object buildHeaders(Map<String, String> headers, ClassLoader cl) {
        try {
            Class<?> hbClass = cl.loadClass("okhttp3.Headers$Builder");
            Constructor<?> ctor = hbClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object builder = ctor.newInstance();
            Method add = hbClass.getMethod("add", String.class, String.class);
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                add.invoke(builder, e.getKey(), e.getValue());
            }
            return hbClass.getMethod("build").invoke(builder);
        } catch (Throwable t) {
            SidekickLog.w(TAG, "buildHeaders failed: " + t.getMessage());
            return null;
        }
    }

    private static Object buildRequestBody(byte[] body, String contentType, ClassLoader cl) {
        try {
            Class<?> rbClass = cl.loadClass("okhttp3.RequestBody");
            Class<?> mtClass = cl.loadClass("okhttp3.MediaType");
            Object mediaType = parseMediaType(contentType, mtClass);
            // Try OkHttp 4.x companion-based factory first
            try {
                Class<?> companion = cl.loadClass("okhttp3.RequestBody$Companion");
                Object instance = rbClass.getField("Companion").get(null);
                Method create = companion.getMethod("create", byte[].class, mtClass, int.class, int.class);
                return create.invoke(instance, body, mediaType, 0, body.length);
            } catch (Throwable ignored) {
                // OkHttp 3.x: static RequestBody.create(MediaType, byte[])
                Method create = rbClass.getMethod("create", mtClass, byte[].class);
                return create.invoke(null, mediaType, body);
            }
        } catch (Throwable t) {
            SidekickLog.w(TAG, "buildRequestBody failed: " + t.getMessage());
            return null;
        }
    }

    private static Object buildResponseBody(byte[] body, String contentType, ClassLoader cl) {
        try {
            Class<?> rbClass = cl.loadClass("okhttp3.ResponseBody");
            Class<?> mtClass = cl.loadClass("okhttp3.MediaType");
            Object mediaType = parseMediaType(contentType, mtClass);
            try {
                Class<?> companion = cl.loadClass("okhttp3.ResponseBody$Companion");
                Object instance = rbClass.getField("Companion").get(null);
                // Companion.create(byte[], MediaType)
                Method create = companion.getMethod("create", byte[].class, mtClass);
                return create.invoke(instance, body, mediaType);
            } catch (Throwable ignored) {
                Method create = rbClass.getMethod("create", mtClass, byte[].class);
                return create.invoke(null, mediaType, body);
            }
        } catch (Throwable t) {
            SidekickLog.w(TAG, "buildResponseBody failed: " + t.getMessage());
            return null;
        }
    }

    private static Object parseMediaType(String contentType, Class<?> mtClass) throws Exception {
        Method parse = mtClass.getMethod("parse", String.class);
        return parse.invoke(null, contentType != null ? contentType : "application/octet-stream");
    }

    private static String findHeader(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (name.equalsIgnoreCase(e.getKey())) return e.getValue();
        }
        return null;
    }
}
