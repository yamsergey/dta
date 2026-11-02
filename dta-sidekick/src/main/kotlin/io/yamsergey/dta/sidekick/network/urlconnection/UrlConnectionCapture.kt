package io.yamsergey.dta.sidekick.network.urlconnection

import io.yamsergey.dta.protocol.network.InitiatorSite
import io.yamsergey.dta.protocol.network.NetworkLibraries
import io.yamsergey.dta.shim.ExitHandler
import io.yamsergey.dta.sidekick.hooks.Hooks
import io.yamsergey.dta.sidekick.network.EventResult
import io.yamsergey.dta.sidekick.network.HookSignature
import io.yamsergey.dta.sidekick.network.Initiators
import io.yamsergey.dta.sidekick.network.NetworkEvents
import io.yamsergey.dta.sidekick.network.LibraryState
import io.yamsergey.dta.sidekick.network.NetworkRecorder
import io.yamsergey.dta.sidekick.network.script.ScriptRuntime
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * HttpURLConnection capture: exit hooks on `URL#openConnection()` and
 * `URL#openConnection(Proxy)` return a tracking wrapper (Android Studio's model).
 * The initiator is the stack of the app code that opened the connection.
 * Non-HTTP connections (file:, jar:, the self-test marker) pass through untouched.
 */
internal object UrlConnectionCapture {

    /** Set while we open a connection ourselves (a script changed the URL): not wrapped again. */
    private val raw = ThreadLocal<Boolean>()

    private fun openRaw(url: URL): HttpURLConnection {
        raw.set(true)
        try {
            return url.openConnection() as HttpURLConnection
        } finally {
            raw.remove()
        }
    }

    fun install(recorder: NetworkRecorder, initiators: Initiators, scripts: ScriptRuntime): LibraryState {
        val handler = object : ExitHandler {
            override fun onExit(result: Any?): Any? = when {
                raw.get() == true -> result
                result is TrackedHttpURLConnection || result is TrackedHttpsURLConnection -> result
                result is HttpsURLConnection -> TrackedHttpsURLConnection(result, recorder, initiators.capture(InitiatorSite.CONNECT), scripts, ::openRaw)
                result is HttpURLConnection -> TrackedHttpURLConnection(result, recorder, initiators.capture(InitiatorSite.CONNECT), scripts, ::openRaw)
                else -> result
            }
        }
        val direct = HookSignature("openConnection", "()Ljava/net/URLConnection;")
        val proxied = HookSignature("openConnection", "(Ljava/net/Proxy;)Ljava/net/URLConnection;")
        val events = listOf(
            EventResult.of(NetworkEvents.CONNECTIONS, direct, Hooks.exit(URL::class.java, direct.method, direct.descriptor, handler)),
            EventResult.of(NetworkEvents.PROXY_CONNECTIONS, proxied, Hooks.exit(URL::class.java, proxied.method, proxied.descriptor, handler)),
        )
        return LibraryState(NetworkLibraries.URLCONNECTION, present = true, installFailure = null, events = events)
    }
}
