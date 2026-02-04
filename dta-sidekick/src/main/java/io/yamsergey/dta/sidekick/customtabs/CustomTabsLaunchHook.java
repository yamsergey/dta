package io.yamsergey.dta.sidekick.customtabs;

import io.yamsergey.dta.sidekick.SidekickLog;
import io.yamsergey.dta.sidekick.jvmti.MethodHook;

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

            SidekickLog.i(TAG, ">>> Custom Tab: " + url +
                    (headers.isEmpty() ? "" : " (headers: " + headers.size() + ")"));

        } catch (Throwable t) {
            SidekickLog.e(TAG, "Error in onEnter", t);
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
            // Key is "android.support.customtabs.extra.EXTRA_HEADERS" or
            // "androidx.browser.customtabs.extra.EXTRA_HEADERS"
            String[] headerKeys = {
                "android.support.customtabs.extra.EXTRA_HEADERS",
                "androidx.browser.customtabs.extra.EXTRA_HEADERS"
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
