package io.yamsergey.dta.shim;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handler chains keyed by method label, the string slicer passes to the hooks:
 * {@code <dotted class>-><name><descriptor>}, e.g.
 * {@code java.net.URL->openConnection()Ljava/net/URLConnection;}. Several handlers may
 * watch one method; they run in registration order, exit handlers each receiving the
 * previous handler's result.
 */
public final class HookRegistry {

    private static final ConcurrentHashMap<String, List<EntryHandler>> ENTRY = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, List<ExitHandler>> EXIT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> ERRORS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Throwable> LAST_ERROR = new ConcurrentHashMap<>();

    private HookRegistry() {}

    public static String label(String className, String method, String descriptor) {
        return className + "->" + method + descriptor;
    }

    public static void entry(String label, EntryHandler handler) {
        chain(ENTRY, label).add(handler);
    }

    public static void exit(String label, ExitHandler handler) {
        chain(EXIT, label).add(handler);
    }

    public static void remove(String label, Object handler) {
        List<EntryHandler> entries = ENTRY.get(label);
        if (entries != null) entries.remove(handler);
        List<ExitHandler> exits = EXIT.get(label);
        if (exits != null) exits.remove(handler);
    }

    private static <T> List<T> chain(ConcurrentHashMap<String, List<T>> map, String label) {
        List<T> list = map.get(label);
        if (list == null) {
            List<T> created = new CopyOnWriteArrayList<>();
            list = map.putIfAbsent(label, created);
            if (list == null) list = created;
        }
        return list;
    }

    public static long errorCount(String label) {
        AtomicLong count = ERRORS.get(label);
        return count == null ? 0 : count.get();
    }

    public static Throwable lastError(String label) {
        return LAST_ERROR.get(label);
    }

    public static void clear() {
        ENTRY.clear();
        EXIT.clear();
        ERRORS.clear();
        LAST_ERROR.clear();
    }

    static List<EntryHandler> entryHandlers(String label) {
        return ENTRY.get(label);
    }

    static List<ExitHandler> exitHandlers(String label) {
        return EXIT.get(label);
    }

    static void recordError(String label, Throwable error) {
        AtomicLong count = ERRORS.get(label);
        if (count == null) {
            AtomicLong created = new AtomicLong();
            count = ERRORS.putIfAbsent(label, created);
            if (count == null) count = created;
        }
        count.incrementAndGet();
        LAST_ERROR.put(label, error);
    }
}
