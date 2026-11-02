package io.yamsergey.dta.sidekick.network.okhttp

import io.yamsergey.dta.protocol.network.InitiatorSite
import io.yamsergey.dta.protocol.network.NetworkLibraries
import io.yamsergey.dta.shim.EntryHandler
import io.yamsergey.dta.shim.ExitHandler
import io.yamsergey.dta.sidekick.hooks.Hooks
import io.yamsergey.dta.sidekick.network.Initiators
import io.yamsergey.dta.sidekick.network.LibraryState
import io.yamsergey.dta.sidekick.network.NetworkRecorder
import io.yamsergey.dta.sidekick.network.script.ScriptRuntime

/**
 * OkHttp 3/4/5 HTTP capture: an exit hook on `OkHttpClient#networkInterceptors()` returns
 * the list with [DtaInterceptor] **last**, right before OkHttp's own CallServerInterceptor,
 * so capture records the request as sent — after the app's network interceptors changed
 * it — and the response as the server sent it, before they change it. (Android Studio puts
 * its interceptor first; we deliberately differ: debugging the network needs what was on
 * the wire, or the server gets blamed for client-side changes.) A hook on
 * `OkHttpClient#interceptors()` adds [ScriptInterceptor] first: it runs the network script and records calls that fail
 * before reaching the network layer. Entry hooks on `RealCall#enqueue`/`#execute` (both
 * the 4.x+ and 3.x class names) remember where the app issued each call, so async
 * requests carry the enqueue site rather than dispatcher frames (FR-010).
 * The getter is read on every call, so clients built before the hook are captured too.
 * Code touching OkHttp types loads only after the presence probe (constitution I).
 */
internal object OkHttpCapture {

    private const val CLIENT = "okhttp3.OkHttpClient"

    fun install(loader: ClassLoader?, recorder: NetworkRecorder, initiators: Initiators, scripts: ScriptRuntime): LibraryState {
        val client = probe(loader) ?: return LibraryState(NetworkLibraries.OKHTTP3, present = false, installFailure = null)
        val contexts = CallContexts()
        val network = DtaInterceptor(recorder, contexts, initiators)
        val app = ScriptInterceptor(recorder, contexts, initiators, scripts, recorder.bodyBound)
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
                override fun onExit(result: Any?): Any? = withInterceptor(result, network, last = true)
            }),
            Hooks.exit(client, "interceptors", "()Ljava/util/List;", object : ExitHandler {
                override fun onExit(result: Any?): Any? = withInterceptor(result, app, last = false)
            }),
        )).firstOrNull { it !is Hooks.Install.Installed }?.describe()
        return LibraryState(NetworkLibraries.OKHTTP3, present = true, installFailure = failure)
    }

    private val REAL_CALLS = listOf("okhttp3.internal.connection.RealCall", "okhttp3.RealCall")

    private fun remember(initiators: Initiators, site: InitiatorSite) = object : EntryHandler {
        override fun onEntry(self: Any?, params: Array<Any?>) {
            if (self != null) initiators.remember(self, site)
        }
    }

    /**
     * Ours exactly once, at the given end. `newBuilder()` copies the list through this
     * same getter, so a derived client may already hold ours — possibly no longer at the
     * end once the app added its own interceptors — hence remove, then place.
     */
    private fun withInterceptor(result: Any?, interceptor: Any, last: Boolean): Any? {
        val list = result as? List<*> ?: return result
        val ours = interceptor.javaClass
        val others = list.filter { it?.javaClass != ours }
        val inPlace = others.size == list.size - 1 && (if (last) list.lastOrNull() else list.firstOrNull())?.javaClass == ours
        if (inPlace) return result
        return ArrayList<Any?>(others.size + 1).apply {
            if (!last) add(interceptor)
            addAll(others)
            if (last) add(interceptor)
        }
    }

    private fun probe(loader: ClassLoader?): Class<*>? =
        try {
            Class.forName(CLIENT, false, loader)
        } catch (_: Throwable) {
            null
        }
}
