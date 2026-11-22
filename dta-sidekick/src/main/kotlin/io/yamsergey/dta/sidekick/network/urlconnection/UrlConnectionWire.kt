package io.yamsergey.dta.sidekick.network.urlconnection

import io.yamsergey.dta.protocol.network.Header
import io.yamsergey.dta.shim.EntryHandler
import io.yamsergey.dta.shim.ExitHandler
import io.yamsergey.dta.sidekick.hooks.Hooks
import io.yamsergey.dta.sidekick.network.EventResult
import io.yamsergey.dta.sidekick.network.HookSignature

/**
 * The heads of an HttpURLConnection exchange as they cross the wire (D-001). Android's
 * HttpURLConnection is the platform's own OkHttp 2 (`com.android.okhttp`): it adds Host,
 * Connection, Accept-Encoding, User-Agent and Content-Length to the app's request properties
 * when it writes the request, and `getHeaderFields()` gives the response sorted by name with
 * `X-Android-*` pseudo headers that were never sent. Two hooks on its HTTP/1 stream see the heads
 * in wire order: `writeRequest(Headers, requestLine)` (entry) and `readHeaders()` (exit).
 *
 * The platform writes and reads on the thread that called the connection, inside the call
 * the wrapper makes to its delegate; [during] marks that call so a head goes to its exchange.
 * The platform's `Headers` is read through `toString()` only: one "name: value" line per header.
 */
internal object UrlConnectionWire {

    const val STREAM = "com.android.okhttp.internal.http.Http1xStream"
    val WRITE_REQUEST = HookSignature("writeRequest", "(Lcom/android/okhttp/Headers;Ljava/lang/String;)V")
    val READ_HEADERS = HookSignature("readHeaders", "()Lcom/android/okhttp/Headers;")

    const val REQUEST_HEADERS = "request headers as sent"
    const val RESPONSE_HEADERS = "response headers as received"

    private val current = ThreadLocal<UrlConnectionTracker?>()

    /** Runs [block] (a call into the platform connection) with its heads going to [tracker]. */
    inline fun <T> during(tracker: UrlConnectionTracker, block: () -> T): T {
        val previous = enter(tracker)
        try {
            return block()
        } finally {
            leave(previous)
        }
    }

    fun enter(tracker: UrlConnectionTracker): UrlConnectionTracker? = current.get().also { current.set(tracker) }

    fun leave(previous: UrlConnectionTracker?) {
        if (previous == null) current.remove() else current.set(previous)
    }

    /** The platform wrote a request head (the `Headers` object, as its `toString()` reads). */
    fun requestWritten(headers: Any?) {
        val tracker = current.get() ?: return
        try {
            tracker.wireRequest(parse(headers?.toString() ?: return))
        } catch (_: Throwable) {
            // Capture never fails the app's request.
        }
    }

    /** The platform read a response head. */
    fun headersRead(headers: Any?) {
        val tracker = current.get() ?: return
        try {
            tracker.wireResponse(parse(headers?.toString() ?: return))
        } catch (_: Throwable) {
            // Capture never fails the app's request.
        }
    }

    /** OkHttp 2's `Headers.toString()`: "name: value\n" per header, in order. */
    fun parse(text: String): List<Header> = text.split('\n').mapNotNull { line ->
        val at = line.indexOf(':')
        if (at <= 0) null else Header(line.substring(0, at), line.substring(at + 1).removePrefix(" "))
    }

    fun install(): List<EventResult> {
        val stream = try {
            Class.forName(STREAM, false, null)
        } catch (_: Throwable) {
            null
        } ?: return listOf(
            EventResult.classAbsent(REQUEST_HEADERS, STREAM, WRITE_REQUEST),
            EventResult.classAbsent(RESPONSE_HEADERS, STREAM, READ_HEADERS),
        )
        val written = object : EntryHandler {
            override fun onEntry(self: Any?, params: Array<Any?>) = requestWritten(params.getOrNull(0))
        }
        val read = object : ExitHandler {
            override fun onExit(result: Any?): Any? {
                headersRead(result)
                return result
            }
        }
        return listOf(
            EventResult.of(REQUEST_HEADERS, WRITE_REQUEST, Hooks.entry(stream, WRITE_REQUEST.method, WRITE_REQUEST.descriptor, written)),
            EventResult.of(RESPONSE_HEADERS, READ_HEADERS, Hooks.exit(stream, READ_HEADERS.method, READ_HEADERS.descriptor, read)),
        )
    }
}
