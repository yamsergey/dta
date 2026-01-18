package io.yamsergey.adt.sidekick.jvmti;

import io.yamsergey.adt.sidekick.SidekickLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry for managing method hooks.
 *
 * <p>Hooks are registered here and looked up by {@link HookDispatcher}
 * when transformed bytecode invokes hook callbacks.</p>
 *
 * <p>This class is thread-safe. Hooks can be registered and unregistered
 * at any time, even while other hooks are executing.</p>
 */
public final class HookRegistry {

    private static final String TAG = "HookRegistry";

    /**
     * All registered hooks, keyed by hook ID.
     */
    private static final Map<String, MethodHook> hooks = new ConcurrentHashMap<>();

    /**
     * Hooks indexed by target class name for efficient lookup during transformation.
     */
    private static final Map<String, List<MethodHook>> hooksByClass = new ConcurrentHashMap<>();

    /**
     * Listeners notified when hooks are registered/unregistered.
     */
    private static final List<HookListener> listeners = new CopyOnWriteArrayList<>();

    // Prevent instantiation
    private HookRegistry() {}

    /**
     * Registers a method hook.
     *
     * <p>If a hook with the same ID is already registered, it will be replaced.</p>
     *
     * <p>Note: For the hook to take effect on already-loaded classes, you must
     * call {@link JvmtiAgent#retransformClass(Class)} after registration.</p>
     *
     * @param hook the hook to register
     * @return the hook ID (auto-generated if not specified)
     */
    public static String registerHook(MethodHook hook) {
        if (hook == null) {
            throw new IllegalArgumentException("Hook cannot be null");
        }

        String id = getOrCreateId(hook);
        MethodHook previous = hooks.put(id, hook);

        // Update class index
        String className = hook.getTargetClass();
        hooksByClass.computeIfAbsent(className, k -> new CopyOnWriteArrayList<>()).add(hook);

        // Remove previous hook from class index if replaced
        if (previous != null) {
            List<MethodHook> classHooks = hooksByClass.get(previous.getTargetClass());
            if (classHooks != null) {
                classHooks.remove(previous);
            }
        }

        SidekickLog.d(TAG, "Registered hook: " + id + " -> " + className + "." + hook.getTargetMethod());

        // Notify listeners
        for (HookListener listener : listeners) {
            try {
                listener.onHookRegistered(id, hook);
            } catch (Throwable t) {
                SidekickLog.e(TAG, "Error in hook listener", t);
            }
        }

        return id;
    }

    /**
     * Unregisters a method hook by ID.
     *
     * @param hookId the hook ID to unregister
     * @return true if a hook was removed, false if not found
     */
    public static boolean unregisterHook(String hookId) {
        MethodHook removed = hooks.remove(hookId);
        if (removed != null) {
            List<MethodHook> classHooks = hooksByClass.get(removed.getTargetClass());
            if (classHooks != null) {
                classHooks.remove(removed);
            }

            SidekickLog.d(TAG, "Unregistered hook: " + hookId);

            // Notify listeners
            for (HookListener listener : listeners) {
                try {
                    listener.onHookUnregistered(hookId, removed);
                } catch (Throwable t) {
                    SidekickLog.e(TAG, "Error in hook listener", t);
                }
            }

            return true;
        }
        return false;
    }

    /**
     * Gets a hook by ID.
     *
     * @param hookId the hook ID
     * @return the hook, or null if not found
     */
    public static MethodHook getHook(String hookId) {
        return hooks.get(hookId);
    }

    /**
     * Gets all hooks targeting a specific class.
     *
     * <p>This is used during class transformation to determine which
     * methods need hook injection.</p>
     *
     * @param className the fully qualified class name
     * @return list of hooks (empty if none), never null
     */
    public static List<MethodHook> getHooksForClass(String className) {
        List<MethodHook> classHooks = hooksByClass.get(className);
        return classHooks != null ? Collections.unmodifiableList(classHooks) : Collections.emptyList();
    }

    /**
     * Gets all registered hooks.
     *
     * @return unmodifiable map of hook ID to hook
     */
    public static Map<String, MethodHook> getAllHooks() {
        return Collections.unmodifiableMap(hooks);
    }

    /**
     * Gets all class names that have registered hooks.
     *
     * @return set of class names with hooks
     */
    public static java.util.Set<String> getHookedClasses() {
        return Collections.unmodifiableSet(hooksByClass.keySet());
    }

    /**
     * Checks if any hooks are registered for a class.
     *
     * @param className the fully qualified class name
     * @return true if at least one hook targets this class
     */
    public static boolean hasHooksForClass(String className) {
        List<MethodHook> classHooks = hooksByClass.get(className);
        return classHooks != null && !classHooks.isEmpty();
    }

    /**
     * Clears all registered hooks.
     */
    public static void clearAllHooks() {
        hooks.clear();
        hooksByClass.clear();
        SidekickLog.d(TAG, "Cleared all hooks");
    }

    /**
     * Adds a listener to be notified of hook registration changes.
     *
     * @param listener the listener to add
     */
    public static void addListener(HookListener listener) {
        listeners.add(listener);
    }

    /**
     * Removes a hook listener.
     *
     * @param listener the listener to remove
     */
    public static void removeListener(HookListener listener) {
        listeners.remove(listener);
    }

    /**
     * Generates or retrieves the hook ID.
     */
    private static String getOrCreateId(MethodHook hook) {
        String customId = hook.getId();
        if (customId != null && !customId.isEmpty()) {
            return customId;
        }

        // Auto-generate ID from class+method+signature
        String sig = hook.getMethodSignature();
        String sigPart = (sig != null && !sig.isEmpty()) ? sig : "*";
        return hook.getTargetClass() + "#" + hook.getTargetMethod() + sigPart;
    }

    /**
     * Listener interface for hook registration events.
     */
    public interface HookListener {
        /**
         * Called when a hook is registered.
         *
         * @param hookId the hook ID
         * @param hook   the registered hook
         */
        void onHookRegistered(String hookId, MethodHook hook);

        /**
         * Called when a hook is unregistered.
         *
         * @param hookId the hook ID
         * @param hook   the unregistered hook
         */
        void onHookUnregistered(String hookId, MethodHook hook);
    }
}
