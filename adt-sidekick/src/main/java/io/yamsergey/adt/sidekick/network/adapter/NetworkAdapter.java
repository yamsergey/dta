package io.yamsergey.adt.sidekick.network.adapter;

import java.util.List;

import io.yamsergey.adt.sidekick.jvmti.MethodHook;

/**
 * Interface for network interception adapters.
 *
 * <p>Adapters provide JVMTI hooks for intercepting network calls from specific libraries
 * (OkHttp, URLConnection, WebSocket clients, etc.). They are registered with
 * {@link NetworkInterceptorManager} and automatically activated when the target library
 * is available.</p>
 *
 * <h3>Implementing a Custom Adapter</h3>
 * <pre>{@code
 * public class MyHttpClientAdapter implements NetworkAdapter {
 *     @Override
 *     public String getId() { return "my-http-client"; }
 *
 *     @Override
 *     public String getName() { return "My HTTP Client"; }
 *
 *     @Override
 *     public AdapterType getType() { return AdapterType.HTTP; }
 *
 *     @Override
 *     public boolean isAvailable() {
 *         try {
 *             Class.forName("com.example.MyHttpClient");
 *             return true;
 *         } catch (ClassNotFoundException e) {
 *             return false;
 *         }
 *     }
 *
 *     @Override
 *     public List<MethodHook> getHooks() {
 *         return Arrays.asList(new MyHttpClientHook());
 *     }
 * }
 * }</pre>
 */
public interface NetworkAdapter {

    /**
     * Returns a unique identifier for this adapter.
     *
     * <p>Used for logging, configuration, and programmatic access.</p>
     *
     * @return unique adapter ID (e.g., "okhttp", "urlconnection", "okhttp-websocket")
     */
    String getId();

    /**
     * Returns a human-readable name for this adapter.
     *
     * <p>Used in UI and logs for display purposes.</p>
     *
     * @return display name (e.g., "OkHttp", "URLConnection", "OkHttp WebSocket")
     */
    String getName();

    /**
     * Returns the type of network traffic this adapter intercepts.
     *
     * @return adapter type (HTTP or WEBSOCKET)
     */
    AdapterType getType();

    /**
     * Checks if the target library is available in the current runtime.
     *
     * <p>This should perform a lightweight check (typically {@code Class.forName()})
     * to determine if the library classes are present. The adapter will only be
     * activated if this returns true.</p>
     *
     * @return true if the target library is available
     */
    boolean isAvailable();

    /**
     * Returns the JVMTI hooks for intercepting the target library.
     *
     * <p>These hooks will be registered with {@link io.yamsergey.adt.sidekick.jvmti.JvmtiAgent}
     * when the adapter is activated.</p>
     *
     * @return list of method hooks (never null, may be empty)
     */
    List<MethodHook> getHooks();

    /**
     * Returns the priority of this adapter.
     *
     * <p>Higher priority adapters are registered first. This can be useful when
     * multiple adapters might hook the same classes.</p>
     *
     * @return priority value (default 0)
     */
    default int getPriority() {
        return 0;
    }

    /**
     * Returns whether this adapter is enabled by default.
     *
     * <p>Default adapters (OkHttp, URLConnection) return true. Custom adapters
     * can return false to require explicit enablement via configuration.</p>
     *
     * @return true if enabled by default
     */
    default boolean isEnabledByDefault() {
        return true;
    }

    /**
     * Called when the adapter is activated.
     *
     * <p>This is called after hooks are registered but before any interception
     * occurs. Adapters can perform additional setup here.</p>
     */
    default void onActivated() {
        // Default: no-op
    }

    /**
     * Called when the adapter is deactivated.
     *
     * <p>Adapters should clean up any resources here.</p>
     */
    default void onDeactivated() {
        // Default: no-op
    }
}
