package io.yamsergey.dta.sidekick.network.okhttp

import io.yamsergey.dta.protocol.network.Header
import io.yamsergey.dta.protocol.network.InitiatorSite
import io.yamsergey.dta.protocol.network.NetworkLibraries
import io.yamsergey.dta.sidekick.network.Initiators
import io.yamsergey.dta.sidekick.network.NetworkRecorder
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.Collections
import java.util.WeakHashMap

/** Calls whose request reached the network layer ([DtaInterceptor]); held weakly. */
internal class NetworkSeenCalls {
    private val calls: MutableSet<Call> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    fun mark(call: Call) {
        calls += call
    }

    fun seen(call: Call) = call in calls
}

/**
 * Application-level interceptor that records calls failing before any request reaches
 * the network layer — connection refused, DNS, TLS, connect timeouts. OkHttp runs network
 * interceptors only on an established connection, so [DtaInterceptor] never sees these
 * (Android Studio's inspector has the same gap). Exchanges the network layer saw report
 * their own failure there, so nothing is recorded twice.
 */
internal class FailureInterceptor(
    private val recorder: NetworkRecorder,
    private val seenCalls: NetworkSeenCalls,
    private val initiators: Initiators,
) : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        try {
            return chain.proceed(chain.request())
        } catch (e: IOException) {
            recordIfUnseen(chain, e)
            throw e
        }
    }

    private fun recordIfUnseen(chain: Interceptor.Chain, error: IOException) {
        try {
            if (seenCalls.seen(chain.call())) return
            val request = chain.request()
            val headers = (0 until request.headers.size).map { Header(request.headers.name(it), request.headers.value(it)) }
            val id = recorder.httpStarted(NetworkLibraries.OKHTTP3, request.method, request.url.toString(), headers, initiators.forCall(chain.call(), InitiatorSite.CALL))
            recorder.httpFailed(id, error)
        } catch (_: Throwable) {
        }
    }
}
