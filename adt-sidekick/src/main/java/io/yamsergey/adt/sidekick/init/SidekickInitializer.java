package io.yamsergey.adt.sidekick.init;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.startup.Initializer;

import java.util.Collections;
import java.util.List;

import io.yamsergey.adt.sidekick.jvmti.JvmtiAgent;
import io.yamsergey.adt.sidekick.network.OkHttpExecuteHook;
import io.yamsergey.adt.sidekick.server.InspectorServer;

/**
 * AndroidX Startup initializer that automatically starts the ADT Sidekick server.
 *
 * <p>This initializer is triggered when the app starts, launching a local HTTP server
 * that provides inspection endpoints for various Android components.</p>
 *
 * <p>Available endpoints:</p>
 * <ul>
 *   <li>GET /health - Health check</li>
 *   <li>GET /compose/hierarchy - Compose UI hierarchy</li>
 *   <li>GET /compose/semantics - Compose semantics tree</li>
 *   <li>GET /compose/tree - Unified Compose tree</li>
 *   <li>GET /network/requests - Captured network requests</li>
 *   <li>GET /network/requests/{id} - Single request details</li>
 *   <li>DELETE /network/clear - Clear captured requests</li>
 * </ul>
 */
public class SidekickInitializer implements Initializer<InspectorServer> {

    private static final String TAG = "ADT-Sidekick";
    private static final int DEFAULT_PORT = 8642;

    @NonNull
    @Override
    public InspectorServer create(@NonNull Context context) {
        Log.i(TAG, "Initializing ADT Sidekick...");

        // Initialize JVMTI agent for method hooking (API 28+)
        initializeJvmtiAgent(context);

        // Start the HTTP server
        InspectorServer server = InspectorServer.getInstance();

        try {
            server.start(DEFAULT_PORT);
            Log.i(TAG, "ADT Sidekick server started on port " + DEFAULT_PORT);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start ADT Sidekick server", e);
        }

        return server;
    }

    /**
     * Initializes the JVMTI agent for runtime method hooking.
     *
     * <p>Requirements:</p>
     * <ul>
     *   <li>Android API 28+ (Android 9 Pie)</li>
     *   <li>App must be debuggable</li>
     * </ul>
     */
    private void initializeJvmtiAgent(@NonNull Context context) {
        // Check API level
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.i(TAG, "JVMTI agent requires API 28+ (current: " + Build.VERSION.SDK_INT + ")");
            return;
        }

        // Check if app is debuggable
        boolean isDebuggable = (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (!isDebuggable) {
            Log.i(TAG, "JVMTI agent requires debuggable app");
            return;
        }

        // Initialize the agent
        boolean success = JvmtiAgent.initialize(context);

        if (success) {
            Log.i(TAG, "JVMTI agent initialized successfully");

            // Register network hooks
            registerNetworkHooks();
        } else {
            String error = JvmtiAgent.getInitError();
            Log.w(TAG, "JVMTI agent initialization failed: " + (error != null ? error : "unknown"));
        }
    }

    /**
     * Registers JVMTI hooks for network interception.
     */
    private void registerNetworkHooks() {
        try {
            // Register OkHttp execute hook
            String hookId = JvmtiAgent.registerHook(new OkHttpExecuteHook());
            Log.i(TAG, "Registered OkHttp hook: " + hookId);

            // Try to retransform OkHttp RealCall if already loaded
            try {
                Class<?> realCallClass = Class.forName("okhttp3.internal.connection.RealCall");
                JvmtiAgent.retransformClass(realCallClass);
                Log.i(TAG, "Retransformed RealCall class");
            } catch (ClassNotFoundException e) {
                // OkHttp not loaded yet - that's fine, hook will apply when loaded
                Log.d(TAG, "RealCall not loaded yet, hook will apply on first load");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to register network hooks", e);
        }
    }

    @NonNull
    @Override
    public List<Class<? extends Initializer<?>>> dependencies() {
        // No dependencies
        return Collections.emptyList();
    }
}
