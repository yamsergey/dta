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
import io.yamsergey.dta.sidekick.jvmti.JvmtiAgent;
import io.yamsergey.dta.sidekick.network.BodyStorage;
import io.yamsergey.dta.sidekick.network.adapter.NetworkInterceptorManager;
import io.yamsergey.dta.sidekick.compose.RecompositionHooks;
import io.yamsergey.dta.sidekick.webview.WebViewDebugHook;
import io.yamsergey.dta.sidekick.server.InspectorServer;

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

        // Initialize JVMTI agent for method hooking (API 28+)
        initializeJvmtiAgent(context);

        // Start file logging if configured (must happen before server start)
        if (Sidekick.getConfig().isFileLoggingEnabled()) {
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
            SidekickLog.i(TAG, "JVMTI agent requires API 28+ (current: " + Build.VERSION.SDK_INT + ")");
            return;
        }

        // Check if app is debuggable
        boolean isDebuggable = (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (!isDebuggable) {
            SidekickLog.i(TAG, "JVMTI agent requires debuggable app");
            return;
        }

        // Initialize the agent
        boolean success = JvmtiAgent.initialize(context);

        if (success) {
            SidekickLog.i(TAG, "JVMTI agent initialized successfully");

            // Register network hooks
            registerNetworkHooks();

            // Register WebView debug hook
            registerWebViewHooks();

            // Register Compose recomposition tracking hooks
            registerRecompositionHooks();
        } else {
            String error = JvmtiAgent.getInitError();
            SidekickLog.w(TAG, "JVMTI agent initialization failed: " + (error != null ? error : "unknown"));
        }
    }

    /**
     * Registers JVMTI hooks for network interception.
     *
     * <p>Uses the NetworkInterceptorManager to register all enabled adapters
     * (OkHttp, URLConnection, WebSocket libraries) based on SidekickConfig.</p>
     */
    private void registerNetworkHooks() {
        try {
            // Apply user configuration before initializing adapters
            Sidekick.applyConfiguration();

            // Initialize the network interceptor manager (registers all enabled adapters)
            NetworkInterceptorManager.initialize();

            SidekickLog.i(TAG, "Network hooks registered via NetworkInterceptorManager");
        } catch (Exception e) {
            SidekickLog.e(TAG, "Failed to register network hooks", e);
        }
    }

    /**
     * Registers JVMTI hook to auto-enable WebView debugging.
     *
     * <p>This hook intercepts WebView constructors and calls
     * {@code WebView.setWebContentsDebuggingEnabled(true)}, which is required
     * for host-side CDP access to WebView content.</p>
     */
    private void registerWebViewHooks() {
        try {
            JvmtiAgent.registerHook(new WebViewDebugHook());
            SidekickLog.i(TAG, "WebView debug hook registered");
        } catch (Exception e) {
            SidekickLog.e(TAG, "Failed to register WebView debug hook", e);
        }
    }

    /**
     * Registers JVMTI hooks for tracking Compose recomposition counts.
     *
     * <p>Hooks {@code ComposerImpl.startRestartGroup(int)} and
     * {@code ComposerImpl.skipToGroupEnd()} to count recompositions and skips
     * per composable, matching Android Studio's Layout Inspector behavior.</p>
     */
    private void registerRecompositionHooks() {
        try {
            JvmtiAgent.registerHook(new RecompositionHooks.StartRestartGroupHook());
            JvmtiAgent.registerHook(new RecompositionHooks.SkipToGroupEndHook());
            SidekickLog.i(TAG, "Compose recomposition hooks registered");
        } catch (Exception e) {
            SidekickLog.e(TAG, "Failed to register recomposition hooks", e);
        }
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
