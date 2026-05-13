package io.yamsergey.dta.sidekick.init;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;

import io.yamsergey.dta.sidekick.Sidekick;
import io.yamsergey.dta.sidekick.SidekickLog;
import io.yamsergey.dta.sidekick.compose.RecompositionHooks;
import io.yamsergey.dta.sidekick.jvmti.JvmtiAgent;
import io.yamsergey.dta.sidekick.network.adapter.NetworkInterceptorManager;
import io.yamsergey.dta.sidekick.webview.WebViewDebugHook;

/**
 * JVMTI agent + hook registration, isolated from {@link SidekickInitializer}
 * so the initializer's class link doesn't transitively need
 * {@code io.yamsergey.dta.sidekick.jvmti.MethodHook} (which lives in the
 * {@code dta-sidekick-shim} jar and is on the bootstrap classpath only
 * after {@link BootstrapShim#install} succeeds).
 *
 * <p>This class is loaded reflectively from {@link SidekickInitializer}
 * <em>only when</em> {@link BootstrapShim#attached()} is true, so a stale
 * APK / failed shim install no longer NCDFEs the host on launch — the
 * server still comes up, just without inspection capability, and the
 * {@code /health} response advertises shim status so clients can warn
 * the user.</p>
 *
 * <p>Everything that references {@code MethodHook}, {@link JvmtiAgent},
 * {@link NetworkInterceptorManager}, or any concrete hook subclass MUST
 * live here, never in {@link SidekickInitializer}. Adding a new hook?
 * Add the registration here.</p>
 */
public final class SidekickJvmtiBoot {

    private static final String TAG = "ADT-Sidekick";

    private SidekickJvmtiBoot() {}

    /**
     * Initializes the JVMTI agent and registers all built-in hook
     * categories. Called only when the bootstrap shim is attached;
     * silently degrades any single category whose registration throws so
     * one bad hook doesn't take the rest down with it.
     */
    public static void start(Context context) {
        // Re-check API + debuggable here too — caller has already gated
        // on shim, but we want this method robust in isolation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            SidekickLog.i(TAG, "JVMTI agent requires API 28+ (current: " + Build.VERSION.SDK_INT + ")");
            return;
        }
        boolean isDebuggable = (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (!isDebuggable) {
            SidekickLog.i(TAG, "JVMTI agent requires debuggable app");
            return;
        }

        boolean success = JvmtiAgent.initialize(context);
        if (!success) {
            String error = JvmtiAgent.getInitError();
            SidekickLog.w(TAG, "JVMTI agent initialization failed: " + (error != null ? error : "unknown"));
            return;
        }

        SidekickLog.i(TAG, "JVMTI agent initialized successfully");
        registerNetworkHooks();
        registerWebViewHooks();
        registerNavigationHooks();
        registerRecompositionHooks();
    }

    private static void registerNetworkHooks() {
        try {
            Sidekick.applyConfiguration();
            NetworkInterceptorManager.initialize();
            SidekickLog.i(TAG, "Network hooks registered via NetworkInterceptorManager");
        } catch (Exception e) {
            SidekickLog.e(TAG, "Failed to register network hooks", e);
        }
    }

    private static void registerWebViewHooks() {
        try {
            JvmtiAgent.registerHook(new WebViewDebugHook());
            SidekickLog.i(TAG, "WebView debug hook registered");
        } catch (Exception e) {
            SidekickLog.e(TAG, "Failed to register WebView debug hook", e);
        }
    }

    private static void registerNavigationHooks() {
        try {
            JvmtiAgent.registerHook(new io.yamsergey.dta.sidekick.data.NavControllerHook());
            SidekickLog.i(TAG, "Navigation hook registered");
        } catch (Exception e) {
            SidekickLog.e(TAG, "Failed to register navigation hook", e);
        }
    }

    private static void registerRecompositionHooks() {
        if (!Sidekick.getConfig().isRecompositionHooksEnabled()) {
            SidekickLog.i(TAG, "Compose recomposition hooks disabled via "
                    + "SidekickConfig.Builder.disableRecompositionHooks().");
            return;
        }
        try {
            JvmtiAgent.registerHook(new RecompositionHooks.StartRestartGroupHook());
            JvmtiAgent.registerHook(new RecompositionHooks.SkipToGroupEndHook());
            SidekickLog.i(TAG, "Compose recomposition hooks registered");
        } catch (Exception e) {
            SidekickLog.e(TAG, "Failed to register recomposition hooks", e);
        }
    }
}
