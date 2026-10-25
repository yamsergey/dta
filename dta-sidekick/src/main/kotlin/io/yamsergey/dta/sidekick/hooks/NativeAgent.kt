package io.yamsergey.dta.sidekick.hooks

/** JNI surface of libdta-agent.so (src/main/cpp/agent.cc). Callable only after [HookAttach]. */
internal object NativeAgent {
    @JvmStatic external fun nativeIsAttached(): Boolean

    /** @return null on success, otherwise the reason. */
    @JvmStatic external fun nativeAddToBootstrap(jarPath: String): String?

    /**
     * Installs one entry or exit hook on an already-loaded class by retransforming it.
     * @return null on success, otherwise the reason (the class keeps its previous form).
     */
    @JvmStatic external fun nativeHook(target: Class<*>, method: String, descriptor: String, entry: Boolean): String?
}
