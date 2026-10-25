package io.yamsergey.dta.sidekick.network.urlconnection

import io.yamsergey.dta.protocol.network.InitiatorSite
import io.yamsergey.dta.protocol.network.NetworkLibraries
import io.yamsergey.dta.shim.ExitHandler
import io.yamsergey.dta.sidekick.hooks.Hooks
import io.yamsergey.dta.sidekick.network.Initiators
import io.yamsergey.dta.sidekick.network.LibraryState
import io.yamsergey.dta.sidekick.network.NetworkRecorder
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

    fun install(recorder: NetworkRecorder, initiators: Initiators): LibraryState {
        val handler = object : ExitHandler {
            override fun onExit(result: Any?): Any? = when (result) {
                is TrackedHttpURLConnection, is TrackedHttpsURLConnection -> result
                is HttpsURLConnection -> TrackedHttpsURLConnection(result, recorder, initiators.capture(InitiatorSite.CONNECT))
                is HttpURLConnection -> TrackedHttpURLConnection(result, recorder, initiators.capture(InitiatorSite.CONNECT))
                else -> result
            }
        }
        val failure = listOf(
            Hooks.exit(URL::class.java, "openConnection", "()Ljava/net/URLConnection;", handler),
            Hooks.exit(URL::class.java, "openConnection", "(Ljava/net/Proxy;)Ljava/net/URLConnection;", handler),
        ).filterIsInstance<Hooks.Install.Failed>().firstOrNull()?.reason
        return LibraryState(NetworkLibraries.URLCONNECTION, present = true, installFailure = failure)
    }
}
