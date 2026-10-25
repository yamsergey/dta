package io.yamsergey.dta.sidekick.network.okhttp

import io.yamsergey.dta.protocol.network.InitiatorSite
import io.yamsergey.dta.protocol.network.NetworkLibraries
import io.yamsergey.dta.shim.EntryHandler
import io.yamsergey.dta.shim.ExitHandler
import io.yamsergey.dta.sidekick.hooks.Hooks
import io.yamsergey.dta.sidekick.network.Initiators
import io.yamsergey.dta.sidekick.network.LibraryState
import io.yamsergey.dta.sidekick.network.NetworkRecorder

/**
 * OkHttp 3/4/5 HTTP capture: an exit hook on `OkHttpClient#networkInterceptors()` returns
 * the list with [DtaInterceptor] first (Android Studio's NetworkInspector does the same);
 * one on `OkHttpClient#interceptors()` adds [FailureInterceptor] for calls that fail
 * before reaching the network layer. Entry hooks on `RealCall#enqueue`/`#execute` (both
 * the 4.x+ and 3.x class names) remember where the app issued each call, so async
 * requests carry the enqueue site rather than dispatcher frames (FR-010).
 * The getter is read on every call, so clients built before the hook are captured too.
 * Code touching OkHttp types loads only after the presence probe (constitution I).
 */
internal object OkHttpCapture {

    private const val CLIENT = "okhttp3.OkHttpClient"

    fun install(loader: ClassLoader?, recorder: NetworkRecorder, initiators: Initiators): LibraryState {
        val client = probe(loader) ?: return LibraryState(NetworkLibraries.OKHTTP3, present = false, installFailure = null)
        val seenCalls = NetworkSeenCalls()
        val network = DtaInterceptor(recorder, seenCalls, initiators)
        val failures = FailureInterceptor(recorder, seenCalls, initiators)
        val callSites = REAL_CALLS.mapNotNull { name ->
            runCatching { Class.forName(name, false, loader) }.getOrNull()
        }.flatMap { realCall ->
            listOf(
                Hooks.entry(realCall, "enqueue", "(Lokhttp3/Callback;)V", remember(initiators, InitiatorSite.ENQUEUE)),
                Hooks.entry(realCall, "execute", "()Lokhttp3/Response;", remember(initiators, InitiatorSite.CALL)),
            )
        }
        val failure = (callSites + listOf(
            Hooks.exit(client, "networkInterceptors", "()Ljava/util/List;", object : ExitHandler {
                override fun onExit(result: Any?): Any? = withInterceptor(result, network)
            }),
            Hooks.exit(client, "interceptors", "()Ljava/util/List;", object : ExitHandler {
                override fun onExit(result: Any?): Any? = withInterceptor(result, failures)
            }),
        )).filterIsInstance<Hooks.Install.Failed>().firstOrNull()?.reason
        return LibraryState(NetworkLibraries.OKHTTP3, present = true, installFailure = failure)
    }

    private val REAL_CALLS = listOf("okhttp3.internal.connection.RealCall", "okhttp3.RealCall")

    private fun remember(initiators: Initiators, site: InitiatorSite) = object : EntryHandler {
        override fun onEntry(self: Any?, params: Array<Any?>) {
            if (self != null) initiators.remember(self, site)
        }
    }

    /**
     * Prepends ours once. `newBuilder()` copies the list through this same getter, so a
     * derived client's list may already hold it — adding again would capture twice.
     */
    private fun withInterceptor(result: Any?, interceptor: Any): Any? {
        val list = result as? List<*> ?: return result
        if (list.any { it != null && it.javaClass == interceptor.javaClass }) return result
        return ArrayList<Any?>(list.size + 1).apply {
            add(interceptor)
            addAll(list)
        }
    }

    private fun probe(loader: ClassLoader?): Class<*>? =
        try {
            Class.forName(CLIENT, false, loader)
        } catch (_: Throwable) {
            null
        }
}
