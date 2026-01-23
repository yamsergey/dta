package io.yamsergey.dta.sidekick.mock;

import io.yamsergey.dta.sidekick.SidekickLog;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Builds OkHttp Response objects via reflection.
 *
 * <p>This is necessary because the sidekick module doesn't have compile-time
 * access to OkHttp classes - they're only available at runtime in the target app.</p>
 */
public final class OkHttpResponseBuilder {

    private static final String TAG = "OkHttpResponseBuilder";

    private OkHttpResponseBuilder() {
    }

    /**
     * Builds a mock OkHttp Response object.
     *
     * @param call the original OkHttp Call object (to get the Request)
     * @param originalResponse the original Response object (for protocol, etc.)
     * @param mockResponse the mock response data
     * @return the mock Response object, or null if building fails
     */
    public static Object build(Object call, Object originalResponse, MockHttpResponse mockResponse) {
        if (mockResponse == null) {
            return null;
        }

        try {
            ClassLoader cl = call.getClass().getClassLoader();

            // Get the Request from the Call
            Object request = getRequest(call);
            if (request == null) {
                SidekickLog.w(TAG, "Failed to get Request from Call");
                return null;
            }

            // Get protocol from original response, or default to HTTP_1_1
            Object protocol = getProtocol(originalResponse, cl);

            // Build the Response using Response.Builder
            Class<?> responseClass = cl.loadClass("okhttp3.Response");
            Class<?> responseBuilderClass = cl.loadClass("okhttp3.Response$Builder");

            // Create builder
            Constructor<?> builderConstructor = responseBuilderClass.getDeclaredConstructor();
            builderConstructor.setAccessible(true);
            Object builder = builderConstructor.newInstance();

            // Set request
            Method requestMethod = responseBuilderClass.getMethod("request", cl.loadClass("okhttp3.Request"));
            requestMethod.invoke(builder, request);

            // Set protocol
            Method protocolMethod = responseBuilderClass.getMethod("protocol", cl.loadClass("okhttp3.Protocol"));
            protocolMethod.invoke(builder, protocol);

            // Set code
            Method codeMethod = responseBuilderClass.getMethod("code", int.class);
            codeMethod.invoke(builder, mockResponse.getStatusCode());

            // Set message
            Method messageMethod = responseBuilderClass.getMethod("message", String.class);
            messageMethod.invoke(builder, mockResponse.getStatusMessage() != null ?
                    mockResponse.getStatusMessage() : "OK");

            // Set headers
            Object headers = buildHeaders(mockResponse.getHeaders(), mockResponse.getContentType(), cl);
            Method headersMethod = responseBuilderClass.getMethod("headers", cl.loadClass("okhttp3.Headers"));
            headersMethod.invoke(builder, headers);

            // Set body
            Object body = buildResponseBody(mockResponse.getBody(), mockResponse.getContentType(), cl);
            if (body != null) {
                Method bodyMethod = responseBuilderClass.getMethod("body", cl.loadClass("okhttp3.ResponseBody"));
                bodyMethod.invoke(builder, body);
            }

            // Build and return the Response
            Method buildMethod = responseBuilderClass.getMethod("build");
            return buildMethod.invoke(builder);

        } catch (Exception e) {
            SidekickLog.e(TAG, "Failed to build mock response", e);
            return null;
        }
    }

    /**
     * Gets the Request from a Call object.
     */
    private static Object getRequest(Object call) {
        try {
            // Try RealCall.request() first
            Method requestMethod = call.getClass().getMethod("request");
            return requestMethod.invoke(call);
        } catch (Exception e) {
            // Try getOriginalRequest() for older versions
            try {
                Method getOriginalRequest = call.getClass().getMethod("getOriginalRequest");
                return getOriginalRequest.invoke(call);
            } catch (Exception e2) {
                SidekickLog.w(TAG, "Could not get request from call: " + e.getMessage());
                return null;
            }
        }
    }

    /**
     * Gets the Protocol from the original response, or defaults to HTTP_1_1.
     */
    private static Object getProtocol(Object originalResponse, ClassLoader cl) {
        try {
            if (originalResponse != null) {
                Method protocolMethod = originalResponse.getClass().getMethod("protocol");
                return protocolMethod.invoke(originalResponse);
            }
        } catch (Exception e) {
            // Ignore
        }

        // Default to HTTP_1_1
        try {
            Class<?> protocolClass = cl.loadClass("okhttp3.Protocol");
            return Enum.valueOf((Class<Enum>) protocolClass, "HTTP_1_1");
        } catch (Exception e) {
            SidekickLog.w(TAG, "Could not get HTTP_1_1 protocol: " + e.getMessage());
            return null;
        }
    }

    /**
     * Builds an OkHttp Headers object.
     */
    private static Object buildHeaders(Map<String, String> headers, String contentType, ClassLoader cl) {
        try {
            Class<?> headersBuilderClass = cl.loadClass("okhttp3.Headers$Builder");
            Constructor<?> builderConstructor = headersBuilderClass.getDeclaredConstructor();
            builderConstructor.setAccessible(true);
            Object builder = builderConstructor.newInstance();

            Method addMethod = headersBuilderClass.getMethod("add", String.class, String.class);

            // Add custom headers
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    addMethod.invoke(builder, entry.getKey(), entry.getValue());
                }
            }

            // Add Content-Type if not already present
            if (contentType != null && (headers == null || !headers.containsKey("Content-Type"))) {
                addMethod.invoke(builder, "Content-Type", contentType);
            }

            Method buildMethod = headersBuilderClass.getMethod("build");
            return buildMethod.invoke(builder);

        } catch (Exception e) {
            SidekickLog.w(TAG, "Could not build headers: " + e.getMessage());
            return null;
        }
    }

    /**
     * Builds an OkHttp ResponseBody object.
     */
    private static Object buildResponseBody(String body, String contentType, ClassLoader cl) {
        if (body == null) {
            return null;
        }

        try {
            Class<?> responseBodyClass = cl.loadClass("okhttp3.ResponseBody");
            Class<?> mediaTypeClass = cl.loadClass("okhttp3.MediaType");

            // Parse MediaType
            Object mediaType = null;
            if (contentType != null) {
                Method parseMethod = mediaTypeClass.getMethod("parse", String.class);
                mediaType = parseMethod.invoke(null, contentType);
            } else {
                // Default to application/octet-stream
                Method parseMethod = mediaTypeClass.getMethod("parse", String.class);
                mediaType = parseMethod.invoke(null, "application/octet-stream");
            }

            // Create ResponseBody using ResponseBody.create(mediaType, body)
            // Try the modern signature first (Kotlin extension), then legacy
            try {
                // OkHttp 4.x: ResponseBody.Companion.create(body, mediaType)
                Class<?> companionClass = cl.loadClass("okhttp3.ResponseBody$Companion");
                Object companion = responseBodyClass.getField("Companion").get(null);
                Method createMethod = companionClass.getMethod("create", String.class, mediaTypeClass);
                return createMethod.invoke(companion, body, mediaType);
            } catch (Exception e) {
                // OkHttp 3.x: ResponseBody.create(mediaType, body)
                Method createMethod = responseBodyClass.getMethod("create", mediaTypeClass, String.class);
                return createMethod.invoke(null, mediaType, body);
            }

        } catch (Exception e) {
            SidekickLog.w(TAG, "Could not build response body: " + e.getMessage());
            return null;
        }
    }
}
