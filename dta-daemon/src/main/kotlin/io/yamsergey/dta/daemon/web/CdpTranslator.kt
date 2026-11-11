package io.yamsergey.dta.daemon.web

import io.yamsergey.dta.protocol.network.Direction
import io.yamsergey.dta.protocol.network.Header
import io.yamsergey.dta.protocol.network.MessageOpcode
import io.yamsergey.dta.protocol.web.WebStep
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Feature 060: one page's DevTools `Network.*` events as the app recorder's lifecycle steps
 * (research R4). Pure: the caller fetches bodies ([Out.fetchBody], [Out.fetchPostData]) and
 * hands them back. Times are the page's (device wall clock): `wallTime`, and `timestamp`
 * (monotonic seconds) shifted by the offset learned from a request.
 */
class CdpTranslator(
    private val prefix: String,
    private val library: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    class Out(val steps: List<WebStep> = emptyList(), val fetchBody: String? = null, val fetchPostData: String? = null)

    private class Request(var key: String, var redirects: Int = 0, var contentType: String? = null, var responded: Boolean = false, var finishedMs: Long? = null)
    private class Socket(val key: String, var url: String?, var started: Boolean = false)

    private val requests = HashMap<String, Request>()
    private val ignored = HashSet<String>()
    private val sockets = HashMap<String, Socket>()
    /** Device wall ms minus monotonic ms, once a request told both. */
    private var offset: Long? = null

    /** Responses and frames of requests or sockets that started before DTA attached. */
    var missed = 0
        private set
    var started = 0
        private set

    private fun time(p: JsonObject): Long = p.num("timestamp")?.let { ts -> offset?.let { (ts * 1000).toLong() + it } } ?: clock()

    fun event(method: String, p: JsonObject): Out = when (method) {
        "Network.requestWillBeSent" -> requestWillBeSent(p)
        "Network.responseReceived" -> responseReceived(p)
        "Network.loadingFinished" -> loadingFinished(p)
        "Network.loadingFailed" -> loadingFailed(p)
        "Network.webSocketCreated" -> { sockets.getOrPut(p.str("requestId")!!) { Socket(key(p.str("requestId")!!), null) }.url = p.str("url"); Out() }
        "Network.webSocketWillSendHandshakeRequest" -> wsHandshake(p)
        "Network.webSocketHandshakeResponseReceived" -> wsOpen(p)
        "Network.webSocketFrameSent" -> wsFrame(p, Direction.OUT)
        "Network.webSocketFrameReceived" -> wsFrame(p, Direction.IN)
        "Network.webSocketClosed" -> wsEnd(p, WebStep(WebStep.WS_CLOSED, "", timeMs = time(p)))
        "Network.webSocketFrameError" -> wsEnd(p, WebStep(WebStep.WS_FAILED, "", error = p.str("errorMessage"), timeMs = time(p)))
        else -> Out()
    }

    /** The answer to [Out.fetchBody]: [body] null when the page no longer has it. */
    fun body(requestId: String, body: String?, base64: Boolean): List<WebStep> {
        val r = requests.remove(requestId) ?: return emptyList()
        if (body == null) return listOf(WebStep(WebStep.HTTP_COMPLETE, r.key, endMs = r.finishedMs))
        return listOf(
            WebStep(
                WebStep.HTTP_RESPONSE_BODY, r.key, contentType = r.contentType, endMs = r.finishedMs,
                text = body.takeUnless { base64 }, base64 = body.takeIf { base64 },
            ),
        )
    }

    /** The answer to [Out.fetchPostData]. */
    fun postData(requestId: String, data: String?): List<WebStep> {
        val r = requests[requestId] ?: return emptyList()
        return data?.let { listOf(WebStep(WebStep.HTTP_REQUEST_BODY, r.key, text = it)) }.orEmpty()
    }

    private fun key(requestId: String) = "$prefix:$requestId"

    private fun requestWillBeSent(p: JsonObject): Out {
        val id = p.str("requestId") ?: return Out()
        val request = p.obj("request") ?: return Out()
        val url = request.str("url") ?: return Out()
        if (url.startsWith("data:") || url.startsWith("blob:")) { ignored += id; return Out() }
        val wall = p.num("wallTime")?.let { (it * 1000).toLong() }
        if (wall != null) p.num("timestamp")?.let { ts -> offset = wall - (ts * 1000).toLong() }
        val steps = ArrayList<WebStep>()
        val existing = requests[id]
        val redirect = p.obj("redirectResponse")
        val r = if (existing != null && redirect != null) {
            steps += WebStep(WebStep.HTTP_RESPONSE, existing.key, status = redirect.int("status"), headers = headers(redirect.obj("headers")), protocol = redirect.str("protocol"))
            steps += WebStep(WebStep.HTTP_COMPLETE, existing.key, endMs = wall ?: clock())
            existing.redirects++
            existing.key = "${key(id)}#${existing.redirects}"
            existing.responded = false
            existing
        } else Request(key(id)).also { requests[id] = it }
        started++
        val postData = request.str("postData")
        steps += WebStep(WebStep.HTTP_START, r.key, library = library, method = request.str("method") ?: "GET", url = url, headers = headers(request.obj("headers")), timeMs = wall ?: clock())
        postData?.let { steps += WebStep(WebStep.HTTP_REQUEST_BODY, r.key, text = it, contentType = header(request.obj("headers"), "Content-Type")) }
        val fetchPost = id.takeIf { postData == null && request.bool("hasPostData") == true }
        return Out(steps, fetchPostData = fetchPost)
    }

    private fun responseReceived(p: JsonObject): Out {
        val id = p.str("requestId") ?: return Out()
        val r = requests[id] ?: return miss(id)
        val response = p.obj("response") ?: return Out()
        r.responded = true
        r.contentType = header(response.obj("headers"), "Content-Type") ?: response.str("mimeType")
        return Out(listOf(WebStep(WebStep.HTTP_RESPONSE, r.key, status = response.int("status"), headers = headers(response.obj("headers")), protocol = response.str("protocol"))))
    }

    private fun loadingFinished(p: JsonObject): Out {
        val id = p.str("requestId") ?: return Out()
        val r = requests[id] ?: return miss(id)
        r.finishedMs = time(p)
        if (!r.responded) {
            requests.remove(id)
            return Out(listOf(WebStep(WebStep.HTTP_COMPLETE, r.key, endMs = r.finishedMs)))
        }
        return Out(fetchBody = id)
    }

    private fun loadingFailed(p: JsonObject): Out {
        val id = p.str("requestId") ?: return Out()
        val r = requests.remove(id) ?: return miss(id)
        val error = p.str("errorText") ?: "failed"
        val canceled = p.bool("canceled") == true
        return Out(listOf(WebStep(WebStep.HTTP_FAILED, r.key, error = if (canceled) "$error (canceled)" else error, endMs = time(p))))
    }

    /** Events of a request that started before DTA attached: counted once, not recorded. */
    private fun miss(id: String): Out {
        if (id in ignored) return Out()
        ignored += id
        missed++
        return Out()
    }

    private fun wsHandshake(p: JsonObject): Out {
        val id = p.str("requestId") ?: return Out()
        val s = sockets.getOrPut(id) { Socket(key(id), null) }
        if (s.started) return Out()
        s.started = true
        val wall = p.num("wallTime")?.let { (it * 1000).toLong() }
        if (wall != null) p.num("timestamp")?.let { ts -> offset = wall - (ts * 1000).toLong() }
        return Out(listOf(WebStep(WebStep.WS_START, s.key, library = library, url = s.url ?: "", headers = headers(p.obj("request")?.obj("headers")), timeMs = wall ?: time(p))))
    }

    private fun wsOpen(p: JsonObject): Out {
        val s = sockets[p.str("requestId")] ?: return Out()
        if (!s.started) return Out()
        return Out(listOf(WebStep(WebStep.WS_OPEN, s.key, headers = headers(p.obj("response")?.obj("headers")), timeMs = time(p))))
    }

    private fun wsFrame(p: JsonObject, direction: Direction): Out {
        val id = p.str("requestId") ?: return Out()
        val s = sockets.getOrPut(id) { Socket(key(id), null) }
        val steps = ArrayList<WebStep>()
        if (!s.started) {
            // Opened before DTA attached: the socket is recorded from its first frame seen.
            s.started = true
            missed++
            steps += WebStep(WebStep.WS_START, s.key, library = library, url = s.url ?: "(opened before DTA attached)", timeMs = time(p))
            steps += WebStep(WebStep.WS_OPEN, s.key, timeMs = time(p))
        }
        val response = p.obj("response") ?: return Out(steps)
        val binary = response.int("opcode") == 2
        val payload = response.str("payloadData") ?: ""
        steps += WebStep(
            WebStep.WS_MESSAGE, s.key, direction = direction, opcode = if (binary) MessageOpcode.BINARY else MessageOpcode.TEXT,
            text = payload.takeUnless { binary }, base64 = payload.takeIf { binary }, timeMs = time(p),
        )
        return Out(steps)
    }

    private fun wsEnd(p: JsonObject, step: WebStep): Out {
        val s = sockets.remove(p.str("requestId")) ?: return Out()
        if (!s.started) return Out()
        return Out(listOf(step.copy(key = s.key)))
    }

    private companion object {
        fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
        fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
        fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
        fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
        fun JsonObject.obj(k: String) = this[k] as? JsonObject

        /** DevTools joins repeated headers with a newline. */
        fun headers(o: JsonObject?): List<Header> = o?.entries?.flatMap { (name, v) ->
            (v.jsonPrimitive.contentOrNull ?: "").split('\n').map { Header(name, it) }
        }.orEmpty()

        fun header(o: JsonObject?, name: String): String? =
            o?.entries?.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.jsonPrimitive?.contentOrNull
    }
}
