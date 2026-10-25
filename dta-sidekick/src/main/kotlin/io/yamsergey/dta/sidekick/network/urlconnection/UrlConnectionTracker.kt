package io.yamsergey.dta.sidekick.network.urlconnection

import io.yamsergey.dta.protocol.network.Header
import io.yamsergey.dta.protocol.network.Initiator
import io.yamsergey.dta.protocol.network.NetworkLibraries
import io.yamsergey.dta.sidekick.network.NetworkRecorder
import io.yamsergey.dta.sidekick.network.TeeInputStream
import io.yamsergey.dta.sidekick.network.TeeOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection

/**
 * Lifecycle of one HttpURLConnection exchange, driven by the wrapper's delegating calls.
 * Every recording step is contained; the delegate's own results and exceptions reach
 * the app unchanged.
 */
internal class UrlConnectionTracker(
    private val target: () -> HttpURLConnection,
    private val recorder: NetworkRecorder,
    private val initiator: Initiator,
) {
    private val delegate: HttpURLConnection get() = target()

    /** The captured entry, once the request started. */
    var id: String? = null
        private set
    private var responseRecorded = false
    private var bodyStarted = false
    private var failed = false

    /** Before the delegate connects: request headers are unreadable afterwards. */
    @Synchronized
    fun start() {
        if (id != null) return
        id = safe {
            val headers = delegate.requestProperties.flatMap { (name, values) ->
                if (name == null) emptyList() else values.map { Header(name, it) }
            }
            recorder.httpStarted(NetworkLibraries.URLCONNECTION, delegate.requestMethod, delegate.url.toString(), headers, initiator)
        }
    }

    fun requestBody(stream: OutputStream): OutputStream {
        val exchange = id ?: return stream
        val body = safe {
            recorder.httpRequestBody(exchange, delegate.getRequestProperty("Content-Type"), null, delegate.getRequestProperty("Content-Encoding"))
        } ?: return stream
        return TeeOutputStream(stream, body)
    }

    /** After the delegate produced a response; reads only what the delegate already has. */
    @Synchronized
    fun response() {
        val exchange = id ?: return
        if (responseRecorded) return
        responseRecorded = true
        safe {
            val headers = delegate.headerFields.flatMap { (name, values) ->
                if (name == null) emptyList() else values.map { Header(name, it) }
            }
            recorder.httpResponse(exchange, delegate.responseCode, headers, null)
        }
    }

    fun responseBody(stream: InputStream?): InputStream? {
        val exchange = id ?: return stream
        if (stream == null) return null
        synchronized(this) {
            if (bodyStarted) return stream
            bodyStarted = true
        }
        val body = safe {
            recorder.httpResponseBody(
                exchange,
                contentType = delegate.contentType,
                encoding = delegate.contentEncoding,
                declaredBytes = delegate.contentLengthLong,
            )
        } ?: return stream
        return TeeInputStream(stream, body)
    }

    @Synchronized
    fun failed(error: Throwable) {
        val exchange = id ?: return
        if (responseRecorded || failed) return
        failed = true
        safe { recorder.httpFailed(exchange, error) }
    }

    /** disconnect(): a response whose body was never requested completes here. */
    @Synchronized
    fun finish() {
        val exchange = id ?: return
        if (responseRecorded && !bodyStarted && !failed) safe { recorder.httpComplete(exchange) }
    }

    private inline fun <T> safe(block: () -> T): T? = try {
        block()
    } catch (_: Throwable) {
        null
    }
}
