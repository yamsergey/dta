package io.yamsergey.dta.sidekick.data;

import io.yamsergey.dta.sidekick.SidekickLog;
import io.yamsergey.dta.sidekick.jvmti.MethodHook;

import java.lang.ref.WeakReference;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * JVMTI hook that captures NavController instances when {@code setGraph} is
 * called. Works for both Fragment-based and Compose navigation since both
 * call {@code NavController.setGraph()} during setup.
 *
 * <p>Stored as weak references so we don't prevent garbage collection.
 * {@link RuntimeInspector} reads from here instead of searching the view tree.</p>
 */
public class NavControllerHook implements MethodHook {

    private static final String TAG = "NavControllerHook";
    private static final CopyOnWriteArrayList<WeakReference<Object>> controllers = new CopyOnWriteArrayList<>();

    @Override
    public String getTargetClass() {
        return "androidx.navigation.NavController";
    }

    @Override
    public String getTargetMethod() {
        return "setGraph";
    }

    @Override
    public String getMethodSignature() {
        // Hook all setGraph overloads
        return null;
    }

    @Override
    public String getId() {
        return "nav-controller-setgraph";
    }

    @Override
    public void onEnter(Object thisObj, Object[] args) {
        if (thisObj == null) return;
        try {
            // Check if we already track this instance
            for (WeakReference<Object> ref : controllers) {
                if (ref.get() == thisObj) return;
            }
            controllers.add(new WeakReference<>(thisObj));
            SidekickLog.d(TAG, "Captured NavController: " + thisObj.getClass().getName());
        } catch (Exception e) {
            SidekickLog.w(TAG, "Failed to capture NavController: " + e.getMessage());
        }
    }

    /**
     * Returns all captured NavController instances (pruning dead refs).
     */
    public static java.util.List<Object> getControllers() {
        java.util.List<Object> live = new java.util.ArrayList<>();
        java.util.List<WeakReference<Object>> dead = new java.util.ArrayList<>();
        for (WeakReference<Object> ref : controllers) {
            Object obj = ref.get();
            if (obj != null) {
                live.add(obj);
            } else {
                dead.add(ref);
            }
        }
        controllers.removeAll(dead);
        return live;
    }
}
