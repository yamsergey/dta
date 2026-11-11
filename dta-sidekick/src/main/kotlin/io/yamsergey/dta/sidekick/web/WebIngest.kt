package io.yamsergey.dta.sidekick.web

import io.yamsergey.dta.protocol.network.Direction
import io.yamsergey.dta.protocol.network.Initiator
import io.yamsergey.dta.protocol.network.InitiatorSite
import io.yamsergey.dta.protocol.network.MessageOpcode
import io.yamsergey.dta.protocol.web.WebIngestResult
import io.yamsergey.dta.protocol.web.WebStep
import io.yamsergey.dta.sidekick.network.NetworkRecorder

/**
 * Feature 060: the daemon's DevTools reading of a web page, recorded as the app's own traffic
 * (one history, constitution VI). Keys are the daemon's; ids are this app's. Runs on the
 * sidekick's connection thread, never the main thread.
 */
class WebIngest(private val recorder: NetworkRecorder) {
    private val ids = object : LinkedHashMap<String, String>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_KEYS
    }

    @Synchronized
    fun apply(steps: List<WebStep>): WebIngestResult {
        var applied = 0
        var unknown = 0
        for (s in steps) if (runCatching { step(s) }.getOrDefault(false)) applied++ else unknown++
        return WebIngestResult(applied, unknown)
    }

    private fun step(s: WebStep): Boolean {
        when (s.op) {
            WebStep.HTTP_START -> {
                ids[s.key] = recorder.httpStarted(s.library ?: "web", s.method ?: "GET", s.url ?: "", s.headers.orEmpty(), PAGE, s.timeMs)
                return true
            }
            WebStep.WS_START -> {
                ids[s.key] = recorder.wsStarted(s.library ?: "web", s.url ?: "", s.headers.orEmpty(), PAGE, s.timeMs)
                return true
            }
        }
        val id = ids[s.key] ?: return false
        when (s.op) {
            WebStep.HTTP_REQUEST_BODY -> s.bytes?.let { b -> recorder.httpRequestBody(id, s.contentType, b.size.toLong()).apply { accept(b, 0, b.size); finish(endOfStream = true) } }
            WebStep.HTTP_RESPONSE -> recorder.httpResponse(id, s.status ?: 0, s.headers.orEmpty(), s.protocol)
            WebStep.HTTP_RESPONSE_BODY -> {
                val b = s.bytes ?: ByteArray(0)
                // DevTools gives the body decoded: no content encoding is left.
                recorder.httpResponseBody(id, s.contentType, null, b.size.toLong(), s.endMs).apply { accept(b, 0, b.size); finish(endOfStream = true) }
            }
            WebStep.HTTP_COMPLETE -> recorder.httpComplete(id, s.endMs)
            WebStep.HTTP_FAILED -> recorder.httpFailed(id, s.error ?: "failed", s.endMs)
            WebStep.WS_OPEN -> recorder.wsOpened(id, s.headers, s.timeMs)
            WebStep.WS_MESSAGE -> recorder.wsMessage(id, s.direction ?: Direction.IN, s.opcode ?: MessageOpcode.TEXT, s.bytes ?: ByteArray(0), PAGE, atMs = s.timeMs)
            WebStep.WS_CLOSED -> recorder.wsClosed(id, s.code, s.reason, s.timeMs)
            WebStep.WS_FAILED -> recorder.wsFailed(id, s.error ?: "failed", s.timeMs)
            else -> return false
        }
        return true
    }

    private companion object {
        const val MAX_KEYS = 5_000
        val PAGE = Initiator(
            thread = "web page", site = InitiatorSite.CALL,
            missing = "made by a web page (its script or the browser), read over DevTools: there is no app stack",
        )
    }
}
