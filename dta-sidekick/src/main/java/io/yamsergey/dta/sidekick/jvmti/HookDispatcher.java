package io.yamsergey.dta.sidekick.jvmti;

import io.yamsergey.dta.sidekick.SidekickLog;

/**
 * Dispatcher that routes method hook invocations to registered hooks.
 *
 * <p>This class is called by transformed bytecode injected by the JVMTI agent.
 * It looks up the appropriate hook and invokes its callbacks.</p>
 *
 * <p><b>IMPORTANT:</b> This class must remain stable as its methods are called
 * directly from injected bytecode. Changing method signatures will break hooks.</p>
 */
public final class HookDispatcher {

    private static final String TAG = "HookDispatcher";

    // Prevent instantiation
    private HookDispatcher() {}

    /**
     * Called by injected bytecode before the original method executes.
     *
     * <p>Bytecode injection format:</p>
     * <pre>
     * // At method entry:
     * invokestatic HookDispatcher.onEnter(hookId, this, args)
     * </pre>
     *
     * @param hookId  unique identifier for the registered hook
     * @param thisObj the 'this' reference (null for static methods)
     * @param args    the method arguments as an Object array
     */
    public static void onEnter(String hookId, Object thisObj, Object[] args) {
        try {
            MethodHook hook = HookRegistry.getHook(hookId);
            if (hook != null && hook.isEnabled()) {
                hook.onEnter(thisObj, args != null ? args : new Object[0]);
            }
        } catch (Throwable t) {
            // Never let hook errors crash the app
            SidekickLog.e(TAG, "Error in hook onEnter: " + hookId, t);
        }
    }

    /**
     * Called by injected bytecode after the original method executes successfully.
     *
     * <p>Bytecode injection format:</p>
     * <pre>
     * // Before return:
     * invokestatic HookDispatcher.onExit(hookId, this, result) -> result
     * </pre>
     *
     * @param hookId  unique identifier for the registered hook
     * @param thisObj the 'this' reference (null for static methods)
     * @param result  the original return value (null for void methods)
     * @return the (possibly modified) return value
     */
    public static Object onExit(String hookId, Object thisObj, Object result) {
        try {
            MethodHook hook = HookRegistry.getHook(hookId);
            if (hook != null && hook.isEnabled()) {
                return hook.onExit(thisObj, result);
            }
        } catch (Throwable t) {
            // Never let hook errors crash the app
            SidekickLog.e(TAG, "Error in hook onExit: " + hookId, t);
        }
        return result;
    }

    /**
     * Called by injected bytecode when the original method throws an exception.
     *
     * <p>Bytecode injection format:</p>
     * <pre>
     * // In catch block:
     * invokestatic HookDispatcher.onException(hookId, this, throwable) -> throwable
     * if (result != null) throw result;
     * </pre>
     *
     * @param hookId    unique identifier for the registered hook
     * @param thisObj   the 'this' reference (null for static methods)
     * @param throwable the exception that was thrown
     * @return the exception to propagate, or null to suppress
     */
    public static Throwable onException(String hookId, Object thisObj, Throwable throwable) {
        try {
            MethodHook hook = HookRegistry.getHook(hookId);
            if (hook != null && hook.isEnabled()) {
                return hook.onException(thisObj, throwable);
            }
        } catch (Throwable t) {
            // Never let hook errors crash the app
            SidekickLog.e(TAG, "Error in hook onException: " + hookId, t);
        }
        return throwable;
    }

    // ==========================================================================
    // Primitive-specific overloads for better bytecode generation
    // ==========================================================================

    /**
     * Overload for methods returning int.
     */
    public static int onExitInt(String hookId, Object thisObj, int result) {
        Object modified = onExit(hookId, thisObj, result);
        return (modified instanceof Number) ? ((Number) modified).intValue() : result;
    }

    /**
     * Overload for methods returning long.
     */
    public static long onExitLong(String hookId, Object thisObj, long result) {
        Object modified = onExit(hookId, thisObj, result);
        return (modified instanceof Number) ? ((Number) modified).longValue() : result;
    }

    /**
     * Overload for methods returning boolean.
     */
    public static boolean onExitBoolean(String hookId, Object thisObj, boolean result) {
        Object modified = onExit(hookId, thisObj, result);
        return (modified instanceof Boolean) ? (Boolean) modified : result;
    }

    /**
     * Overload for methods returning float.
     */
    public static float onExitFloat(String hookId, Object thisObj, float result) {
        Object modified = onExit(hookId, thisObj, result);
        return (modified instanceof Number) ? ((Number) modified).floatValue() : result;
    }

    /**
     * Overload for methods returning double.
     */
    public static double onExitDouble(String hookId, Object thisObj, double result) {
        Object modified = onExit(hookId, thisObj, result);
        return (modified instanceof Number) ? ((Number) modified).doubleValue() : result;
    }

    /**
     * Overload for void methods.
     */
    public static void onExitVoid(String hookId, Object thisObj) {
        onExit(hookId, thisObj, null);
    }
}
