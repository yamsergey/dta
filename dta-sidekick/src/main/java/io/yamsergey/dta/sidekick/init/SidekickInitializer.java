package io.yamsergey.dta.sidekick.init;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import io.yamsergey.dta.sidekick.SidekickLog;

import androidx.annotation.NonNull;
import androidx.startup.Initializer;

import java.io.File;
import java.util.Collections;
import java.util.List;

import io.yamsergey.dta.sidekick.Sidekick;
import io.yamsergey.dta.sidekick.compose.ComposeInspector;
import io.yamsergey.dta.sidekick.events.EventStore;
import io.yamsergey.dta.sidekick.network.BodyStorage;
import io.yamsergey.dta.sidekick.server.InspectorServer;
// NOTE: do NOT add imports for any class that references MethodHook
// directly or transitively (JvmtiAgent, NetworkInterceptorManager,
// concrete hook classes, etc.). Those would force this initializer's
// class verifier to resolve MethodHook from the bootstrap classpath at
// load time — and if BootstrapShim hasn't installed the shim jar yet
// (stale APK, release build, etc.), the host app NCDFEs and crashes on
// launch. All hook-touching code lives in SidekickJvmtiBoot, loaded
// reflectively only when BootstrapShim.attached() returns true.

/**
 * AndroidX Startup initializer that automatically starts the ADT Sidekick server.
 *
 * <p>This initializer is triggered when the app starts, launching a local server
 * on a Unix domain socket that provides inspection endpoints for various Android components.</p>
 *
 * <h3>Socket Name</h3>
 * <p>The server listens on a Unix domain socket named: {@code dta_sidekick_{package_name}}</p>
 * <p>Connect via ADB port forwarding:</p>
 * <pre>{@code
 * adb forward tcp:8642 localabstract:dta_sidekick_com.example.app
 * }</pre>
 *
 * <h3>Configuration</h3>
 * <p>Configure Sidekick before initialization using {@link io.yamsergey.dta.sidekick.Sidekick#configure}:</p>
 * <pre>{@code
 * Sidekick.configure(SidekickConfig.builder()
 *     .disableUrlConnection()
 *     .addAdapter(new MyCustomAdapter())
 *     .build());
 * }</pre>
 *
 * <h3>Available endpoints:</h3>
 * <ul>
 *   <li>GET /health - Health check</li>
 *   <li>GET /compose/tree - Unified Compose tree</li>
 *   <li>GET /compose/select?x=N&amp;y=N - Hit test at coordinates</li>
 *   <li>GET /network/requests - Captured HTTP requests</li>
 *   <li>GET /network/requests/{id} - Single request details</li>
 *   <li>DELETE /network/clear - Clear captured requests</li>
 *   <li>GET /websocket/connections - WebSocket connections</li>
 *   <li>GET /websocket/connections/{id} - Single connection details</li>
 *   <li>GET/POST /selection/element - Get/set selected UI element</li>
 *   <li>GET/POST /selection/network - Get/set selected network request</li>
 *   <li>GET /events/stream - SSE stream for real-time updates</li>
 * </ul>
 *
 * <h3>Supported Network Libraries:</h3>
 * <ul>
 *   <li><b>HTTP:</b> OkHttp, URLConnection</li>
 *   <li><b>WebSocket:</b> OkHttp WebSocket, Java-WebSocket, nv-websocket-client</li>
 * </ul>
 */
public class SidekickInitializer implements Initializer<InspectorServer> {

    private static final String TAG = "ADT-Sidekick";

    @NonNull
    @Override
    public InspectorServer create(@NonNull Context context) {
        SidekickLog.i(TAG, "Initializing ADT Sidekick...");

        // Enable Compose inspection mode FIRST - must happen before any Compose UI is created
        // This enables CompositionData population for accurate composable names
        enableComposeInspection();

        // Initialize EventStore for binary event capture
        initializeEventStore(context);

        // Initialize BodyStorage for large HTTP body storage
        initializeBodyStorage(context);

        // Initialize JVMTI agent + hooks ONLY when the bootstrap shim is
        // attached. If shim install failed (stale AAR, release build,
        // etc.), the MethodHook class isn't on the bootstrap classpath
        // and any direct reference would NCDFE the verifier — so the
        // call goes through Class.forName so SidekickJvmtiBoot's class
        // load is deferred until we know it's safe.
        if (BootstrapShim.attached()) {
            try {
                Class.forName("io.yamsergey.dta.sidekick.init.SidekickJvmtiBoot")
                    .getMethod("start", Context.class)
                    .invoke(null, context);
            } catch (Throwable t) {
                SidekickLog.e(TAG, "JVMTI boot threw — running in degraded mode "
                        + "(server up, hooks disabled). " + t, t);
            }
        } else {
            SidekickLog.w(TAG, "BootstrapShim not attached (reason="
                    + BootstrapShim.lastReason()
                    + ") — running in degraded mode: server up, hooks disabled. "
                    + "Inspection capabilities (network capture, layout tree, "
                    + "etc.) will not work. Check /health → shim for the reason.");
        }

        // Start file logging if configured (must happen before server start).
        // Defaults to enabled — gated additionally by ApplicationInfo.FLAG_DEBUGGABLE
        // because sidekick is intended only for debuggable builds, and a stray
        // `implementation` (instead of `debugImplementation`) shouldn't write
        // logs in production. Same gate JVMTI uses.
        boolean debuggable = (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (Sidekick.getConfig().isFileLoggingEnabled() && debuggable) {
            File logFile = new File(context.getCacheDir(), SidekickLog.LOG_FILE_NAME);
            SidekickLog.startFileLogging(logFile);
            SidekickLog.i(TAG, "File logging enabled: " + logFile.getAbsolutePath());
        }

        // Start the server on Unix domain socket
        InspectorServer server = InspectorServer.getInstance();
        String packageName = context.getPackageName();

        try {
            server.start(packageName);
            SidekickLog.i(TAG, "ADT Sidekick server started on socket: dta_sidekick_" + packageName);
        } catch (Exception e) {
            SidekickLog.e(TAG, "Failed to start ADT Sidekick server", e);
        }

        // Wire interceptor persistence: filesDir is per-app, durable
        // across app restart (cacheDir would be too aggressive — OS or
        // user can wipe it). install/clear write/delete the file;
        // autoReinstallFromDisk picks it up on this and every future
        // app launch. Compile errors during auto-reinstall don't crash
        // sidekick — they're recorded to the ring buffer.
        try {
            File interceptorFile = new File(context.getFilesDir(), "dta-interceptor.js");
            io.yamsergey.dta.sidekick.interceptor.InterceptorRuntime runtime =
                io.yamsergey.dta.sidekick.interceptor.InterceptorRuntime.getInstance();
            runtime.setPersistenceFile(interceptorFile);
            runtime.autoReinstallFromDisk();
        } catch (Exception e) {
            SidekickLog.w(TAG, "Interceptor persistence init failed (non-fatal): " + e.getMessage());
        }

        return server;
    }

    /**
     * Enables Compose inspection mode to populate CompositionData for accurate composable names.
     *
     * <p>This sets {@code isDebugInspectorInfoEnabled = true} in the Compose runtime,
     * which causes the {@code inspection_slot_table_set} tag to be populated on ComposeViews.
     * This must be called BEFORE any Compose UI is created.</p>
     */
    private void enableComposeInspection() {
        try {
            boolean enabled = ComposeInspector.enableInspection();
            if (enabled) {
                SidekickLog.i(TAG, "Compose inspection mode enabled");
            } else {
                SidekickLog.w(TAG, "Compose inspection mode could not be enabled (Compose may not be available yet)");
            }
        } catch (Exception e) {
            SidekickLog.e(TAG, "Failed to enable Compose inspection mode", e);
        }
    }

    /**
     * Initializes the EventStore for binary event capture.
     */
    private void initializeEventStore(@NonNull Context context) {
        try {
            EventStore store = EventStore.getInstance(context);
            SidekickLog.i(TAG, "EventStore initialized: " + store.getRecordCount() + " existing events");
        } catch (Exception e) {
            SidekickLog.e(TAG, "Failed to initialize EventStore", e);
        }
    }

    /**
     * Initializes the BodyStorage for large HTTP body storage.
     */
    private void initializeBodyStorage(@NonNull Context context) {
        try {
            BodyStorage.init(context);
            SidekickLog.i(TAG, "BodyStorage initialized");
        } catch (Exception e) {
            SidekickLog.e(TAG, "Failed to initialize BodyStorage", e);
        }
    }

    @NonNull
    @Override
    public List<Class<? extends Initializer<?>>> dependencies() {
        // No dependencies
        return Collections.emptyList();
    }
}
