package io.yamsergey.dta.sidekick.compose;

import io.yamsergey.dta.sidekick.jvmti.MethodHook;

/**
 * JVMTI hooks for tracking Compose recomposition counts <strong>per
 * visual instance</strong>.
 *
 * <p>Hooks {@code ComposerImpl.startRestartGroup(int)} and
 * {@code ComposerImpl.skipToGroupEnd()} — same surface as Android
 * Studio's {@code androidx.compose.ui.inspection} agent (verified via
 * dexdump of {@code RecompositionHandlerKt}). The hook side reads
 * {@code Composer.getRecomposeScopeIdentity()} to obtain the
 * slot-table Anchor for the current scope; the tracker stores counts
 * keyed by that Anchor object, giving distinct counts for distinct
 * instances of the same composable function (LazyColumn items, etc.).
 *
 * <p>Why startRestartGroup uses {@code onExit} (not {@code onEnter}):
 * the new scope is pushed onto the Composer during the method body,
 * so {@code getRecomposeScopeIdentity()} returns the parent's anchor
 * at {@code onEnter}. AS's hook is also a post-call hook for the same
 * reason. {@code skipToGroupEnd} is symmetric — at {@code onEnter}
 * the current scope is already the one being skipped.
 */
public final class RecompositionHooks {

    private RecompositionHooks() {}

    /**
     * Hook on {@code ComposerImpl.startRestartGroup(int key)}.
     * Fires <em>after</em> the call so the new scope is established
     * and {@code getRecomposeScopeIdentity()} returns the right
     * Anchor.
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
        public Object onExit(Object thisObj, Object result) {
            // The method returns `this`; either thisObj or result is
            // the Composer. Prefer the returned value because that's
            // the canonical AS approach (their hook signature is
            // (Composer) -> Composer, operating on the return value).
            Object composer = result != null ? result : thisObj;
            RecompositionTracker.onStartRestartGroup(composer);
            return result;
        }
    }

    /**
     * Hook on {@code ComposerImpl.skipToGroupEnd()}.
     * Fires at entry; the current scope is already established at
     * this point and its identity is the right key.
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
            RecompositionTracker.onSkipToGroupEnd(thisObj);
        }
    }
}
