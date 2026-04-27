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

    /**
     * Idempotent — calling twice is a no-op. Failures are logged + return
     * false; the host app keeps running with whatever capabilities loaded
     * before the failure point.
     */
    public static synchronized boolean install(Context context) {
        if (attached) return true;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return false;
        }
        boolean debuggable = (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (!debuggable) {
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
                    return false;
                }
            }

            // 2. Load the library + attach as a JVMTI agent. Both go
            //    through the same path so JNI symbol lookup and JVMTI
            //    state stay coherent.
            String soPath = agentLib.getAbsolutePath();
            System.load(soPath);
            Debug.attachJvmtiAgent(soPath, null, BootstrapShim.class.getClassLoader());
            agentPath = soPath;

            // 3. Extract the shim asset and add it to the bootstrap
            //    classpath. Ordering: must happen AFTER agent attach
            //    (the JNI symbol lives in the .so) and BEFORE any class
            //    that references MethodHook gets linked.
            File shimDest = new File(context.getCodeCacheDir(), SHIM_ASSET);
            extractAssetIfNeeded(context, SHIM_ASSET, shimDest);
            int rc = nativeAddToBootstrapClassLoaderSearch(shimDest.getAbsolutePath());
            if (rc != 0) {
                Log.w(TAG, "AddToBootstrapClassLoaderSearch returned " + rc);
                // Don't flip attached=true — without the shim, future
                // boot-class hook bytecode would NCDFE. Better to leave
                // the system in a "no boot hooks" state than partially
                // broken.
                return false;
            }

            // 4. Now that HookDispatcher / DexTransformer are reachable from
            //    the bootstrap classloader, initialize the native class
            //    transformer's Java method ID cache. Doing this earlier
            //    (in JNI_OnLoad) would cache null pointers and disable
            //    every subsequent transformation silently.
            nativeInitClassTransformer();

            attached = true;
            Log.i(TAG, "Bootstrap shim installed: " + shimDest.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Bootstrap shim install failed", t);
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
