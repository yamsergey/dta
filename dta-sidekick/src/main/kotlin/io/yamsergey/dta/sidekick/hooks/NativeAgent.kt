package io.yamsergey.dta.sidekick.hooks

/** JNI surface of libdta-agent.so (src/main/cpp/agent.cc). Callable only after [HookAttach]. */
internal object NativeAgent {
    @JvmStatic external fun nativeIsAttached(): Boolean

    /** @return null on success, otherwise the reason. */
    @JvmStatic external fun nativeAddToBootstrap(jarPath: String): String?

    /** [nativeMethodState] results (feature 019). */
    const val PRESENT = 0
    const val ABSENT = 1
    const val ABSTRACT = 2
    const val NATIVE = 3
    const val UNKNOWN = 4

    /** Whether [target] declares [method][descriptor] in a hookable form; never reloads the class. */
    @JvmStatic external fun nativeMethodState(target: Class<*>, method: String, descriptor: String): Int

    const val ENTRY = 0
    const val EXIT = 1
    const val CONTROL = 2

    /**
     * Installs one hook ([ENTRY], [EXIT] or [CONTROL]) on an already-loaded class by
     * retransforming it.
     * @return null on success, otherwise "<transform reason>\u001f<runtime detail>" (the class
     * keeps its previous form); see [Hooks.refused].
     */
    @JvmStatic external fun nativeHook(target: Class<*>, method: String, descriptor: String, kind: Int): String?
}
