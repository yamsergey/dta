package io.yamsergey.dta.sidekick.webview;

import io.yamsergey.dta.sidekick.SidekickLog;
import io.yamsergey.dta.sidekick.jvmti.MethodHook;

import java.lang.reflect.Method;

/**
 * JVMTI hook that auto-enables WebView debugging on construction.
 *
 * <p>Intercepts all {@code android.webkit.WebView} constructors and calls
 * {@code WebView.setWebContentsDebuggingEnabled(true)} on first invocation.
 * This is required for Chrome DevTools Protocol access to the WebView's content,
 * which enables accessibility tree inspection from the host side.</p>
 *
 * <p>The hook is idempotent — the static method is only called once regardless
 * of how many WebView instances are created.</p>
 */
public class WebViewDebugHook implements MethodHook {

    private static final String TAG = "WebViewDebugHook";

    private volatile boolean debuggingEnabled = false;

    @Override
    public String getTargetClass() {
        return "android.webkit.WebView";
    }

    @Override
    public String getTargetMethod() {
        return "<init>";
    }

    @Override
    public String getMethodSignature() {
        // Hook all constructors
        return null;
    }

    @Override
    public String getId() {
        return "webview-debug";
    }

    @Override
    public Object onExit(Object thisObj, Object result) {
        if (!debuggingEnabled) {
            debuggingEnabled = true;
            try {
                Class<?> webViewClass = thisObj.getClass();
                // Walk up to find android.webkit.WebView (could be a subclass)
                while (webViewClass != null && !webViewClass.getName().equals("android.webkit.WebView")) {
                    webViewClass = webViewClass.getSuperclass();
                }
                if (webViewClass != null) {
                    Method setDebugging = webViewClass.getMethod(
                        "setWebContentsDebuggingEnabled", boolean.class);
                    setDebugging.invoke(null, true);
                    SidekickLog.i(TAG, "WebView debugging enabled via JVMTI hook");
                }
            } catch (Exception e) {
                SidekickLog.w(TAG, "Failed to enable WebView debugging: " + e.getMessage());
            }
        }
        return result;
    }
}
