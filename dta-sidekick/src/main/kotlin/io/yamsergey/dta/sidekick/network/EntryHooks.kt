package io.yamsergey.dta.sidekick.network

import io.yamsergey.dta.shim.ControlHandler
import io.yamsergey.dta.shim.EntryHandler
import io.yamsergey.dta.shim.HookDispatcher
import io.yamsergey.dta.sidekick.hooks.Hooks

/** How hooks are installed: [Hooks] on a device, a fake in deviceless tests. */
internal interface HookInstaller {
    fun entry(cls: Class<*>, method: String, descriptor: String, handler: EntryHandler): Hooks.Install
    fun control(cls: Class<*>, method: String, descriptor: String, handler: ControlHandler): Hooks.Install

    object Device : HookInstaller {
        override fun entry(cls: Class<*>, method: String, descriptor: String, handler: EntryHandler) = Hooks.entry(cls, method, descriptor, handler)
        override fun control(cls: Class<*>, method: String, descriptor: String, handler: ControlHandler) = Hooks.control(cls, method, descriptor, handler)
    }
}

/**
 * Installs entry (and control) hooks on one class, one per event (feature 019). An event lists
 * the method's known forms, newest first: the first present in the app is hooked, and the
 * event's result says which, or why none was.
 */
internal class EntryHooks(private val target: Class<*>, private val installer: HookInstaller = HookInstaller.Device) {

    private val results = mutableListOf<EventResult>()
    val events: List<EventResult> get() = results.toList()

    fun on(event: String, method: String, descriptor: String, handler: (self: Any, params: Array<Any?>) -> Unit) =
        on(event, HookSignature(method, descriptor), handler = handler)

    fun on(event: String, vararg signatures: HookSignature, handler: (self: Any, params: Array<Any?>) -> Unit) {
        val entry = object : EntryHandler {
            override fun onEntry(self: Any?, params: Array<Any?>) {
                if (self != null) handler(self, params)
            }
        }
        install(event, signatures.toList()) { installer.entry(target, it.method, it.descriptor, entry) }
    }

    fun control(event: String, method: String, descriptor: String, handler: (self: Any, params: Array<Any?>) -> Any?) =
        control(event, HookSignature(method, descriptor), handler = handler)

    /**
     * A control hook (feature 005): [handler] may rewrite `params` in place and return
     * [PROCEED], or return a value to return from the method instead of running it.
     */
    fun control(event: String, vararg signatures: HookSignature, handler: (self: Any, params: Array<Any?>) -> Any?) {
        val control = object : ControlHandler {
            override fun onEntry(self: Any?, params: Array<Any?>): Any? =
                if (self == null) HookDispatcher.PROCEED else handler(self, params)
        }
        install(event, signatures.toList()) { installer.control(target, it.method, it.descriptor, control) }
    }

    /** Tries each form in order; stops at the first installed, or at the first refused (it exists, so older forms will not). */
    private fun install(event: String, signatures: List<HookSignature>, attempt: (HookSignature) -> Hooks.Install) {
        val tried = mutableListOf<Pair<HookSignature, Hooks.Install>>()
        var installed: HookSignature? = null
        for (signature in signatures) {
            val result = attempt(signature)
            tried += signature to result
            if (result is Hooks.Install.Installed) {
                installed = signature
                break
            }
            if (result is Hooks.Install.Failed) break
        }
        results += EventResult(event, installed, tried)
    }

    /** An event that cannot be hooked because something it needs is not there ([why] says what). */
    fun missing(event: String, signature: HookSignature, why: Hooks.Install) {
        results += EventResult(event, null, listOf(signature to why))
    }

    fun state(library: String) = state(library, this)

    companion object {
        val PROCEED: Any get() = HookDispatcher.PROCEED

        fun load(className: String, loader: ClassLoader?): Class<*>? =
            try {
                Class.forName(className, false, loader)
            } catch (_: Throwable) {
                null
            }

        /** A present library whose capture spans [hooks] (several classes). */
        fun state(library: String, vararg hooks: EntryHooks) =
            LibraryState(library, present = true, installFailure = null, events = hooks.flatMap { it.events })
    }
}
