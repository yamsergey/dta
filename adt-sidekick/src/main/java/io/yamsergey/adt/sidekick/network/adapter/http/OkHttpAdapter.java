package io.yamsergey.adt.sidekick.network.adapter.http;

import java.util.Arrays;
import java.util.List;

import io.yamsergey.adt.sidekick.jvmti.MethodHook;
import io.yamsergey.adt.sidekick.network.adapter.AdapterType;
import io.yamsergey.adt.sidekick.network.adapter.NetworkAdapter;

/**
 * Network adapter for OkHttp HTTP client.
 *
 * <p>Intercepts calls to {@code okhttp3.internal.connection.RealCall.getResponseWithInterceptorChain()}
 * to capture HTTP request/response details for both synchronous (execute) and
 * asynchronous (enqueue) requests.</p>
 *
 * <p>Supports both OkHttp 3.x (Java) and OkHttp 4.x (Kotlin) by registering hooks for:
 * <ul>
 *   <li>{@code getResponseWithInterceptorChain} - OkHttp 3.x (Java)</li>
 *   <li>{@code getResponseWithInterceptorChain$okhttp} - OkHttp 4.x (Kotlin internal mangling)</li>
 * </ul>
 *
 * <p>This is the primary HTTP adapter and is enabled by default.</p>
 */
public class OkHttpAdapter implements NetworkAdapter {

    private static final String OKHTTP_REALCALL_CLASS = "okhttp3.internal.connection.RealCall";

    @Override
    public String getId() {
        return "okhttp";
    }

    @Override
    public String getName() {
        return "OkHttp";
    }

    @Override
    public AdapterType getType() {
        return AdapterType.HTTP;
    }

    @Override
    public boolean isAvailable() {
        try {
            Class.forName(OKHTTP_REALCALL_CLASS);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public List<MethodHook> getHooks() {
        // Register hooks for both OkHttp 3.x (Java) and OkHttp 4.x (Kotlin)
        // Only one will match at runtime depending on which version is used
        return Arrays.asList(
                new OkHttpExecuteHook("getResponseWithInterceptorChain", "okhttp3"),      // OkHttp 3.x
                new OkHttpExecuteHook("getResponseWithInterceptorChain$okhttp", "okhttp4") // OkHttp 4.x
        );
    }

    @Override
    public int getPriority() {
        // High priority - OkHttp is the most common HTTP client
        return 100;
    }
}
