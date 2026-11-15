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
            override fun describe() = "${HookNames.simple(className)} is not in the app or this Android version"
        }

        /** The class is there, but declares no such method (checked before any class reload). */
        data class MethodAbsent(val className: String, val method: String, val descriptor: String) : Install {
            override val code = "method-absent"
            override fun describe() = "${HookNames.method(className, method, descriptor)} is not in this version of ${HookNames.simple(className)}"
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

    /** One hook of [together]: what to hook and the handler it calls. */
    class Spec private constructor(val method: String, val descriptor: String, internal val kind: Int, internal val register: (String) -> Unit) {
        companion object {
            fun entry(method: String, descriptor: String, handler: EntryHandler) = Spec(method, descriptor, NativeAgent.ENTRY) { HookRegistry.entry(it, handler) }
            fun exit(method: String, descriptor: String, handler: ExitHandler) = Spec(method, descriptor, NativeAgent.EXIT) { HookRegistry.exit(it, handler) }
        }
    }

    /**
     * Several hooks on [cls] in one class reload: each reload of a large framework class holds the
     * calling thread for tens of milliseconds, and a second reload of the same class costs more
     * than the first (View: 65 ms, then 290). One result per spec, in order; an absent or
     * unhookable method is refused before the reload and does not stop the others.
     */
    @Synchronized
    fun together(cls: Class<*>, vararg specs: Spec): List<Install> {
        if (!HookAttach.attached) return specs.map { Install.Failed("DTA's hooks are off in this app", HookAttach.reason) }
        val results = arrayOfNulls<Install>(specs.size)
        val pending = ArrayList<Int>()
        try {
            specs.forEachIndexed { i, s ->
                val label = HookRegistry.label(cls.name, s.method, s.descriptor)
                if ("${s.kind} $label" in nativelyInstalled) {
                    s.register(label)
                    results[i] = Install.Installed
                    return@forEachIndexed
                }
                results[i] = when (NativeAgent.nativeMethodState(cls, s.method, s.descriptor)) {
                    NativeAgent.ABSENT -> Install.MethodAbsent(cls.name, s.method, s.descriptor)
                    NativeAgent.ABSTRACT -> Install.NotHookable(cls.name, s.method, s.descriptor, "abstract")
                    NativeAgent.NATIVE -> Install.NotHookable(cls.name, s.method, s.descriptor, "native")
                    else -> null.also { pending += i }
                }
            }
            // The same method and kind twice in one call is one native hook.
            val distinct = pending.distinctBy { specs[it].kind to (specs[it].method + specs[it].descriptor) }
            if (distinct.isNotEmpty()) {
                val refused = NativeAgent.nativeHookMany(
                    cls,
                    distinct.map { specs[it].method }.toTypedArray(),
                    distinct.map { specs[it].descriptor }.toTypedArray(),
                    distinct.map { specs[it].kind }.toIntArray(),
                )
                if (refused != null) {
                    val failed = Install.refused(refused)
                    for (i in pending) results[i] = failed
                    return results.map { it!! }
                }
                for (i in distinct) nativelyInstalled += "${specs[i].kind} ${HookRegistry.label(cls.name, specs[i].method, specs[i].descriptor)}"
            }
            for (i in pending) {
                specs[i].register(HookRegistry.label(cls.name, specs[i].method, specs[i].descriptor))
                results[i] = Install.Installed
            }
        } catch (t: Throwable) {
            val failed = Install.Failed("DTA could not add the hook", "${t.javaClass.name}: ${t.message}")
            for (i in specs.indices) if (results[i] == null || i in pending) results[i] = failed
        }
        return results.map { it!! }
    }

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

    /**
     * Feature 064: [handler] stops being called at once. When it was the method's last handler
     * of its kind, the hook is also taken out of the method's code, so the method costs what it
     * did before. @return null when done, otherwise why the code keeps the (now idle) hook.
     */
    @Synchronized
    fun remove(cls: Class<*>, method: String, descriptor: String, handler: Any): String? {
        val label = HookRegistry.label(cls.name, method, descriptor)
        HookRegistry.remove(label, handler)
        val (kind, left) = when (handler) {
            is ControlHandler -> NativeAgent.CONTROL to HookRegistry.controlCount(label)
            is EntryHandler -> NativeAgent.ENTRY to HookRegistry.entryCount(label)
            is ExitHandler -> NativeAgent.EXIT to HookRegistry.exitCount(label)
            else -> return "not a hook handler"
        }
        val key = "$kind $label"
        if (left > 0 || key !in nativelyInstalled) return null
        return try {
            NativeAgent.nativeUnhook(cls, method, descriptor, kind)?.let { return it }
            nativelyInstalled -= key
            null
        } catch (t: Throwable) {
            "${t.javaClass.name}: ${t.message}"
        }
    }

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
        if (!HookAttach.attached) return Install.Failed("DTA's hooks are off in this app", HookAttach.reason)
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
