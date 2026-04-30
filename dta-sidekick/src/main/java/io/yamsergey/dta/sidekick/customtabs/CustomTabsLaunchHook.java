package io.yamsergey.dta.sidekick.customtabs;

import io.yamsergey.dta.sidekick.SidekickLog;
import io.yamsergey.dta.sidekick.jvmti.MethodHook;
import io.yamsergey.dta.sidekick.server.InspectorServer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * JVMTI hook for intercepting Chrome Custom Tabs launches.
 *
 * <p>This hook intercepts calls to {@code CustomTabsIntent.launchUrl(Context, Uri)}
 * to detect when the app opens a URL in Chrome Custom Tabs.</p>
 *
 * <p>When a Custom Tab is launched, this hook:</p>
 * <ul>
 *   <li>Captures the URL being opened</li>
 *   <li>Extracts any custom headers set via the Builder</li>
 *   <li>Records the event via {@link CustomTabsInspector}</li>
 * </ul>
 *
 * <p>External tools can then use this information to connect to Chrome's
 * DevTools Protocol and capture the actual network traffic.</p>
 */
public class CustomTabsLaunchHook implements MethodHook {

    private static final String TAG = "CustomTabsHook";

    /**
     * Set to a non-zero count while {@code CustomTabsIntent.launchUrl} is on
     * the current thread's stack. Read by {@code ActivityStartActivityForResultHook}
     * to skip the boot-class hook for launches we're already broadcasting via
     * the launchUrl path — without this dedup, a single Custom Tabs launch
     * would emit both {@code customtab_will_launch} and {@code chrome_will_launch}.
     *
     * <p>Counter (not boolean) so the marker survives any hypothetical
     * re-entry without false-clearing. Cleared in {@link #onExit} <b>and</b>
     * {@link #onException} so a thrown launchUrl doesn't strand the marker
     * and suppress unrelated future launches on this thread.</p>
     *
     * <p>Note: must NOT be substituted by an EXTRA_SESSION check on the
     * Intent. Auth0's CustomTabsController builds a CustomTabsIntent (which
     * sets EXTRA_SESSION on its inner Intent) and then bypasses launchUrl
     * by calling context.startActivity(intent) on the bare Intent. An
     * extras-based check would silently skip those launches — exactly the
     * regression that motivated this ThreadLocal approach.</p>
     */
    public static final ThreadLocal<int[]> ACTIVE_LAUNCH_URL_DEPTH =
            ThreadLocal.withInitial(() -> new int[1]);

    /** True iff the current thread is inside a CustomTabsIntent.launchUrl call. */
    public static boolean isInsideLaunchUrl() {
        return ACTIVE_LAUNCH_URL_DEPTH.get()[0] > 0;
    }

    @Override
    public String getTargetClass() {
        return "androidx.browser.customtabs.CustomTabsIntent";
    }

    @Override
    public String getTargetMethod() {
        return "launchUrl";
    }

    @Override
    public String getMethodSignature() {
        // launchUrl(Context context, Uri url)
        return "(Landroid/content/Context;Landroid/net/Uri;)V";
    }

    @Override
    public String getId() {
        return "customtabs-launch";
    }

    @Override
    public void onEnter(Object thisObj, Object[] args) {
        // Mark this thread as "inside launchUrl" before any other work — we
        // need the marker visible even if we early-return below, otherwise
        // the boot hook (which fires later in the same call stack via
        // ContextCompat.startActivity) would broadcast a duplicate event.
        ACTIVE_LAUNCH_URL_DEPTH.get()[0]++;
        try {
            if (args.length < 2) {
                SidekickLog.w(TAG, "launchUrl called with insufficient arguments");
                return;
            }

            Object context = args[0];
            Object uri = args[1];

            if (uri == null) {
                SidekickLog.w(TAG, "launchUrl called with null URI");
                return;
            }

            // Get URL string
            String url = uri.toString();

            // Extract package name from context
            String packageName = getPackageName(context);

            // Try to extract custom headers from the CustomTabsIntent
            Map<String, String> headers = extractHeaders(thisObj);

            // Create and record the event
            CustomTabEvent event = new CustomTabEvent(url, headers, packageName);
            CustomTabsInspector.recordEvent(event);

            // Block until CDP is attached (if capture is armed)
            boolean cdpArmed = InspectorServer.getInstance().waitForCdpAckIfNeeded(event);

            // Replace URI with about:blank so CDP can connect before the real page loads.
            // The server will navigate to the original URL via Page.navigate after
            // attaching CDP and enabling Network capture.
            //
            // Note (2026-04-30): we tried swapping to a data:text/html URI to
            // serve a diagnostic "loading" page but Android refuses
            // Intent.ACTION_VIEW with data: schemes — even for CCT, since
            // CustomTabsIntent.launchUrl ultimately dispatches via ACTION_VIEW.
            // For a proper escape-hatch UI we need the sidekick HTTP server
            // to expose a TCP listener and use http://127.0.0.1:PORT/loading
            // instead. about:blank stays in the meantime.
            if (cdpArmed) {
                try {
                    Class<?> uriClass = Class.forName("android.net.Uri");
                    Method parse = uriClass.getMethod("parse", String.class);
                    args[1] = parse.invoke(null, "about:blank");
                    SidekickLog.d(TAG, "Replaced URI with about:blank for CDP capture");
                } catch (Exception e) {
                    SidekickLog.w(TAG, "Failed to replace URI with about:blank", e);
                }
            }

            SidekickLog.i(TAG, ">>> Custom Tab: " + url +
                    (headers.isEmpty() ? "" : " (headers: " + headers.size() + ")"));

        } catch (Throwable t) {
            SidekickLog.e(TAG, "Error in onEnter", t);
        }
    }

    @Override
    public Object onExit(Object thisObj, Object result) {
        decrementDepth();
        return result;
    }

    @Override
    public Throwable onException(Object thisObj, Throwable throwable) {
        decrementDepth();
        return throwable;
    }

    /**
     * Decrements the active-launchUrl counter on the current thread, with a
     * floor at zero. Defensive against any hypothetical onExit/onException
     * fire that wasn't matched by an onEnter — keeps the marker monotonic
     * so the boot hook's check stays correct across the lifetime of the
     * thread.
     */
    private static void decrementDepth() {
        int[] depth = ACTIVE_LAUNCH_URL_DEPTH.get();
        if (depth[0] > 0) {
            depth[0]--;
        }
    }

    /**
     * Extracts custom headers from the CustomTabsIntent.
     *
     * <p>Headers are set via CustomTabsIntent.Builder.addExtraHeader() and
     * stored in the intent's extras bundle.</p>
     */
    private Map<String, String> extractHeaders(Object customTabsIntent) {
        Map<String, String> headers = new HashMap<>();

        try {
            // Get the underlying Intent from CustomTabsIntent
            Object intent = getField(customTabsIntent, "intent");
            if (intent == null) {
                return headers;
            }

            // Get the extras Bundle
            Method getExtras = intent.getClass().getMethod("getExtras");
            Object extras = getExtras.invoke(intent);
            if (extras == null) {
                return headers;
            }

            // Try to get the headers bundle
            // Keys can be:
            // - "android.support.customtabs.extra.EXTRA_HEADERS" (old support lib)
            // - "androidx.browser.customtabs.extra.EXTRA_HEADERS" (androidx)
            // - "com.android.browser.headers" (Browser.EXTRA_HEADERS)
            String[] headerKeys = {
                "android.support.customtabs.extra.EXTRA_HEADERS",
                "androidx.browser.customtabs.extra.EXTRA_HEADERS",
                "com.android.browser.headers"
            };

            for (String key : headerKeys) {
                try {
                    Method getBundle = extras.getClass().getMethod("getBundle", String.class);
                    Object headerBundle = getBundle.invoke(extras, key);
                    if (headerBundle != null) {
                        extractHeadersFromBundle(headerBundle, headers);
                        break;
                    }
                } catch (Exception ignored) {}
            }

        } catch (Exception e) {
            SidekickLog.w(TAG, "Failed to extract headers", e);
        }

        return headers;
    }

    /**
     * Extracts headers from a Bundle into a Map.
     */
    private void extractHeadersFromBundle(Object bundle, Map<String, String> headers) {
        try {
            Method keySet = bundle.getClass().getMethod("keySet");
            @SuppressWarnings("unchecked")
            java.util.Set<String> keys = (java.util.Set<String>) keySet.invoke(bundle);

            if (keys != null) {
                for (String key : keys) {
                    try {
                        Method getString = bundle.getClass().getMethod("getString", String.class);
                        String value = (String) getString.invoke(bundle, key);
                        if (value != null) {
                            headers.put(key, value);
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            SidekickLog.w(TAG, "Failed to extract headers from bundle", e);
        }
    }

    /**
     * Gets the package name from a Context.
     */
    private String getPackageName(Object context) {
        try {
            Method getPackageName = context.getClass().getMethod("getPackageName");
            return (String) getPackageName.invoke(context);
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Gets a field value using reflection.
     */
    private Object getField(Object obj, String fieldName) {
        try {
            Field field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(obj);
        } catch (Exception e) {
            // Try public field
            try {
                Field field = obj.getClass().getField(fieldName);
                return field.get(obj);
            } catch (Exception e2) {
                return null;
            }
        }
    }
}
