package io.yamsergey.dta.shim;

import java.util.List;

/**
 * Static targets of the bytecode slicer injects into hooked methods. Lives on the
 * bootstrap class path so boot classes (java.net.URL) can call it.
 *
 * <p>FROZEN ABI (constitution I): the names and descriptors below are baked into
 * transformed bytecode by the native agent. Add overloads, never change these.
 * <ul>
 *   <li>entry: {@code onEntry([Ljava/lang/Object;)V} — slicer EntryHook ArrayParams:
 *       {@code [label, this-or-null, params...]}</li>
 *   <li>exit: {@code onExit(Ljava/lang/String;X)X} for X in Object (ReturnAsObject) and
 *       every primitive, and {@code onExit(Ljava/lang/String;)V} — slicer ExitHook
 *       PassMethodSignature</li>
 * </ul>
 *
 * <p>Nothing here may throw into the host app: handler failures are counted in
 * {@link HookRegistry} and the original value is returned. A handler that calls a hooked
 * method on the same thread is not re-entered. No lambdas, default or static interface
 * methods: d8 desugaring of those broke the original's shim (AbstractMethodError).
 */
public final class HookDispatcher {

    private static final ThreadLocal<boolean[]> IN_HOOK = new ThreadLocal<boolean[]>() {
        @Override
        protected boolean[] initialValue() {
            return new boolean[1];
        }
    };

    private HookDispatcher() {}

    public static void onEntry(Object[] args) {
        if (args == null || args.length < 2 || !(args[0] instanceof String)) return;
        String label = (String) args[0];
        List<EntryHandler> handlers = HookRegistry.entryHandlers(label);
        if (handlers == null || handlers.isEmpty()) return;
        boolean[] inHook = IN_HOOK.get();
        if (inHook[0]) return;
        inHook[0] = true;
        try {
            Object[] params = new Object[args.length - 2];
            System.arraycopy(args, 2, params, 0, params.length);
            for (EntryHandler handler : handlers) {
                try {
                    handler.onEntry(args[1], params);
                } catch (Throwable t) {
                    HookRegistry.recordError(label, t);
                }
            }
        } finally {
            inHook[0] = false;
        }
    }

    public static Object onExit(String label, Object result) {
        return dispatchExit(label, result, null);
    }

    public static void onExit(String label) {
        dispatchExit(label, null, null);
    }

    public static boolean onExit(String label, boolean result) {
        return (Boolean) dispatchExit(label, result, Boolean.class);
    }

    public static byte onExit(String label, byte result) {
        return (Byte) dispatchExit(label, result, Byte.class);
    }

    public static char onExit(String label, char result) {
        return (Character) dispatchExit(label, result, Character.class);
    }

    public static short onExit(String label, short result) {
        return (Short) dispatchExit(label, result, Short.class);
    }

    public static int onExit(String label, int result) {
        return (Integer) dispatchExit(label, result, Integer.class);
    }

    public static long onExit(String label, long result) {
        return (Long) dispatchExit(label, result, Long.class);
    }

    public static float onExit(String label, float result) {
        return (Float) dispatchExit(label, result, Float.class);
    }

    public static double onExit(String label, double result) {
        return (Double) dispatchExit(label, result, Double.class);
    }

    /** @param primitiveBox the box class a replacement must have, or null for reference/void */
    private static Object dispatchExit(String label, Object result, Class<?> primitiveBox) {
        List<ExitHandler> handlers = HookRegistry.exitHandlers(label);
        if (handlers == null || handlers.isEmpty()) return result;
        boolean[] inHook = IN_HOOK.get();
        if (inHook[0]) return result;
        inHook[0] = true;
        try {
            Object current = result;
            for (ExitHandler handler : handlers) {
                try {
                    Object replaced = handler.onExit(current);
                    if (primitiveBox != null && (replaced == null || replaced.getClass() != primitiveBox)) {
                        HookRegistry.recordError(label, new ClassCastException(
                                "exit handler returned " + (replaced == null ? "null" : replaced.getClass().getName())
                                        + " for a " + primitiveBox.getName() + " method"));
                    } else {
                        current = replaced;
                    }
                } catch (Throwable t) {
                    HookRegistry.recordError(label, t);
                }
            }
            return current;
        } finally {
            inHook[0] = false;
        }
    }
}
