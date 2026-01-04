package io.yamsergey.adt.sidekick.init;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.startup.Initializer;

import java.util.Collections;
import java.util.List;

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
 * </ul>
 */
public class SidekickInitializer implements Initializer<InspectorServer> {

    private static final String TAG = "ADT-Sidekick";
    private static final int DEFAULT_PORT = 8642;

    @NonNull
    @Override
    public InspectorServer create(@NonNull Context context) {
        Log.i(TAG, "Initializing ADT Sidekick...");

        InspectorServer server = InspectorServer.getInstance();

        try {
            server.start(DEFAULT_PORT);
            Log.i(TAG, "ADT Sidekick server started on port " + DEFAULT_PORT);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start ADT Sidekick server", e);
        }

        return server;
    }

    @NonNull
    @Override
    public List<Class<? extends Initializer<?>>> dependencies() {
        // No dependencies
        return Collections.emptyList();
    }
}
