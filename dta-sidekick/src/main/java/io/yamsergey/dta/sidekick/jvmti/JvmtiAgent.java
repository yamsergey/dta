package io.yamsergey.dta.sidekick.jvmti;

import android.content.Context;
import android.os.Build;
import android.os.Debug;
import io.yamsergey.dta.sidekick.SidekickLog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Manages the JVMTI native agent for method hooking.
 *
 * <p>This class handles loading the native agent via {@link Debug#attachJvmtiAgent}
 * and provides methods to register hooks and retransform classes.</p>
 *
 * <h3>Requirements</h3>
 * <ul>
 *   <li>Android API 28+ (Android 9 Pie)</li>
 *   <li>App must be debuggable ({@code android:debuggable="true"})</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // Initialize the agent (typically in Application.onCreate or via Startup)
 * JvmtiAgent.initialize(context);
 *
 * // Register a hook
 * JvmtiAgent.registerHook(new MyMethodHook());
 *
 * // Retransform already-loaded classes if needed
 * JvmtiAgent.retransformClass(OkHttpClient.class);
 * }</pre>
 */
public final class JvmtiAgent {

    private static final String TAG = "JvmtiAgent";
    private static final String AGENT_LIB_NAME = "libsidekick-agent.so";

    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private static final AtomicBoolean agentLoaded = new AtomicBoolean(false);
    private static final AtomicReference<String> initError = new AtomicReference<>();

    // Prevent instantiation
    private JvmtiAgent() {}

    /**
     * Initializes the JVMTI agent.
     *
     * <p>This must be called before any hooks can be registered. It loads the
     * native agent library and attaches it to the current VM.</p>
     *
     * <p>Safe to call multiple times - subsequent calls are no-ops.</p>
     *
     * @param context application context
     * @return true if agent was loaded successfully, false otherwise
     */
    public static boolean initialize(Context context) {
        if (!initialized.compareAndSet(false, true)) {
            // Already initialized
            return agentLoaded.get();
        }

        // Check API level
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            String error = "JVMTI agent requires API 28+ (current: " + Build.VERSION.SDK_INT + ")";
            SidekickLog.w(TAG, error);
            initError.set(error);
            return false;
        }

        // Find the agent library - first try the native lib directory
        String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
        File agentLib = new File(nativeLibDir, AGENT_LIB_NAME);

        if (!agentLib.exists()) {
            // Library not extracted - extract it from APK
            SidekickLog.d(TAG, "Library not in nativeLibDir, extracting from APK...");
            agentLib = extractAgentFromApk(context);

            if (agentLib == null) {
                String error = "Failed to extract agent library from APK";
                SidekickLog.e(TAG, error);
                initError.set(error);
                return false;
            }
        }

        try {
            String agentPath = agentLib.getAbsolutePath();

            // Load the library for JNI using the same path that we'll use for JVMTI.
            // This ensures both JNI and JVMTI use the same library instance.
            SidekickLog.i(TAG, "Loading native library: " + agentPath);
            System.load(agentPath);

            // Attach the JVMTI agent using the same path
            SidekickLog.i(TAG, "Attaching JVMTI agent: " + agentPath);
            Debug.attachJvmtiAgent(agentPath, null, JvmtiAgent.class.getClassLoader());

            // Register the transformer hook
            nativeRegisterTransformerHook();

            agentLoaded.set(true);
            SidekickLog.i(TAG, "JVMTI agent loaded successfully");
            return true;

        } catch (Exception e) {
            String error = "Failed to attach JVMTI agent: " + e.getMessage();
            SidekickLog.e(TAG, error, e);
            initError.set(error);
            return false;
        }
    }

    /**
     * Extracts the agent library from the APK to a file on disk.
     *
     * <p>This is needed when extractNativeLibs is false (default on modern AGP),
     * because Debug.attachJvmtiAgent() requires a real file path, not a path
     * inside the APK.</p>
     */
    private static File extractAgentFromApk(Context context) {
        String apkPath = context.getApplicationInfo().sourceDir;
        String abi = Build.SUPPORTED_ABIS[0]; // Primary ABI

        // Possible library paths in the APK
        String[] possiblePaths = {
            "lib/" + abi + "/" + AGENT_LIB_NAME,
            "lib/arm64-v8a/" + AGENT_LIB_NAME,
            "lib/armeabi-v7a/" + AGENT_LIB_NAME,
            "lib/x86_64/" + AGENT_LIB_NAME,
            "lib/x86/" + AGENT_LIB_NAME
        };

        try (ZipFile apk = new ZipFile(apkPath)) {
            ZipEntry libEntry = null;

            // Find the library entry
            for (String path : possiblePaths) {
                libEntry = apk.getEntry(path);
                if (libEntry != null) {
                    SidekickLog.d(TAG, "Found library in APK at: " + path);
                    break;
                }
            }

            if (libEntry == null) {
                // Try to find it by scanning
                Enumeration<? extends ZipEntry> entries = apk.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.getName().endsWith(AGENT_LIB_NAME)) {
                        libEntry = entry;
                        SidekickLog.d(TAG, "Found library in APK by scan: " + entry.getName());
                        break;
                    }
                }
            }

            if (libEntry == null) {
                SidekickLog.e(TAG, "Library not found in APK");
                return null;
            }

            // Extract to app's code cache directory (allows execution)
            File outputDir = new File(context.getCodeCacheDir(), "jvmti");
            if (!outputDir.exists() && !outputDir.mkdirs()) {
                SidekickLog.e(TAG, "Failed to create output directory");
                return null;
            }

            File outputFile = new File(outputDir, AGENT_LIB_NAME);

            // Check if already extracted and up-to-date
            if (outputFile.exists() && outputFile.length() == libEntry.getSize()) {
                SidekickLog.d(TAG, "Using previously extracted library");
                return outputFile;
            }

            // Extract the library
            try (InputStream in = apk.getInputStream(libEntry);
                 FileOutputStream out = new FileOutputStream(outputFile)) {

                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                }
            }

            // Make executable
            if (!outputFile.setExecutable(true, false)) {
                SidekickLog.w(TAG, "Failed to set executable permission");
            }

            SidekickLog.i(TAG, "Extracted library to: " + outputFile.getAbsolutePath());
            return outputFile;

        } catch (IOException e) {
            SidekickLog.e(TAG, "Failed to extract library from APK", e);
            return null;
        }
    }

    /**
     * Checks if the JVMTI agent is available and loaded.
     *
     * @return true if the agent is ready to use
     */
    public static boolean isAvailable() {
        return agentLoaded.get();
    }

    /**
     * Gets the initialization error message, if any.
     *
     * @return error message, or null if no error
     */
    public static String getInitError() {
        return initError.get();
    }

    /**
     * Sets whether native debug logging is enabled.
     *
     * <p>When disabled (the default), the native agent will not print
     * debug/info/warning messages to logcat. Error messages are always printed.</p>
     *
     * @param enabled true to enable debug logging
     */
    public static void setDebugEnabled(boolean enabled) {
        if (agentLoaded.get()) {
            try {
                nativeSetDebugEnabled(enabled);
            } catch (UnsatisfiedLinkError e) {
                // Native method not available - ignore
                SidekickLog.w(TAG, "nativeSetDebugEnabled not available");
            }
        }
    }

    /**
     * Registers a method hook.
     *
     * <p>The hook will be applied to classes loaded after this call.
     * For already-loaded classes, call {@link #retransformClass(Class)} after registration.</p>
     *
     * @param hook the hook to register
     * @return the hook ID
     * @throws IllegalStateException if agent is not initialized
     */
    public static String registerHook(MethodHook hook) {
        String hookId = HookRegistry.registerHook(hook);

        // If agent is loaded, notify native side about the new hook
        if (agentLoaded.get()) {
            nativeAddHookTarget(
                    hook.getTargetClass().replace('.', '/'),
                    hook.getTargetMethod(),
                    hook.getMethodSignature(),
                    hookId
            );
        }

        return hookId;
    }

    /**
     * Unregisters a method hook.
     *
     * <p>Note: This does not revert bytecode transformations. The transformed
     * code will simply no-op when the hook is not found.</p>
     *
     * @param hookId the hook ID to unregister
     * @return true if the hook was found and removed
     */
    public static boolean unregisterHook(String hookId) {
        return HookRegistry.unregisterHook(hookId);
    }

    /**
     * Requests retransformation of a class.
     *
     * <p>Use this to apply hooks to classes that were already loaded before
     * the hook was registered.</p>
     *
     * @param clazz the class to retransform
     * @return true if retransformation was requested successfully
     */
    public static boolean retransformClass(Class<?> clazz) {
        if (!agentLoaded.get()) {
            SidekickLog.w(TAG, "Cannot retransform: agent not loaded");
            return false;
        }

        try {
            nativeRetransformClasses(new Class<?>[] { clazz });
            SidekickLog.d(TAG, "Requested retransformation of: " + clazz.getName());
            return true;
        } catch (Exception e) {
            SidekickLog.e(TAG, "Failed to retransform class: " + clazz.getName(), e);
            return false;
        }
    }

    /**
     * Requests retransformation of multiple classes.
     *
     * @param classes the classes to retransform
     * @return true if retransformation was requested successfully
     */
    public static boolean retransformClasses(Class<?>... classes) {
        if (!agentLoaded.get()) {
            SidekickLog.w(TAG, "Cannot retransform: agent not loaded");
            return false;
        }

        try {
            nativeRetransformClasses(classes);
            return true;
        } catch (Exception e) {
            SidekickLog.e(TAG, "Failed to retransform classes", e);
            return false;
        }
    }

    // ==========================================================================
    // Native methods - implemented in libsidekick-agent.so
    // ==========================================================================

    /**
     * Registers the class file load hook with JVMTI.
     * Called once during initialization.
     */
    private static native void nativeRegisterTransformerHook();

    /**
     * Adds a hook target to the native transformer.
     *
     * @param className   internal class name (e.g., "okhttp3/OkHttpClient")
     * @param methodName  method name
     * @param methodSig   method signature (JVM format)
     * @param hookId      unique hook identifier
     */
    private static native void nativeAddHookTarget(
            String className,
            String methodName,
            String methodSig,
            String hookId
    );

    /**
     * Requests retransformation of the specified classes.
     *
     * @param classes array of classes to retransform
     */
    private static native void nativeRetransformClasses(Class<?>[] classes);

    /**
     * Sets native debug logging enabled/disabled.
     *
     * @param enabled true to enable debug logging
     */
    private static native void nativeSetDebugEnabled(boolean enabled);

    // ==========================================================================
    // Callbacks from native code
    // ==========================================================================

    /**
     * Called by native code to check if a class should be transformed.
     *
     * @param className internal class name (e.g., "okhttp3/OkHttpClient")
     * @return true if there are hooks for this class
     */
    @SuppressWarnings("unused") // Called from native
    private static boolean shouldTransformClass(String className) {
        String dotName = className.replace('/', '.');
        return HookRegistry.hasHooksForClass(dotName);
    }

    /**
     * Called by native code to get hook information for bytecode injection.
     *
     * @param className  internal class name
     * @param methodName method name
     * @param methodSig  method signature
     * @return hook ID if a hook exists, null otherwise
     */
    @SuppressWarnings("unused") // Called from native
    private static String getHookId(String className, String methodName, String methodSig) {
        String dotName = className.replace('/', '.');
        for (MethodHook hook : HookRegistry.getHooksForClass(dotName)) {
            if (hook.getTargetMethod().equals(methodName)) {
                String hookSig = hook.getMethodSignature();
                if (hookSig == null || hookSig.isEmpty() || hookSig.equals(methodSig)) {
                    String id = hook.getId();
                    if (id == null || id.isEmpty()) {
                        // Generate ID
                        String sigPart = (hookSig != null && !hookSig.isEmpty()) ? hookSig : "*";
                        id = dotName + "#" + methodName + sigPart;
                    }
                    return id;
                }
            }
        }
        return null;
    }

    // Note: We don't use a static initializer to load the library.
    // Instead, we load it in initialize() using the same path as Debug.attachJvmtiAgent
    // to ensure both JNI and JVMTI use the same library instance.
}
