package io.yamsergey.dta.sidekick.network.adapter;

import io.yamsergey.dta.sidekick.SidekickLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import io.yamsergey.dta.sidekick.chromeintent.ChromeIntentAdapter;
import io.yamsergey.dta.sidekick.customtabs.CustomTabsAdapter;
import io.yamsergey.dta.sidekick.jvmti.JvmtiAgent;
import io.yamsergey.dta.sidekick.jvmti.MethodHook;
import io.yamsergey.dta.sidekick.network.adapter.http.OkHttpAdapter;
import io.yamsergey.dta.sidekick.network.adapter.http.UrlConnectionAdapter;
import io.yamsergey.dta.sidekick.network.adapter.websocket.JavaWebSocketAdapter;
import io.yamsergey.dta.sidekick.network.adapter.websocket.NvWebSocketAdapter;
import io.yamsergey.dta.sidekick.network.adapter.websocket.OkHttpWebSocketAdapter;

/**
 * Manages network interception adapters.
 *
 * <p>This class handles registration, activation, and lifecycle of network adapters.
 * It automatically registers default adapters (OkHttp, URLConnection, WebSocket libraries)
 * and allows users to register custom adapters.</p>
 *
 * <h3>Default Adapters</h3>
 * <ul>
 *   <li><b>HTTP:</b> OkHttp, URLConnection</li>
 *   <li><b>WebSocket:</b> OkHttp WebSocket, Java-WebSocket, nv-websocket-client</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // Initialize with default adapters (called by SidekickInitializer)
 * NetworkInterceptorManager.initialize();
 *
 * // Register a custom adapter
 * NetworkInterceptorManager.registerAdapter(new MyCustomAdapter());
 *
 * // Disable a specific adapter
 * NetworkInterceptorManager.setAdapterEnabled("urlconnection", false);
 * }</pre>
 */
public final class NetworkInterceptorManager {

    private static final String TAG = "NetworkInterceptorMgr";

    // All registered adapters
    private static final List<NetworkAdapter> adapters = new CopyOnWriteArrayList<>();

    // Active (enabled + available) adapters
    private static final Map<String, NetworkAdapter> activeAdapters = new ConcurrentHashMap<>();

    // Adapter enable/disable overrides
    private static final Map<String, Boolean> enabledOverrides = new ConcurrentHashMap<>();

    // Initialization state
    private static volatile boolean initialized = false;

    // Prevent instantiation
    private NetworkInterceptorManager() {}

    /**
     * Initializes the interceptor manager with default adapters.
     *
     * <p>This should be called once during app startup (typically by SidekickInitializer).
     * Calling multiple times has no effect.</p>
     */
    public static synchronized void initialize() {
        if (initialized) {
            SidekickLog.d(TAG, "Already initialized");
            return;
        }

        SidekickLog.i(TAG, "Initializing network interceptor manager...");

        // Register default adapters
        registerDefaultAdapters();

        // Activate available adapters
        activateAdapters();

        initialized = true;
        SidekickLog.i(TAG, "Initialization complete. Active adapters: " + activeAdapters.size());
    }

    /**
     * Registers the built-in default adapters.
     */
    private static void registerDefaultAdapters() {
        // HTTP adapters
        registerAdapterInternal(new OkHttpAdapter());
        registerAdapterInternal(new UrlConnectionAdapter());

        // WebSocket adapters
        registerAdapterInternal(new OkHttpWebSocketAdapter());
        registerAdapterInternal(new JavaWebSocketAdapter());
        registerAdapterInternal(new NvWebSocketAdapter());

        // Custom Tabs adapter
        registerAdapterInternal(new CustomTabsAdapter());

        // Standalone Chrome via Intent.ACTION_VIEW (no Custom Tabs)
        registerAdapterInternal(new ChromeIntentAdapter());
    }

    /**
     * Registers a network adapter.
     *
     * <p>If the adapter is available and enabled, its hooks will be registered
     * with the JVMTI agent.</p>
     *
     * @param adapter the adapter to register
     */
    public static void registerAdapter(NetworkAdapter adapter) {
        if (adapter == null) {
            throw new IllegalArgumentException("Adapter cannot be null");
        }

        registerAdapterInternal(adapter);

        // If already initialized, activate immediately if available
        if (initialized && isAdapterEnabled(adapter) && adapter.isAvailable()) {
            activateAdapter(adapter);
        }
    }

    private static void registerAdapterInternal(NetworkAdapter adapter) {
        // Remove existing adapter with same ID
        adapters.removeIf(a -> a.getId().equals(adapter.getId()));
        adapters.add(adapter);
        SidekickLog.d(TAG, "Registered adapter: " + adapter.getId() + " (" + adapter.getName() + ")");
    }

    /**
     * Unregisters a network adapter.
     *
     * @param adapterId the adapter ID to unregister
     * @return true if an adapter was removed
     */
    public static boolean unregisterAdapter(String adapterId) {
        boolean removed = adapters.removeIf(a -> a.getId().equals(adapterId));
        if (removed) {
            NetworkAdapter active = activeAdapters.remove(adapterId);
            if (active != null) {
                active.onDeactivated();
            }
            SidekickLog.d(TAG, "Unregistered adapter: " + adapterId);
        }
        return removed;
    }

    /**
     * Enables or disables a specific adapter.
     *
     * <p>This overrides the adapter's default enabled state. Changes take effect
     * on the next activation cycle or immediately if called after initialization.</p>
     *
     * @param adapterId the adapter ID
     * @param enabled   true to enable, false to disable
     */
    public static void setAdapterEnabled(String adapterId, boolean enabled) {
        enabledOverrides.put(adapterId, enabled);

        // If initialized, handle state change immediately
        if (initialized) {
            NetworkAdapter adapter = findAdapter(adapterId);
            if (adapter != null) {
                if (enabled && adapter.isAvailable() && !activeAdapters.containsKey(adapterId)) {
                    activateAdapter(adapter);
                } else if (!enabled && activeAdapters.containsKey(adapterId)) {
                    deactivateAdapter(adapter);
                }
            }
        }
    }

    /**
     * Checks if an adapter is enabled.
     *
     * @param adapterId the adapter ID
     * @return true if enabled
     */
    public static boolean isAdapterEnabled(String adapterId) {
        Boolean override = enabledOverrides.get(adapterId);
        if (override != null) {
            return override;
        }
        NetworkAdapter adapter = findAdapter(adapterId);
        return adapter != null && adapter.isEnabledByDefault();
    }

    private static boolean isAdapterEnabled(NetworkAdapter adapter) {
        Boolean override = enabledOverrides.get(adapter.getId());
        if (override != null) {
            return override;
        }
        return adapter.isEnabledByDefault();
    }

    /**
     * Gets all registered adapters.
     *
     * @return unmodifiable list of adapters
     */
    public static List<NetworkAdapter> getAdapters() {
        return Collections.unmodifiableList(new ArrayList<>(adapters));
    }

    /**
     * Gets all active (enabled + available) adapters.
     *
     * @return unmodifiable list of active adapters
     */
    public static List<NetworkAdapter> getActiveAdapters() {
        return Collections.unmodifiableList(new ArrayList<>(activeAdapters.values()));
    }

    /**
     * Gets adapters by type.
     *
     * @param type the adapter type
     * @return list of adapters of the specified type
     */
    public static List<NetworkAdapter> getAdaptersByType(AdapterType type) {
        List<NetworkAdapter> result = new ArrayList<>();
        for (NetworkAdapter adapter : adapters) {
            if (adapter.getType() == type) {
                result.add(adapter);
            }
        }
        return result;
    }

    /**
     * Finds an adapter by ID.
     *
     * @param adapterId the adapter ID
     * @return the adapter, or null if not found
     */
    public static NetworkAdapter findAdapter(String adapterId) {
        for (NetworkAdapter adapter : adapters) {
            if (adapter.getId().equals(adapterId)) {
                return adapter;
            }
        }
        return null;
    }

    /**
     * Checks if the manager has been initialized.
     */
    public static boolean isInitialized() {
        return initialized;
    }

    // =========================================================================
    // Activation
    // =========================================================================

    private static void activateAdapters() {
        // Sort by priority (highest first)
        List<NetworkAdapter> sortedAdapters = new ArrayList<>(adapters);
        sortedAdapters.sort(Comparator.comparingInt(NetworkAdapter::getPriority).reversed());

        for (NetworkAdapter adapter : sortedAdapters) {
            if (isAdapterEnabled(adapter) && adapter.isAvailable()) {
                activateAdapter(adapter);
            } else {
                String reason = !isAdapterEnabled(adapter) ? "disabled" : "not available";
                SidekickLog.d(TAG, "Skipping adapter " + adapter.getId() + " (" + reason + ")");
            }
        }
    }

    private static void activateAdapter(NetworkAdapter adapter) {
        String id = adapter.getId();

        if (activeAdapters.containsKey(id)) {
            SidekickLog.d(TAG, "Adapter already active: " + id);
            return;
        }

        try {
            // Register all hooks with JVMTI agent first
            List<MethodHook> hooks = adapter.getHooks();
            java.util.Set<String> targetClasses = new java.util.HashSet<>();

            for (MethodHook hook : hooks) {
                String hookId = JvmtiAgent.registerHook(hook);
                SidekickLog.d(TAG, "Registered hook: " + hookId + " for adapter " + id);
                targetClasses.add(hook.getTargetClass());
            }

            // Retransform each target class ONCE after all hooks are registered
            // This ensures all hooks for the same class are applied in a single transformation
            for (String targetClass : targetClasses) {
                tryRetransformClass(targetClass);
            }

            // Call activation callback
            adapter.onActivated();

            activeAdapters.put(id, adapter);
            SidekickLog.i(TAG, "Activated adapter: " + id + " (" + adapter.getName() + ") with " + hooks.size() + " hooks");

        } catch (Exception e) {
            SidekickLog.e(TAG, "Failed to activate adapter: " + id, e);
        }
    }

    private static void deactivateAdapter(NetworkAdapter adapter) {
        String id = adapter.getId();

        if (!activeAdapters.containsKey(id)) {
            return;
        }

        try {
            // Note: We don't unregister hooks from JVMTI as that would require
            // retransforming classes. Instead, hooks check isEnabled() at runtime.

            adapter.onDeactivated();
            activeAdapters.remove(id);
            SidekickLog.i(TAG, "Deactivated adapter: " + id);

        } catch (Exception e) {
            SidekickLog.e(TAG, "Error deactivating adapter: " + id, e);
        }
    }

    private static void tryRetransformClass(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            JvmtiAgent.retransformClass(clazz);
            SidekickLog.d(TAG, "Retransformed class: " + className);
        } catch (ClassNotFoundException e) {
            // Class not loaded yet - hook will apply when loaded
            SidekickLog.d(TAG, "Class not loaded yet: " + className);
        } catch (Exception e) {
            SidekickLog.w(TAG, "Failed to retransform class: " + className, e);
        }
    }
}
