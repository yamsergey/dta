package io.yamsergey.dta.sidekick.network.okhttp

import okhttp3.Call
import java.util.Collections
import java.util.WeakHashMap

/**
 * Per-call link between the app-level [ScriptInterceptor] and the network-level
 * [DtaInterceptor]: the entry ids the network layer created for a call (one per attempt:
 * redirects, retries). Calls are held weakly.
 */
internal class CallContexts {
    private val entries: MutableMap<Call, MutableList<String>> = Collections.synchronizedMap(WeakHashMap())

    fun entryCreated(call: Call, entryId: String) {
        synchronized(entries) { entries.getOrPut(call) { mutableListOf() } += entryId }
    }

    /** Entries the network layer recorded for [call]; empty when the request never got there. */
    fun entries(call: Call): List<String> = synchronized(entries) { entries[call]?.toList() ?: emptyList() }
}
