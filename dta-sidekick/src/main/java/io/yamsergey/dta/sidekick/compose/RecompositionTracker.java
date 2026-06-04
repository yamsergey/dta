package io.yamsergey.dta.sidekick.compose;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Tracks recomposition counts <strong>per visual instance</strong> of a
 * composable.
 *
 * <p>The key is the slot-table Anchor identity returned by
 * {@code Composer.getRecomposeScopeIdentity()} — stable per call site
 * <em>per instance</em>, so 8 distinct LazyColumn items show 8
 * distinct counts (the bug in the previous, group-key-keyed tracker
 * was that all instances of the same composable function collapsed
 * into one bucket).</p>
 *
 * <p>This mirrors {@code androidx.compose.ui.inspection.recompositions.RecompositionHandler}
 * from the Android Studio ui-inspection AAR — confirmed by dexdump:
 * AS hooks the same two methods we do and stores counts keyed by
 * {@code Composer.getRecomposeScopeIdentity()} on a per-Anchor
 * {@code HashMap<Object, RecompositionData>}.</p>
 *
 * <p>Populated by {@link RecompositionHooks}; consumed by
 * {@code ComposeInspector} during the slot-table walk, where each
 * {@code CompositionGroup}'s identity is looked up to find its
 * counts.</p>
 */
public final class RecompositionTracker {

    /** Anchor identity → [recomposeCount, skipCount]. */
    private static final ConcurrentHashMap<Object, AtomicIntegerArray> counts = new ConcurrentHashMap<>();

    /** Cached reflective handle to {@code Composer.getRecomposeScopeIdentity()}. */
    private static volatile Method recomposeScopeIdentityMethod;

    private RecompositionTracker() {}

    /**
     * Called from the JVMTI {@code onExit} hook on
     * {@code ComposerImpl.startRestartGroup(int)}. The composer is the
     * method receiver; we read its current recompose-scope identity
     * (the slot-table Anchor that was just established by the method
     * body) and increment its bucket.
     */
    public static void onStartRestartGroup(Object composer) {
        Object anchor = readRecomposeScopeIdentity(composer);
        if (anchor == null) return;
        counts.computeIfAbsent(anchor, k -> new AtomicIntegerArray(2)).incrementAndGet(0);
    }

    /**
     * Called from the JVMTI {@code onEnter} hook on
     * {@code ComposerImpl.skipToGroupEnd()}. At this point the current
     * scope is the one being skipped — its identity is the right key.
     * We undo the count increment (the symmetric startRestartGroup
     * fired for the same scope) and tally the skip instead, matching
     * AS's accounting.
     */
    public static void onSkipToGroupEnd(Object composer) {
        Object anchor = readRecomposeScopeIdentity(composer);
        if (anchor == null) return;
        AtomicIntegerArray data = counts.get(anchor);
        if (data != null) {
            data.decrementAndGet(0); // undo the count from startRestartGroup
            data.incrementAndGet(1); // skips++
        }
    }

    /**
     * Looks up counts by Anchor identity (the same Object returned by
     * {@code CompositionGroup.identity}, which equals what the hook
     * captured at increment time).
     *
     * @return {@code [recomposeCount, skipCount]}, or {@code null} if
     *         this anchor hasn't been observed.
     */
    public static int[] getCounts(Object anchor) {
        if (anchor == null) return null;
        AtomicIntegerArray data = counts.get(anchor);
        if (data == null) return null;
        return new int[] { data.get(0), data.get(1) };
    }

    public static void reset() {
        counts.clear();
    }

    public static int getTrackedKeyCount() {
        return counts.size();
    }

    /**
     * Reflectively calls {@code Composer.getRecomposeScopeIdentity()}.
     * The method is on the {@code androidx.compose.runtime.Composer}
     * interface (verified via dexdump of AS's
     * {@code RecompositionHandlerKt.installHooks$lambda$4$lambda$1};
     * the {@code invoke-interface} opcode confirms public-interface
     * resolution). Cached after first lookup.
     */
    private static Object readRecomposeScopeIdentity(Object composer) {
        if (composer == null) return null;
        try {
            Method m = recomposeScopeIdentityMethod;
            if (m == null) {
                m = composer.getClass().getMethod("getRecomposeScopeIdentity");
                m.setAccessible(true);
                recomposeScopeIdentityMethod = m;
            }
            return m.invoke(composer);
        } catch (Throwable t) {
            return null;
        }
    }
}
