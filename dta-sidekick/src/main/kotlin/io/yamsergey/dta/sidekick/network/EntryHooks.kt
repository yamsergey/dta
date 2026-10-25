package io.yamsergey.dta.sidekick.network

import io.yamsergey.dta.shim.EntryHandler
import io.yamsergey.dta.sidekick.hooks.Hooks

/** Installs entry hooks on one class and folds their results into one library state. */
internal class EntryHooks(private val target: Class<*>) {
    private val results = mutableListOf<Hooks.Install>()

    fun on(method: String, descriptor: String, handler: (self: Any, params: Array<Any?>) -> Unit) {
        results += Hooks.entry(target, method, descriptor, object : EntryHandler {
            override fun onEntry(self: Any?, params: Array<Any?>) {
                if (self != null) handler(self, params)
            }
        })
    }

    fun state(library: String) =
        LibraryState(library, present = true, installFailure = results.filterIsInstance<Hooks.Install.Failed>().firstOrNull()?.reason)

    companion object {
        fun load(className: String, loader: ClassLoader?): Class<*>? =
            try {
                Class.forName(className, false, loader)
            } catch (_: Throwable) {
                null
            }
    }
}
