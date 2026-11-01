package io.yamsergey.dta.client

import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.protocol.ops.DaemonEvents
import io.yamsergey.dta.protocol.ops.DaemonHello
import io.yamsergey.dta.protocol.ops.DaemonHelloResult
import io.yamsergey.dta.protocol.ops.DaemonMethods
import io.yamsergey.dta.protocol.ops.ReplaceParams
import io.yamsergey.dta.protocol.ops.Selected
import io.yamsergey.dta.protocol.ops.VersionIdentity
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.Event
import io.yamsergey.dta.protocol.rpc.Message
import io.yamsergey.dta.protocol.rpc.Request
import io.yamsergey.dta.protocol.rpc.Response
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The daemon answered with an error: a client error code (`ClientErrorCodes`, `ErrorCodes`), a message naming the next step, data. */
class DaemonCallException(val code: Int, message: String, val data: JsonObject? = null) : Exception(message)

/** The connection to the daemon ended; [reason] says why (replaced, stopping, lost). */
class DaemonGoneException(val reason: String) : Exception(reason)

/**
 * One connection to the daemon process (feature 008): JDK WebSocket client, the same
 * JSON-RPC envelope as the sidekick channel, `daemon.hello` on connect.
 */
class DaemonClient private constructor(private val timeout: Duration) {
    private lateinit var socket: WebSocket
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Response>>()
    private val ids = AtomicLong(0)
    private val events = MutableSharedFlow<Event>(extraBufferCapacity = 1024)
    private val gone = MutableStateFlow<String?>(null)

    @Volatile
    var goneReason: String? = null
        private set

    /** The `daemon.replaced` or `daemon.stopping` event that ended the connection; null when it just dropped. */
    @Volatile
    var endedBy: Event? = null
        private set

    /** Suspends until the connection ends, for any reason; returns [goneReason]. */
    suspend fun awaitGone(): String = gone.filterNotNull().first()

    lateinit var daemon: DaemonHelloResult
        private set

    /** Runs a catalog operation; [params] may include `device` and `app`. */
    suspend fun call(operation: String, params: JsonObject? = null, timeout: Duration = this.timeout): Selected =
        DtaJson.decodeFromJsonElement(Selected.serializer(), request(operation, params, timeout))

    /** Raw request for lifecycle methods. */
    suspend fun request(method: String, params: JsonObject?, timeout: Duration = this.timeout): JsonElement {
        goneReason?.let { throw DaemonGoneException(it) }
        val id = ids.incrementAndGet()
        val deferred = CompletableDeferred<Response>()
        pending[id] = deferred
        try {
            send(DtaJson.encodeToString(Message.serializer(), Request(id, method, params))).await()
            val response = withTimeout(timeout) { deferred.await() }
            response.error?.let { throw DaemonCallException(it.code, it.message, it.details) }
            return response.result ?: JsonObject(emptyMap())
        } finally {
            pending.remove(id)
        }
    }

    /**
     * Network entries of one app as they happen. Each element is the event's data
     * (`subscription`, `device`, `app`, `event`, optional `degraded`), or, when any client
     * cleared the app's history, `cleared` and `by` instead of `event`. The flow ends with
     * [DaemonGoneException] when the daemon is replaced or stops, or the stream ends.
     */
    fun networkEvents(params: JsonObject): Flow<JsonObject> = subscription(DaemonMethods.NETWORK_SUBSCRIBE, DaemonMethods.NETWORK_UNSUBSCRIBE, setOf(DaemonEvents.NETWORK_ENTRY, DaemonEvents.NETWORK_CLEARED), params)

    /**
     * App connection changes (`apps.watch`, feature 009). The first element is
     * `{"selection": Selected}` whose result holds the current `apps` per watched device; then
     * each `app.connection` event's data (`device`, `app`, `state`, `reason`, `subscription`).
     * Ends with [DaemonGoneException] when the daemon is replaced or stops.
     */
    fun appEvents(params: JsonObject): Flow<JsonObject> = subscription(DaemonMethods.APPS_SUBSCRIBE, DaemonMethods.APPS_UNSUBSCRIBE, setOf(DaemonEvents.APP_CONNECTION), params)

    /**
     * The app's timeline as it happens (`timeline.watch`, features 015/016). The first element is
     * `{"selection": Selected}`; then each `timeline.event`'s data (`subscription`, `device`, `app`, `event`).
     */
    fun timelineEvents(params: JsonObject): Flow<JsonObject> =
        subscription(DaemonMethods.TIMELINE_SUBSCRIBE, DaemonMethods.TIMELINE_UNSUBSCRIBE, setOf(DaemonEvents.TIMELINE_EVENT), params)

    private fun subscription(subscribe: String, unsubscribe: String, eventNames: Set<String>, params: JsonObject): Flow<JsonObject> = callbackFlow {
        val subscribed = DtaJson.decodeFromJsonElement(Selected.serializer(), request(subscribe, params))
        val id = (subscribed.result.jsonObject["subscription"] as JsonPrimitive).content
        send(buildJsonObject { put("selection", DtaJson.encodeToJsonElement(Selected.serializer(), subscribed)) })
        val job = launch {
            events.collect { event ->
                val data = event.data as? JsonObject
                when (event.name) {
                    in eventNames -> if ((data?.get("subscription") as? JsonPrimitive)?.content == id) send(data)
                    DaemonEvents.REPLACED, DaemonEvents.STOPPING -> close(DaemonGoneException(describe(event)))
                }
            }
        }
        // A connection that drops without a lifecycle event (the daemon was killed) ends the stream too.
        val dropped = launch { close(DaemonGoneException(gone.filterNotNull().first())) }
        awaitClose {
            job.cancel()
            dropped.cancel()
            runCatching { send(DtaJson.encodeToString(Message.serializer(), Request(ids.incrementAndGet(), unsubscribe, buildJsonObject { put("subscription", id) }))) }
        }
    }

    /** Lifecycle events (`daemon.replaced`, `daemon.stopping`) and every other push. */
    val allEvents: Flow<Event> get() = events

    suspend fun close() {
        runCatching { sends.enqueue { socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye") }.await() }
        lost("closed")
    }

    private fun describe(event: Event): String {
        val data = event.data as? JsonObject
        return when (event.name) {
            DaemonEvents.REPLACED -> {
                val by = data?.get("by")?.let { DtaJson.decodeFromJsonElement(VersionIdentity.serializer(), it) }
                "the DTA daemon was replaced by a newer version${by?.let { " (${it.display})" } ?: ""}"
            }
            else -> "the DTA daemon stopped: ${(data?.get("reason") as? JsonPrimitive)?.content ?: "no reason given"}"
        }
    }

    private val sends = io.yamsergey.dta.protocol.wire.SendQueue()

    private fun send(text: String) = sends.enqueue { socket.sendText(text, true) }

    private fun lost(reason: String) {
        if (goneReason != null) return
        goneReason = reason
        gone.value = reason
        pending.values.forEach { it.completeExceptionally(DaemonGoneException(reason)) }
        pending.clear()
    }

    private inner class Listener : WebSocket.Listener {
        private val text = StringBuilder()

        override fun onOpen(webSocket: WebSocket) = webSocket.request(1)

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            text.append(data)
            if (last) {
                val message = runCatching { DtaJson.decodeFromString(Message.serializer(), text.toString()) }.getOrNull()
                text.setLength(0)
                when (message) {
                    is Response -> pending[message.id]?.complete(message)
                    is Event -> {
                        if (message.name == DaemonEvents.REPLACED || message.name == DaemonEvents.STOPPING) {
                            endedBy = message
                            events.tryEmit(message)
                            lost(describe(message))
                        } else {
                            events.tryEmit(message)
                        }
                    }
                    else -> Unit
                }
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            lost(goneReason ?: "the DTA daemon closed the connection ($statusCode${if (reason.isBlank()) "" else ": $reason"})")
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            lost("lost the connection to the DTA daemon (${error.message ?: error.javaClass.simpleName})")
        }
    }

    companion object {
        /** Dials [port] with [token] and says hello; throws [DaemonCallException] or an IO error. */
        suspend fun connect(
            port: Int,
            token: String,
            kind: ClientKind,
            identity: VersionIdentity,
            timeout: Duration = 2.minutes,
        ): DaemonClient {
            val client = DaemonClient(timeout)
            client.socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(3))
                .header("Authorization", "Bearer $token")
                .buildAsync(URI("ws://127.0.0.1:$port/"), client.Listener())
                .await()
            try {
                val hello = client.request(
                    DaemonMethods.HELLO,
                    DtaJson.encodeToJsonElement(DaemonHello.serializer(), DaemonHello(kind, identity)).jsonObject,
                    5.seconds,
                )
                client.daemon = DtaJson.decodeFromJsonElement(DaemonHelloResult.serializer(), hello)
                return client
            } catch (e: Exception) {
                runCatching { client.close() }
                throw e
            }
        }

        fun replaceParams(by: VersionIdentity): JsonObject = DtaJson.encodeToJsonElement(ReplaceParams.serializer(), ReplaceParams(by)).jsonObject
    }
}
