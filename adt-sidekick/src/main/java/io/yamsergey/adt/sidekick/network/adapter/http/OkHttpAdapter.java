package io.yamsergey.adt.sidekick.network.adapter.http;

import java.util.Collections;
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
        return Collections.singletonList(new OkHttpExecuteHook());
    }

    @Override
    public int getPriority() {
        // High priority - OkHttp is the most common HTTP client
        return 100;
    }
}
