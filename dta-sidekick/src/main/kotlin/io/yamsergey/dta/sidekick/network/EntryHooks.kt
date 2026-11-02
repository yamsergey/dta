package io.yamsergey.dta.sidekick.network

import io.yamsergey.dta.shim.ControlHandler
import io.yamsergey.dta.shim.EntryHandler
import io.yamsergey.dta.shim.HookDispatcher
import io.yamsergey.dta.sidekick.hooks.Hooks

/** Installs entry (and control) hooks on one class and folds their results into one library state. */
internal class EntryHooks(private val target: Class<*>) {
    private val results = mutableListOf<Hooks.Install>()

    fun on(method: String, descriptor: String, handler: (self: Any, params: Array<Any?>) -> Unit) {
        results += Hooks.entry(target, method, descriptor, object : EntryHandler {
            override fun onEntry(self: Any?, params: Array<Any?>) {
                if (self != null) handler(self, params)
            }
        })
    }

    /**
     * A control hook (feature 005): [handler] may rewrite `params` in place and return
     * [PROCEED], or return a value to return from the method instead of running it.
     */
    fun control(method: String, descriptor: String, handler: (self: Any, params: Array<Any?>) -> Any?) {
        results += Hooks.control(target, method, descriptor, object : ControlHandler {
            override fun onEntry(self: Any?, params: Array<Any?>): Any? =
                if (self == null) HookDispatcher.PROCEED else handler(self, params)
        })
    }

    fun state(library: String) =
        LibraryState(library, present = true, installFailure = results.firstOrNull { it !is Hooks.Install.Installed }?.describe())

    companion object {
        val PROCEED: Any get() = HookDispatcher.PROCEED

        fun load(className: String, loader: ClassLoader?): Class<*>? =
            try {
                Class.forName(className, false, loader)
            } catch (_: Throwable) {
                null
            }
    }
}
