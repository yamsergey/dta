package io.yamsergey.dta.sidekick.chromeintent;

import io.yamsergey.dta.sidekick.SidekickLog;
import io.yamsergey.dta.sidekick.jvmti.MethodHook;
import io.yamsergey.dta.sidekick.server.InspectorServer;

import java.lang.reflect.Method;

/**
 * JVMTI hook on {@code androidx.activity.ComponentActivity.startActivityForResult}.
 *
 * <p>Why startActivityForResult and not startActivity: {@code Activity.startActivity(Intent)}
 * is in the boot classloader and would require the bootclasspath shim to hook
 * (see {@code dta/sidekick-boot-class-hooks} skill). But {@code Activity.startActivity}
 * delegates to {@code startActivityForResult(intent, -1)} via virtual dispatch — and
 * {@code androidx.activity.ComponentActivity} overrides startActivityForResult in the
 * app classloader. So hooking the override here catches every {@code startActivity()}
 * call from any ComponentActivity-extending Activity (which is essentially every
 * modern Android app).</p>
 *
 * <p>Behavior:</p>
 * <ul>
 *   <li>onEnter: extract the {@link android.content.Intent}, check it's
 *       {@code ACTION_VIEW} with an http(s) URI, and broadcast a
 *       {@code chrome_will_launch} SSE event with the original URL.</li>
 *   <li>The hook does <b>not</b> resolve the target activity's package. Doing so
 *       would require the host app to declare {@code <queries>} in its manifest
 *       on Android 11+, which we won't impose. Broadcasting unconditionally is
 *       safe: the daemon's {@code findChromeSocket} returns null for non-Chrome
 *       targets and logs/returns gracefully.</li>
 *   <li>When capture is active (daemon connected and watcher armed), the hook
 *       swaps the Intent's URI to {@code about:blank}. about:blank loads with
 *       zero network requests, so the daemon has time to attach via autoAttach
 *       before any traffic happens. The daemon then issues
 *       {@code Page.navigate(realUrl)} on the captured session — this is the
 *       same blank-page trick Custom Tabs uses. When capture is <b>not</b>
 *       active, the Intent is left untouched: the host app must keep working
 *       even with no daemon connected.</li>
 *   <li>Two hook instances cover the two override signatures
 *       {@code (Intent, int)} and {@code (Intent, int, Bundle)}.</li>
 * </ul>
 *
 * <p>Coverage gap: Activity subclasses that don't extend ComponentActivity
 * (vanishing in modern Android), and {@code Context.startActivity} calls from
 * Services or BroadcastReceivers (rarely fire browser launches). Closing those
 * requires the bootclasspath shim.</p>
 */
public class ChromeIntentLaunchHook implements MethodHook {

    private static final String TAG = "ChromeIntentHook";

    private static final String ACTION_VIEW = "android.intent.action.VIEW";

    private final String targetMethodSignature;

    /** Hook for {@code startActivityForResult(Intent, int)}. */
    public ChromeIntentLaunchHook() {
        this("(Landroid/content/Intent;I)V");
    }

    /**
     * Constructor for the {@code (Intent, int, Bundle)} variant. Use
     * {@link #ChromeIntentLaunchHook()} for the 2-arg variant.
     */
    public ChromeIntentLaunchHook(String signature) {
        this.targetMethodSignature = signature;
    }

    @Override
    public String getTargetClass() {
        return "androidx.activity.ComponentActivity";
    }

    @Override
    public String getTargetMethod() {
        return "startActivityForResult";
    }

    @Override
    public String getMethodSignature() {
        return targetMethodSignature;
    }

    @Override
    public String getId() {
        // Make the id distinguishable so two hook instances (different
        // signatures) don't collide in the registry.
        boolean withBundle = targetMethodSignature.contains("Bundle");
        return "chrome-intent-launch-" + (withBundle ? "bundled" : "plain");
    }

    @Override
    public void onEnter(Object thisObj, Object[] args) {
        try {
            if (args == null || args.length < 1 || args[0] == null) {
                return;
            }
            Object intent = args[0];

            String action = invokeStringGetter(intent, "getAction");
            if (action == null || !ACTION_VIEW.equals(action)) {
                return;
            }

            Object uri = invokeGetter(intent, "getData");
            if (uri == null) {
                return;
            }
            String url = uri.toString();
            String scheme = invokeStringGetter(uri, "getScheme");
            if (scheme == null) {
                return;
            }
            scheme = scheme.toLowerCase(java.util.Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return;
            }

            String hostPackage = invokeStringGetter(thisObj, "getPackageName");
            ChromeLaunchEvent event = new ChromeLaunchEvent(url, hostPackage, null);

            // Decide whether to swap to about:blank BEFORE broadcasting, so the
            // daemon never sees a chrome_will_launch event for an Intent we
            // didn't actually mutate (which would cause it to wait for an
            // about:blank tab that never opens). With no daemon connected,
            // leave the Intent alone — the app's URL goes to Chrome unmodified.
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
     * Replaces the Intent's data URI with {@code about:blank} via
     * {@code Intent.setData(Uri.parse("about:blank"))}. Mutating the Intent
     * before the host method continues means the URI Chrome receives is
     * about:blank, not the original URL. Returns false if reflection fails;
     * the caller should treat that as "no swap" and not signal the daemon to
     * navigate a non-existent blank tab.
     */
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
            Method m = obj.getClass().getMethod(methodName);
            return m.invoke(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String invokeStringGetter(Object obj, String methodName) {
        Object result = invokeGetter(obj, methodName);
        return result == null ? null : result.toString();
    }
}
