package io.yamsergey.dta.sidekick.network

import java.util.concurrent.TimeUnit

/**
 * Feature 023: the wait OkHttp itself spends when a body is closed early. On HTTP/1 it reads the
 * rest for up to [DISCARD_MILLIS] to reuse the connection (`Http1ExchangeCodec`); DTA reads those
 * same bytes first under the same deadline, so OkHttp then has nothing left to discard. On HTTP/2
 * it waits for nothing, so neither does DTA (null: only bytes already in the body's buffer).
 * Android's own `HttpURLConnection` hides its source's timeout (non-SDK API), so it gets none.
 */
object CloseWaits {
    const val DISCARD_MILLIS = 100L

    /** [protocol] as OkHttp names it ("http/1.1"). */
    fun okio(timeout: okio.Timeout, protocol: String?): CloseWait? {
        if (!discards(protocol)) return null
        return CloseWait {
            val had = timeout.hasDeadline()
            val before = if (had) timeout.deadlineNanoTime() else 0L
            val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DISCARD_MILLIS)
            timeout.deadlineNanoTime(if (had) minOf(before, until) else until)
            AutoCloseable { if (had) timeout.deadlineNanoTime(before) else timeout.clearDeadline() }
        }
    }

    private fun discards(protocol: String?) = protocol != null && protocol.lowercase().startsWith("http/1")
}
