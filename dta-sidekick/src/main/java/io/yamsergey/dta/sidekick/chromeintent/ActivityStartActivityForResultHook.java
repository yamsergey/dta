package io.yamsergey.dta.sidekick.chromeintent;

import io.yamsergey.dta.sidekick.SidekickLog;
import io.yamsergey.dta.sidekick.jvmti.MethodHook;
import io.yamsergey.dta.sidekick.server.InspectorServer;

import java.lang.reflect.Method;

/**
 * Boot-class hook on
 * {@code android.app.Activity.startActivityForResult(Intent, int, Bundle)}.
 *
 * <p><b>Why this and not the old ComponentActivity hook:</b> the previous
 * implementation hooked {@code androidx.activity.ComponentActivity.startActivityForResult}
 * (an app-classloader class) to sidestep the bootstrap-classloader requirement.
 * That covered every {@code ComponentActivity}-extending Activity, but missed
 * subclasses of plain {@code android.app.Activity} — Auth0's
 * {@code AuthenticationActivity} is the canonical case. With the bootstrap
 * shim in place, we can hook {@code android.app.Activity} directly and catch
 * every Activity launch in the process.</p>
 *
 * <p><b>Why only the 3-arg overload:</b> {@code startActivity(Intent)},
 * {@code startActivity(Intent, Bundle)}, and {@code startActivityForResult(Intent, int)}
 * all delegate to {@code startActivityForResult(Intent, int, Bundle)} —
 * hooking only the funnel point fires once per user call instead of 2-3
 * times. Verified empirically: with three hooks installed for
 * {@code Launch in Chrome (Intent.ACTION_VIEW)}, the event fired twice;
 * with this single hook it fires exactly once.</p>
 *
 * <p><b>Filtering:</b> only Intents with action {@code ACTION_VIEW} and
 * an {@code http(s)} data URI propagate. An Activity launches dozens of
 * unrelated intents per screen — startup, lifecycle, permission flows —
 * and we don't want any of those to reach the daemon.</p>
 *
 * <p><b>Mutation:</b> when CDP capture is active, the Intent's data URI
 * is rewritten to {@code about:blank}. The daemon attaches via
 * {@code Target.setAutoAttach}, then issues {@code Page.navigate(realUrl)}
 * once Network capture is enabled. Without the swap, the first page load
 * would race with CDP attach and we'd miss the initial requests. Same
 * trick as the Custom Tabs path, same correctness guarantee.</p>
 */
public final class ActivityStartActivityForResultHook implements MethodHook {

    private static final String TAG = "ChromeIntentHook";
    private static final String ACTION_VIEW = "android.intent.action.VIEW";

    @Override public String getTargetClass() { return "android.app.Activity"; }
    @Override public String getTargetMethod() { return "startActivityForResult"; }
    @Override public String getMethodSignature() { return "(Landroid/content/Intent;ILandroid/os/Bundle;)V"; }
    @Override public String getId() { return "chrome-intent-launch-boot"; }

    @Override
    public void onEnter(Object thisObj, Object[] args) {
        try {
            if (args == null || args.length < 1 || args[0] == null) {
                return;
            }
            Object intent = args[0];

            String action = invokeStringGetter(intent, "getAction");
            if (!ACTION_VIEW.equals(action)) {
                return;
            }

            // Skip Custom Tabs — those are handled by CustomTabsLaunchHook on
            // the launchUrl entry point. CustomTabsIntent.launchUrl ultimately
            // calls Activity.startActivity, so without this check the boot
            // hook would double-broadcast every Custom Tab launch (one
            // customtab_will_launch + one chrome_will_launch event). Detection
            // is the session extra that CustomTabsIntent.Builder always sets.
            if (hasCustomTabsExtra(intent)) {
                return;
            }

            Object uri = invokeGetter(intent, "getData");
            if (uri == null) {
                return;
            }
            String scheme = invokeStringGetter(uri, "getScheme");
            if (scheme == null) return;
            scheme = scheme.toLowerCase(java.util.Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return;
            }

            String url = uri.toString();
            String hostPackage = invokeStringGetter(thisObj, "getPackageName");
            ChromeLaunchEvent event = new ChromeLaunchEvent(url, hostPackage, null);

            // Swap to about:blank BEFORE broadcasting, so we never tell the
            // daemon to look for an about:blank tab that won't exist (would
            // happen if reflection failed). Without an active CDP capture
            // session we leave the Intent untouched — the host app's launch
            // must keep working with no daemon connected.
            boolean swapped = false;
            if (InspectorServer.getInstance().isCdpCaptureActive()) {
                swapped = swapIntentUriToBlank(intent);
            }

            InspectorServer.getInstance().broadcastChromeLaunchEvent(event);
            SidekickLog.d(TAG, "chrome intent: " + url + (swapped ? " (swapped to about:blank)" : ""));
        } catch (Throwable t) {
            // Hooks must never throw out of onEnter — the host app proceeds
            // unmodified on any reflection / SSE failure here.
            SidekickLog.e(TAG, "Error in onEnter", t);
        }
    }

    /**
     * Detects Custom Tabs intents by the presence of the session extra that
     * {@code CustomTabsIntent.Builder} always sets. Two key variants exist:
     * legacy {@code android.support.customtabs.extra.SESSION} and the
     * AndroidX {@code androidx.browser.customtabs.extra.SESSION}. Either
     * one is conclusive — if it's present, this Intent came from
     * {@code CustomTabsIntent} and the {@code launchUrl} hook has already
     * processed it.
     */
    private static boolean hasCustomTabsExtra(Object intent) {
        try {
            Method hasExtra = intent.getClass().getMethod("hasExtra", String.class);
            Boolean a = (Boolean) hasExtra.invoke(intent, "android.support.customtabs.extra.SESSION");
            if (Boolean.TRUE.equals(a)) return true;
            Boolean b = (Boolean) hasExtra.invoke(intent, "androidx.browser.customtabs.extra.SESSION");
            return Boolean.TRUE.equals(b);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean swapIntentUriToBlank(Object intent) {
        try {
            Class<?> uriClass = Class.forName("android.net.Uri");
            Method parse = uriClass.getMethod("parse", String.class);
            Object blankUri = parse.invoke(null, "about:blank");
            Method setData = intent.getClass().getMethod("setData", uriClass);
            setData.invoke(intent, blankUri);
            return true;
        } catch (Throwable t) {
            SidekickLog.w(TAG, "Failed to swap Intent URI to about:blank: " + t.getMessage());
            return false;
        }
    }

    private static Object invokeGetter(Object obj, String methodName) {
        try {
            return obj.getClass().getMethod(methodName).invoke(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String invokeStringGetter(Object obj, String methodName) {
        Object result = invokeGetter(obj, methodName);
        return result == null ? null : result.toString();
    }
}
