package io.yamsergey.adt.sidekick;

import io.yamsergey.adt.sidekick.SidekickLog;
import io.yamsergey.adt.sidekick.jvmti.JvmtiAgent;

import io.yamsergey.adt.sidekick.network.adapter.NetworkAdapter;
import io.yamsergey.adt.sidekick.network.adapter.NetworkInterceptorManager;

/**
 * Main entry point for Sidekick configuration.
 *
 * <p>Sidekick automatically initializes via ContentProvider, but you can
 * configure it before initialization using this class.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // In your Application.attachBaseContext() or Application.onCreate():
 * Sidekick.configure(SidekickConfig.builder()
 *     .disableUrlConnection()
 *     .addAdapter(new MyCustomAdapter())
 *     .build());
 * }</pre>
 *
 * <p>Note: Configuration must be set BEFORE Sidekick initialization occurs
 * (which happens via ContentProvider during app startup).</p>
 */
public final class Sidekick {

    private static final String TAG = "Sidekick";

    private static volatile SidekickConfig pendingConfig = null;
    private static volatile SidekickConfig activeConfig = null;
    private static volatile boolean initialized = false;

    private Sidekick() {}

    /**
     * Configures Sidekick before initialization.
     *
     * <p>This must be called before the ContentProvider initializes Sidekick.
     * The best place to call this is in {@code Application.attachBaseContext()}.</p>
     *
     * @param config the configuration to apply
     * @throws IllegalStateException if Sidekick has already been initialized
     */
    public static synchronized void configure(SidekickConfig config) {
        if (initialized) {
            throw new IllegalStateException(
                "Sidekick has already been initialized. " +
                "Call configure() in Application.attachBaseContext() before initialization."
            );
        }

        pendingConfig = config;
        SidekickLog.d(TAG, "Configuration pending (will apply on initialization)");
    }

    /**
     * Checks if Sidekick has been initialized.
     */
    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * Gets the active configuration (or defaults if not configured).
     */
    public static SidekickConfig getConfig() {
        return activeConfig != null ? activeConfig : SidekickConfig.defaults();
    }

    // =========================================================================
    // Internal (called by SidekickInitializer)
    // =========================================================================

    /**
     * Applies pending configuration and marks as initialized.
     * Called internally by SidekickInitializer.
     */
    public static synchronized void applyConfiguration() {
        if (initialized) {
            return;
        }

        SidekickConfig config = pendingConfig != null ? pendingConfig : SidekickConfig.defaults();
        activeConfig = config;

        // Apply debug logging setting to Java side
        SidekickLog.setDebugEnabled(config.isDebugLoggingEnabled());

        // Apply debug logging setting to native side (if agent is loaded)
        JvmtiAgent.setDebugEnabled(config.isDebugLoggingEnabled());

        // Apply adapter enable/disable settings
        applyAdapterSettings(config);

        // Register custom adapters
        for (NetworkAdapter adapter : config.getCustomAdapters()) {
            NetworkInterceptorManager.registerAdapter(adapter);
            SidekickLog.i(TAG, "Registered custom adapter: " + adapter.getId());
        }

        initialized = true;
        SidekickLog.d(TAG, "Configuration applied");
    }

    private static void applyAdapterSettings(SidekickConfig config) {
        // Apply settings to NetworkInterceptorManager
        if (!config.isOkHttpEnabled()) {
            NetworkInterceptorManager.setAdapterEnabled("okhttp", false);
        }
        if (!config.isUrlConnectionEnabled()) {
            NetworkInterceptorManager.setAdapterEnabled("urlconnection", false);
        }
        if (!config.isOkHttpWebSocketEnabled()) {
            NetworkInterceptorManager.setAdapterEnabled("okhttp-websocket", false);
        }
        if (!config.isJavaWebSocketEnabled()) {
            NetworkInterceptorManager.setAdapterEnabled("java-websocket", false);
        }
        if (!config.isNvWebSocketEnabled()) {
            NetworkInterceptorManager.setAdapterEnabled("nv-websocket", false);
        }
    }
}
