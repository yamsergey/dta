package io.yamsergey.dta.sidekick.init;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Debug;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipFile;

/**
 * Self-contained bootstrap of the dta-sidekick native agent + the
 * boot-classpath shim. Runs from {@link BootstrapShimProvider}'s
 * ContentProvider.onCreate, which attaches before AndroidX Startup so
 * SidekickInitializer's class link finds the shim already in place.
 *
 * <p><b>Constraint that drives this file's design:</b> nothing here may
 * reference {@code MethodHook} / {@code HookRegistry} / {@code HookDispatcher}
 * (or any class that does, e.g. {@code JvmtiAgent}). Doing so would trigger
 * a class-link of those types at the moment {@code BootstrapShim} is
 * loaded — which is precisely what we're trying to make resolvable. Stick
 * to {@code java.*} + {@code android.*} stdlib.</p>
 *
 * <p>Side effects after a successful {@link #install} call:</p>
 * <ul>
 *   <li>{@code libsidekick-agent.so} is loaded into the process</li>
 *   <li>The native agent is attached as a JVMTI agent (capabilities + globals
 *       set up via {@code Agent_OnAttach})</li>
 *   <li>{@code dta-shim.jar} is on the bootstrap classloader's search path
 *       so subsequent class loads of {@code MethodHook} etc. resolve from
 *       there</li>
 *   <li>{@link #attached()} returns true — JvmtiAgent.initialize uses this
 *       to skip a second attach</li>
 * </ul>
 */
public final class BootstrapShim {

    private static final String TAG = "DtaBootShim";
    private static final String AGENT_LIB_NAME = "libsidekick-agent.so";
    private static final String SHIM_ASSET = "dta-shim.jar";

    private static volatile boolean attached = false;
    private static volatile String agentPath = null;
    /**
     * Last failure or skip reason, machine-readable. Read by
     * {@code /debug/diagnostics} and the AS plugin so the user can see
     * why inspection isn't working without grepping logcat. Values are
     * stable strings — don't reword without checking call-sites.
     *
     * <p>Possible values:
     * {@code "ok"}, {@code "api_too_low"}, {@code "not_debuggable"},
     * {@code "agent_so_missing"}, {@code "system_load_failed"},
     * {@code "attach_jvmti_failed"}, {@code "shim_jar_extract_failed"},
     * {@code "add_to_bootstrap_failed"}, {@code "init_class_transformer_failed"},
     * {@code "unknown_error"}, {@code "not_run_yet"}.</p>
     */
    private static volatile String lastReason = "not_run_yet";
    /** Free-form detail for {@link #lastReason} when applicable (exception messages, return codes). */
    private static volatile String lastDetail = null;

    private BootstrapShim() {}

    /**
     * Returns true once {@link #install} has fully succeeded. Other parts
     * of sidekick (notably {@code JvmtiAgent.initialize}) check this to
     * avoid re-attaching the agent — JVMTI rejects a second attach.
     */
    public static boolean attached() {
        return attached;
    }

    /**
     * Returns the absolute path of the loaded agent .so, or null if
     * {@link #install} hasn't run yet.
     */
    public static String agentPath() {
        return agentPath;
    }

    /** @see #lastReason */
    public static String lastReason() {
        return lastReason;
    }

    /** @see #lastDetail */
    public static String lastDetail() {
        return lastDetail;
    }

    private static void recordFailure(String reason, String detail) {
        lastReason = reason;
        lastDetail = detail;
    }

    /**
     * Idempotent — calling twice is a no-op. Failures are logged + return
     * false; the host app keeps running with whatever capabilities loaded
     * before the failure point. Sets {@link #lastReason} / {@link #lastDetail}
     * to a machine-readable string in every exit branch so callers can
     * decide what to do (or surface it to the user via /debug/diagnostics).
     */
    public static synchronized boolean install(Context context) {
        if (attached) return true;
        Log.i(TAG, "Bootstrap shim install starting (api=" + Build.VERSION.SDK_INT + ")");
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.w(TAG, "Skipping shim install: API " + Build.VERSION.SDK_INT
                    + " < P (28). JVMTI agents require Pie or newer.");
            recordFailure("api_too_low", "SDK_INT=" + Build.VERSION.SDK_INT);
            return false;
        }
        boolean debuggable = (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (!debuggable) {
            Log.w(TAG, "Skipping shim install: app is not debuggable. "
                    + "dta-sidekick must be added via debugImplementation, not implementation.");
            recordFailure("not_debuggable", null);
            return false;
        }

        try {
            // 1. Locate / extract the agent .so. Android typically puts it
            //    in nativeLibraryDir for ABIs the device supports; if not
            //    (multi-APK / app-bundle / etc.) we extract from the APK.
            File agentLib = new File(context.getApplicationInfo().nativeLibraryDir, AGENT_LIB_NAME);
            if (!agentLib.exists()) {
                agentLib = extractAgentFromApk(context);
                if (agentLib == null) {
                    Log.w(TAG, "Could not locate or extract " + AGENT_LIB_NAME);
                    recordFailure("agent_so_missing",
                            "primary nativeLibraryDir=" + context.getApplicationInfo().nativeLibraryDir);
                    return false;
                }
            }

            // 2. Load the library + attach as a JVMTI agent. Both go
            //    through the same path so JNI symbol lookup and JVMTI
            //    state stay coherent.
            String soPath = agentLib.getAbsolutePath();
            try {
                System.load(soPath);
            } catch (Throwable t) {
                Log.w(TAG, "System.load failed for " + soPath, t);
                recordFailure("system_load_failed", t.toString());
                return false;
            }
            try {
                Debug.attachJvmtiAgent(soPath, null, BootstrapShim.class.getClassLoader());
            } catch (Throwable t) {
                Log.w(TAG, "Debug.attachJvmtiAgent failed for " + soPath, t);
                recordFailure("attach_jvmti_failed", t.toString());
                return false;
            }
            agentPath = soPath;

            // 3. Extract the shim asset and add it to the bootstrap
            //    classpath. Ordering: must happen AFTER agent attach
            //    (the JNI symbol lives in the .so) and BEFORE any class
            //    that references MethodHook gets linked.
            File shimDest = new File(context.getCodeCacheDir(), SHIM_ASSET);
            try {
                extractAssetIfNeeded(context, SHIM_ASSET, shimDest);
            } catch (Throwable t) {
                Log.w(TAG, "Failed to extract " + SHIM_ASSET, t);
                recordFailure("shim_jar_extract_failed", t.toString());
                return false;
            }
            int rc = nativeAddToBootstrapClassLoaderSearch(shimDest.getAbsolutePath());
            if (rc != 0) {
                Log.w(TAG, "AddToBootstrapClassLoaderSearch returned " + rc);
                // Don't flip attached=true — without the shim, future
                // boot-class hook bytecode would NCDFE. Better to leave
                // the system in a "no boot hooks" state than partially
                // broken.
                recordFailure("add_to_bootstrap_failed", "rc=" + rc);
                return false;
            }

            // 4. Now that HookDispatcher / DexTransformer are reachable from
            //    the bootstrap classloader, initialize the native class
            //    transformer's Java method ID cache. Doing this earlier
            //    (in JNI_OnLoad) would cache null pointers and disable
            //    every subsequent transformation silently.
            try {
                nativeInitClassTransformer();
            } catch (Throwable t) {
                Log.w(TAG, "nativeInitClassTransformer failed", t);
                recordFailure("init_class_transformer_failed", t.toString());
                return false;
            }

            attached = true;
            lastReason = "ok";
            lastDetail = null;
            Log.i(TAG, "Bootstrap shim installed: " + shimDest.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Bootstrap shim install failed", t);
            recordFailure("unknown_error", t.toString());
            return false;
        }
    }

    private static File extractAgentFromApk(Context context) {
        // Mirrors the logic in JvmtiAgent.extractAgentFromApk but inlined
        // here to keep BootstrapShim self-contained. Tries each ABI from
        // the host APK's lib/ entries and picks the first match.
        File destDir = new File(context.getCodeCacheDir(), "jvmti");
        if (!destDir.exists() && !destDir.mkdirs()) {
            return null;
        }
        File dest = new File(destDir, AGENT_LIB_NAME);
        String apkPath = context.getApplicationInfo().sourceDir;
        try (ZipFile apk = new ZipFile(apkPath)) {
            // Prefer the device's primary ABI.
            String[] abis = Build.SUPPORTED_ABIS;
            for (String abi : abis) {
                String entry = "lib/" + abi + "/" + AGENT_LIB_NAME;
                java.util.zip.ZipEntry zipEntry = apk.getEntry(entry);
                if (zipEntry == null) continue;
                try (InputStream in = apk.getInputStream(zipEntry);
                     FileOutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                return dest;
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to extract agent from APK", e);
        }
        return null;
    }

    private static void extractAssetIfNeeded(Context context, String asset, File dest) throws IOException {
        try (InputStream probe = context.getAssets().open(asset)) {
            if (dest.exists() && dest.length() == probe.available()) {
                return;
            }
        } catch (IOException ignored) {
            // available() can underreport — fall through to unconditional copy.
        }
        try (InputStream in = context.getAssets().open(asset);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    private static native int nativeAddToBootstrapClassLoaderSearch(String jarAbsolutePath);

    /**
     * Initialize the native ClassTransformer's Java method ID cache.
     * Must be called only after {@link #nativeAddToBootstrapClassLoaderSearch}
     * has succeeded — otherwise FindClass(HookDispatcher) caches null.
     */
    private static native void nativeInitClassTransformer();
}
