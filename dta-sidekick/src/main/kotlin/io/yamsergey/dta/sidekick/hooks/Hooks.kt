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

    sealed interface Install {
        data object Installed : Install
        data class ClassAbsent(val className: String) : Install
        data class Failed(val reason: String) : Install
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
        if (!HookAttach.attached) return Install.Failed(HookAttach.reason ?: "hooks not attached")
        return try {
            val label = HookRegistry.label(cls.name, method, descriptor)
            val key = "$kind $label"
            if (key !in nativelyInstalled) {
                NativeAgent.nativeHook(cls, method, descriptor, kind)?.let { return Install.Failed(it) }
                nativelyInstalled += key
            }
            register(label)
            Install.Installed
        } catch (t: Throwable) {
            Install.Failed("${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
