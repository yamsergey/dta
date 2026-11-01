package io.yamsergey.dta.shim;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads framework fields and calls framework methods the app's own code may not reach (feature
 * 015, research R2). Living on the boot class path, the shim is not filtered by hidden-API
 * checks; the exemption stays here, for the names the sidekick asks for, and never changes how
 * the app's own reflection behaves. Every lookup is cached; a missing member throws
 * {@link NoSuchFieldException} / {@link NoSuchMethodException} for the caller to state.
 */
public final class ShimReflect {

    private static final ConcurrentHashMap<String, Field> FIELDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Method> METHODS = new ConcurrentHashMap<>();

    private ShimReflect() {}

    /** The value of field [name] declared on [target]'s class or a superclass. */
    public static Object field(Object target, String name) throws ReflectiveOperationException {
        return find(target.getClass(), name).get(target);
    }

    /** The value of static field [name] of [cls]. */
    public static Object staticField(Class<?> cls, String name) throws ReflectiveOperationException {
        return find(cls, name).get(null);
    }

    /** Calls the no-argument method [name] on [target] (or statically on [cls] when target is null). */
    public static Object call(Class<?> cls, Object target, String name) throws ReflectiveOperationException {
        String key = cls.getName() + "#" + name;
        Method method = METHODS.get(key);
        if (method == null) {
            method = cls.getDeclaredMethod(name);
            method.setAccessible(true);
            METHODS.put(key, method);
        }
        return method.invoke(target);
    }

    /** "name descriptor" of every method [cls] declares with [name], for picking a hook target. */
    public static String[] descriptors(Class<?> cls, String name) {
        Method[] all = cls.getDeclaredMethods();
        int count = 0;
        for (Method m : all) if (m.getName().equals(name)) count++;
        String[] out = new String[count];
        int i = 0;
        for (Method m : all) {
            if (!m.getName().equals(name)) continue;
            StringBuilder d = new StringBuilder("(");
            for (Class<?> p : m.getParameterTypes()) d.append(descriptor(p));
            d.append(')').append(descriptor(m.getReturnType()));
            out[i++] = d.toString();
        }
        return out;
    }

    private static Field find(Class<?> start, String name) throws NoSuchFieldException {
        String key = start.getName() + "." + name;
        Field cached = FIELDS.get(key);
        if (cached != null) return cached;
        for (Class<?> c = start; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                FIELDS.put(key, f);
                return f;
            } catch (NoSuchFieldException ignored) {
                // keep looking in the superclass
            }
        }
        throw new NoSuchFieldException(start.getName() + "." + name);
    }

    private static String descriptor(Class<?> c) {
        if (c.isArray()) return "[" + descriptor(c.getComponentType());
        if (c == void.class) return "V";
        if (c == boolean.class) return "Z";
        if (c == byte.class) return "B";
        if (c == char.class) return "C";
        if (c == short.class) return "S";
        if (c == int.class) return "I";
        if (c == long.class) return "J";
        if (c == float.class) return "F";
        if (c == double.class) return "D";
        return "L" + c.getName().replace('.', '/') + ";";
    }
}
