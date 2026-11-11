package io.yamsergey.dta.daemon.web

import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** A DevTools event; [sessionId] names the attached target (flatten mode), null for the browser. */
data class CdpEvent(val method: String, val params: JsonObject, val sessionId: String?)

class CdpException(message: String) : Exception(message)

/** Feature 060: a DevTools connection, as the browser session sees it (a fake in tests). */
interface CdpChannel {
    val events: ReceiveChannel<CdpEvent>
    suspend fun send(method: String, params: JsonObject = JsonObject(emptyMap()), sessionId: String? = null): JsonObject
    fun close()
}

/** The JDK's WebSocket client speaking DevTools (research R4: no new dependency). */
class CdpSocket private constructor() : CdpChannel, WebSocket.Listener {
    private lateinit var ws: WebSocket
    private val ids = AtomicInteger()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val sendLock = Mutex()
    private val buffer = StringBuilder()
    override val events = Channel<CdpEvent>(Channel.UNLIMITED)

    override suspend fun send(method: String, params: JsonObject, sessionId: String?): JsonObject {
        val id = ids.incrementAndGet()
        val answer = CompletableDeferred<JsonObject>()
        pending[id] = answer
        val message = buildJsonObject {
            put("id", id)
            put("method", method)
            put("params", params)
            sessionId?.let { put("sessionId", it) }
        }
        try {
            sendLock.withLock { ws.sendText(DtaJson.encodeToString(JsonObject.serializer(), message), true).await() }
            return withTimeout(TIMEOUT) { answer.await() }
        } finally {
            pending.remove(id)
        }
    }

    override fun close() {
        runCatching { ws.sendClose(WebSocket.NORMAL_CLOSURE, "") }
        runCatching { ws.abort() }
        finish(CdpException("closed"))
    }

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
        buffer.append(data)
        if (last) {
            val text = buffer.toString()
            buffer.setLength(0)
            runCatching { dispatch(DtaJson.parseToJsonElement(text).jsonObject) }
        }
        webSocket.request(1)
        return null
    }

    private fun dispatch(m: JsonObject) {
        val id = (m["id"] as? JsonPrimitive)?.intOrNull
        if (id != null) {
            val answer = pending[id] ?: return
            val error = m["error"] as? JsonObject
            if (error != null) answer.completeExceptionally(CdpException((error["message"] as? JsonPrimitive)?.contentOrNull ?: error.toString()))
            else answer.complete(m["result"] as? JsonObject ?: JsonObject(emptyMap()))
            return
        }
        val method = (m["method"] as? JsonPrimitive)?.contentOrNull ?: return
        events.trySend(CdpEvent(method, m["params"] as? JsonObject ?: JsonObject(emptyMap()), (m["sessionId"] as? JsonPrimitive)?.contentOrNull))
    }

    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String?): CompletionStage<*>? {
        finish(CdpException("DevTools closed the connection ($statusCode)"))
        return null
    }

    override fun onError(webSocket: WebSocket, error: Throwable) = finish(CdpException("DevTools connection failed: ${error.message}"))

    private fun finish(error: Exception) {
        events.close()
        pending.values.forEach { it.completeExceptionally(error) }
    }

    companion object {
        private val TIMEOUT = 10.seconds
        private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

        suspend fun connect(webSocketUrl: String): CdpSocket {
            val socket = CdpSocket()
            // DevTools messages (bodies) exceed the default frame budget; text arrives in parts and is joined.
            socket.ws = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3)).buildAsync(URI(webSocketUrl), socket).await()
            return socket
        }

        /** `/json/version` of a forwarded endpoint: the browser WebSocket URL and, on newer WebViews, `Android-Package`. */
        suspend fun version(port: Int): JsonObject {
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/json/version")).timeout(Duration.ofSeconds(3)).GET().build()
            val body = http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await().body()
            return DtaJson.parseToJsonElement(body).jsonObject
        }
    }
}
