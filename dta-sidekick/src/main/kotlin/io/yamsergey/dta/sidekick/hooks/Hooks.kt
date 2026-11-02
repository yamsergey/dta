package io.yamsergey.dta.sidekick.hooks

import io.yamsergey.dta.shim.ControlHandler
import io.yamsergey.dta.shim.EntryHandler
import io.yamsergey.dta.shim.ExitHandler
import io.yamsergey.dta.shim.HookRegistry

/**
 * Installs entry/exit hooks: registers the handler with the shim and has the agent
 * retransform the class. Only valid after [HookAttach.attached]; the class itself must
 * not be loaded before that (it links against the bootstrap shim). Never throws.
 */
object Hooks {

    /**
     * The outcome of one hook. [code] is a stable token (diagnostics, tests); [describe] is the
     * plain text a reason is built from (feature 019): what was tried and why it did not install.
     */
    sealed interface Install {
        val code: String
        fun describe(): String

        data object Installed : Install {
            override val code = "installed"
            override fun describe() = "installed"
        }

        data class ClassAbsent(val className: String) : Install {
            override val code = "class-absent"
            override fun describe() = "${HookNames.simple(className)} is not in the app"
        }

        /** The class is there, but declares no such method (checked before any class reload). */
        data class MethodAbsent(val className: String, val method: String, val descriptor: String) : Install {
            override val code = "method-absent"
            override fun describe() = "${HookNames.method(className, method, descriptor)} is not in the app's version of ${HookNames.simple(className)}"
        }

        /** The method exists but has no code of its own to hook ([why]: abstract or native). */
        data class NotHookable(val className: String, val method: String, val descriptor: String, val why: String) : Install {
            override val code = "not-hookable"
            override fun describe() = "${HookNames.method(className, method, descriptor)} is $why, so it has no code to hook"
        }

        /** Tried and refused: [reason] is plain, [detail] the raw cause (runtime code, transform text). */
        data class Failed(val reason: String, val detail: String? = null) : Install {
            override val code = "failed"
            override fun describe() = reason
        }

        companion object {
            /**
             * nativeHook's "<our transform's reason>\u001f<runtime detail>": our reason means DTA
             * could not rewrite the method; only a runtime detail means Android refused the
             * rewritten class.
             */
            fun refused(native: String): Failed {
                val ours = native.substringBefore('\u001f').trim()
                val runtime = native.substringAfter('\u001f', "").trim()
                val detail = listOf(ours, runtime).filter { it.isNotEmpty() }.joinToString("; ").ifEmpty { null }
                return if (ours.isNotEmpty()) {
                    Failed("DTA could not add its hook to the method's code", detail)
                } else {
                    Failed("Android refused to reload the class with the hook", detail)
                }
            }
        }
    }

    private val nativelyInstalled = HashSet<String>()

    fun entry(cls: Class<*>, method: String, descriptor: String, handler: EntryHandler): Install =
        install(cls, method, descriptor, NativeAgent.ENTRY) { HookRegistry.entry(it, handler) }

    fun exit(cls: Class<*>, method: String, descriptor: String, handler: ExitHandler): Install =
        install(cls, method, descriptor, NativeAgent.EXIT) { HookRegistry.exit(it, handler) }

    /** A hook that may rewrite the method's parameters or return early (feature 005). */
    fun control(cls: Class<*>, method: String, descriptor: String, handler: ControlHandler): Install =
        install(cls, method, descriptor, NativeAgent.CONTROL) { HookRegistry.control(it, handler) }

    fun entry(className: String, loader: ClassLoader?, method: String, descriptor: String, handler: EntryHandler): Install =
        load(className, loader)?.let { entry(it, method, descriptor, handler) } ?: Install.ClassAbsent(className)

    fun exit(className: String, loader: ClassLoader?, method: String, descriptor: String, handler: ExitHandler): Install =
        load(className, loader)?.let { exit(it, method, descriptor, handler) } ?: Install.ClassAbsent(className)

    /** Handler failures counted by the shim for one hooked method (diagnostics). */
    fun errorCount(cls: Class<*>, method: String, descriptor: String): Long =
        HookRegistry.errorCount(HookRegistry.label(cls.name, method, descriptor))

    fun lastError(cls: Class<*>, method: String, descriptor: String): Throwable? =
        HookRegistry.lastError(HookRegistry.label(cls.name, method, descriptor))

    private fun load(className: String, loader: ClassLoader?): Class<*>? =
        try {
            Class.forName(className, false, loader)
        } catch (_: Throwable) {
            null
        }

    @Synchronized
    private fun install(cls: Class<*>, method: String, descriptor: String, kind: Int, register: (String) -> Unit): Install {
        if (!HookAttach.attached) return Install.Failed("hooks are off in this app", HookAttach.reason)
        return try {
            val label = HookRegistry.label(cls.name, method, descriptor)
            val key = "$kind $label"
            if (key !in nativelyInstalled) {
                // Feature 019: an absent method is found here, never by a class reload.
                when (NativeAgent.nativeMethodState(cls, method, descriptor)) {
                    NativeAgent.ABSENT -> return Install.MethodAbsent(cls.name, method, descriptor)
                    NativeAgent.ABSTRACT -> return Install.NotHookable(cls.name, method, descriptor, "abstract")
                    NativeAgent.NATIVE -> return Install.NotHookable(cls.name, method, descriptor, "native")
                }
                NativeAgent.nativeHook(cls, method, descriptor, kind)?.let { return Install.refused(it) }
                nativelyInstalled += key
            }
            register(label)
            Install.Installed
        } catch (t: Throwable) {
            Install.Failed("DTA could not add the hook", "${t.javaClass.name}: ${t.message}")
        }
    }
}
