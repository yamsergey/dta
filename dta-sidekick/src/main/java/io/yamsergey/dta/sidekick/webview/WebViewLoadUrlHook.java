package io.yamsergey.dta.sidekick.webview;

import android.os.Process;
import io.yamsergey.dta.sidekick.SidekickLog;
import io.yamsergey.dta.sidekick.jvmti.MethodHook;
import io.yamsergey.dta.sidekick.server.InspectorServer;

import java.lang.reflect.Method;

/**
 * JVMTI hook on {@code android.webkit.WebView.loadUrl} that arms CDP network
 * capture on the host before the load proceeds.
 *
 * <p>Mirrors {@link io.yamsergey.dta.sidekick.customtabs.CustomTabsLaunchHook}
 * in intent but with a different mechanism: the WebView debug socket
 * ({@code webview_devtools_remote_<pid>}) is already open at {@code loadUrl}
 * time (opened via {@code setWebContentsDebuggingEnabled(true)} by
 * {@link WebViewDebugHook}), so the host can attach CDP + enable Network
 * <em>before</em> ACKing. We block the caller for up to 2 seconds to give the
 * host that window, then let the load proceed with capture armed.</p>
 *
 * <p>Sidekick tracks the last ACK per pid: further {@code loadUrl} calls on
 * the same pid within 10 seconds skip broadcast + block — capture is already
 * armed from the previous event.</p>
 *
 * <p>Hooks both overloads:</p>
 * <ul>
 *   <li>{@code loadUrl(String url)}</li>
 *   <li>{@code loadUrl(String url, Map<String, String> additionalHttpHeaders)}</li>
 * </ul>
 *
 * <p>{@code loadData} / {@code loadDataWithBaseURL} are intentionally NOT hooked
 * — their content doesn't drive a real network load in the normal sense.</p>
 */
public class WebViewLoadUrlHook implements MethodHook {

    private static final String TAG = "WebViewLoadUrlHook";

    @Override
    public String getTargetClass() {
        return "android.webkit.WebView";
    }

    @Override
    public String getTargetMethod() {
        return "loadUrl";
    }

    @Override
    public String getMethodSignature() {
        // null → match both loadUrl(String) and loadUrl(String, Map)
        return null;
    }

    @Override
    public String getId() {
        return "webview-load-url";
    }

    @Override
    public void onEnter(Object thisObj, Object[] args) {
        try {
            if (args == null || args.length == 0 || !(args[0] instanceof String)) {
                return;
            }
            String url = (String) args[0];
            if (url == null || url.isEmpty()) {
                return;
            }

            int pid = Process.myPid();
            String packageName = getPackageName(thisObj);
            WebViewLoadEvent event = new WebViewLoadEvent(pid, url, packageName);

            // Force to logcat regardless of debugEnabled for now — this is
            // the key signal that bytecode instrumentation actually reached us.
            android.util.Log.i(TAG, ">>> WebView loadUrl: " + url + " (pid=" + pid + ")");

            boolean captured = InspectorServer.getInstance().broadcastWebViewLoadAndWait(event);
            if (captured) {
                SidekickLog.d(TAG, "Capture armed for pid=" + pid + ", proceeding with load");
            } else {
                SidekickLog.d(TAG, "No capture for pid=" + pid + " (timeout / no subscribers), proceeding");
            }
        } catch (Throwable t) {
            // Never let a hook throw into the app — loadUrl must proceed either way.
            SidekickLog.e(TAG, "Error in onEnter", t);
        }
    }

    /**
     * Extracts the package name from the WebView's Context.
     */
    private String getPackageName(Object webView) {
        if (webView == null) return "unknown";
        try {
            Method getContext = webView.getClass().getMethod("getContext");
            Object context = getContext.invoke(webView);
            if (context == null) return "unknown";
            Method getPackageName = context.getClass().getMethod("getPackageName");
            Object pkg = getPackageName.invoke(context);
            return pkg != null ? pkg.toString() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }
}
