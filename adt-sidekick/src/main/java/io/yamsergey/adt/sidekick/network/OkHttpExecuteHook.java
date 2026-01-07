package io.yamsergey.adt.sidekick.network;

import android.util.Log;

import io.yamsergey.adt.sidekick.jvmti.MethodHook;

/**
 * JVMTI hook for intercepting OkHttp network calls.
 *
 * <p>This hook intercepts calls to RealCall.execute() and logs request/response details.
 * The hook is injected via bytecode transformation at class load time.</p>
 */
public class OkHttpExecuteHook implements MethodHook {

    private static final String TAG = "OkHttpHook";

    @Override
    public String getTargetClass() {
        // OkHttp 4.x uses this class for call execution
        return "okhttp3.internal.connection.RealCall";
    }

    @Override
    public String getTargetMethod() {
        return "execute";
    }

    @Override
    public String getMethodSignature() {
        // execute() returns Response
        return "()Lokhttp3/Response;";
    }

    @Override
    public String getId() {
        return "okhttp-execute";
    }

    @Override
    public void onEnter(Object thisObj, Object[] args) {
        try {
            // thisObj is the RealCall instance
            // Get the request from the call
            Object request = getRequest(thisObj);
            if (request != null) {
                String url = getUrl(request);
                String method = getMethod(request);
                Log.i(TAG, ">>> " + method + " " + url);

                // Record in NetworkInspector
                NetworkRequest netRequest = NetworkInspector.startRequest(url, method);
                // Store the request ID for onExit
                storeRequestId(thisObj, netRequest.getId());
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in onEnter", t);
        }
    }

    @Override
    public Object onExit(Object thisObj, Object result) {
        try {
            String requestId = getStoredRequestId(thisObj);
            Log.i(TAG, "<<< onExit called (result=" + result + ", requestId=" + requestId + ")");

            // Even if result is null (we pass null from bytecode), still mark as completed
            if (requestId != null) {
                NetworkRequest netRequest = NetworkInspector.getRequest(requestId);
                if (netRequest != null) {
                    if (result != null) {
                        int code = getResponseCode(result);
                        Log.i(TAG, "<<< Response code: " + code);
                        netRequest.setResponseCode(code);
                    }
                    netRequest.markCompleted();
                    Log.i(TAG, "<<< Request marked completed: " + requestId);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in onExit", t);
        }
        return result;
    }

    @Override
    public Throwable onException(Object thisObj, Throwable throwable) {
        try {
            String requestId = getStoredRequestId(thisObj);
            Log.e(TAG, "!!! Request failed: " + requestId + " - " + throwable.getMessage());

            // Update NetworkInspector
            if (requestId != null) {
                NetworkRequest netRequest = NetworkInspector.getRequest(requestId);
                if (netRequest != null) {
                    netRequest.markFailed(throwable.getMessage());
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in onException", t);
        }
        return throwable;
    }

    // ThreadLocal to store request IDs between onEnter and onExit
    private static final ThreadLocal<String> requestIdHolder = new ThreadLocal<>();

    private void storeRequestId(Object thisObj, String requestId) {
        requestIdHolder.set(requestId);
    }

    private String getStoredRequestId(Object thisObj) {
        String id = requestIdHolder.get();
        requestIdHolder.remove();
        return id;
    }

    // Reflection helpers to access OkHttp internals
    private Object getRequest(Object call) {
        try {
            java.lang.reflect.Method m = call.getClass().getMethod("request");
            return m.invoke(call);
        } catch (Exception e) {
            // Try field access
            try {
                java.lang.reflect.Field f = call.getClass().getDeclaredField("originalRequest");
                f.setAccessible(true);
                return f.get(call);
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private String getUrl(Object request) {
        try {
            java.lang.reflect.Method m = request.getClass().getMethod("url");
            Object url = m.invoke(request);
            return url != null ? url.toString() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String getMethod(Object request) {
        try {
            java.lang.reflect.Method m = request.getClass().getMethod("method");
            return (String) m.invoke(request);
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    private int getResponseCode(Object response) {
        try {
            java.lang.reflect.Method m = response.getClass().getMethod("code");
            return (Integer) m.invoke(response);
        } catch (Exception e) {
            return -1;
        }
    }
}
