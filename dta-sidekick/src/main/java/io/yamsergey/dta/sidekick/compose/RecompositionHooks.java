package io.yamsergey.dta.sidekick.compose;

import io.yamsergey.dta.sidekick.jvmti.MethodHook;

/**
 * JVMTI hooks for tracking Compose recomposition counts.
 *
 * <p>Hooks {@code ComposerImpl.startRestartGroup(int)} and
 * {@code ComposerImpl.skipToGroupEnd()} to count how many times each
 * composable recomposes vs skips. This is the same approach Android Studio
 * uses via ArtTooling, but implemented through our DexTransformer.</p>
 */
public final class RecompositionHooks {

    private RecompositionHooks() {}

    /**
     * Hook on {@code ComposerImpl.startRestartGroup(int key)}.
     * The key identifies the composable's source location.
     */
    public static class StartRestartGroupHook implements MethodHook {

        @Override
        public String getTargetClass() {
            return "androidx.compose.runtime.ComposerImpl";
        }

        @Override
        public String getTargetMethod() {
            return "startRestartGroup";
        }

        @Override
        public String getMethodSignature() {
            return "(I)Landroidx/compose/runtime/Composer;";
        }

        @Override
        public String getId() {
            return "compose-startRestartGroup";
        }

        @Override
        public void onEnter(Object thisObj, Object[] args) {
            if (args.length > 0 && args[0] instanceof Integer) {
                RecompositionTracker.onStartRestartGroup((Integer) args[0]);
            }
        }
    }

    /**
     * Hook on {@code ComposerImpl.skipToGroupEnd()}.
     * Called when the Compose runtime decides a composable doesn't need to recompose.
     */
    public static class SkipToGroupEndHook implements MethodHook {

        @Override
        public String getTargetClass() {
            return "androidx.compose.runtime.ComposerImpl";
        }

        @Override
        public String getTargetMethod() {
            return "skipToGroupEnd";
        }

        @Override
        public String getMethodSignature() {
            return "()V";
        }

        @Override
        public String getId() {
            return "compose-skipToGroupEnd";
        }

        @Override
        public void onEnter(Object thisObj, Object[] args) {
            RecompositionTracker.onSkipToGroupEnd();
        }
    }
}
