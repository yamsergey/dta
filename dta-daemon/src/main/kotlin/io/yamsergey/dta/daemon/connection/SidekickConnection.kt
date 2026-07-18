package io.yamsergey.dta.daemon.connection

import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.ErrorCodes
import io.yamsergey.dta.protocol.rpc.Event
import io.yamsergey.dta.protocol.rpc.HelloParams
import io.yamsergey.dta.protocol.rpc.HelloResult
import io.yamsergey.dta.protocol.rpc.Message
import io.yamsergey.dta.protocol.rpc.PROTOCOL_VERSION
import io.yamsergey.dta.protocol.rpc.Request
import io.yamsergey.dta.protocol.rpc.Response
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Daemon side of the single duplex channel: JDK built-in WebSocket client
 * (an independent RFC 6455 implementation — free conformance check of our
 * hand-rolled server), correlated requests, pushed events, binary
 * continuations, hello-on-connect.
 */
class SidekickConnection private constructor(
    private val defaultRequestTimeout: Duration,
) : AppConnection {

    private class PendingRequest {
        val deferred = CompletableDeferred<Pair<Response, List<ByteArray>>>()
        private var response: Response? = null
        private val binaries = mutableListOf<ByteArray>()

        @Synchronized
        fun onResponse(incoming: Response) {
            response = incoming
            maybeComplete()
        }

        @Synchronized
        fun onBinary(payload: ByteArray) {
            binaries += payload
            maybeComplete()
        }

        private fun maybeComplete() {
            val current = response ?: return
            val expectedFrames = current.binary?.frames ?: 0
            if (current.error != null || binaries.size >= expectedFrames) {
                deferred.complete(current to binaries.toList())
            }
        }

        fun fail(error: Throwable) {
            deferred.completeExceptionally(error)
        }
    }

    private lateinit var webSocket: WebSocket
    private lateinit var helloResult: HelloResult

    private val pending = ConcurrentHashMap<Long, PendingRequest>()
    private val ids = AtomicLong(0)
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 256)

    @Volatile
    private var open = false

    @Volatile
    private var lossReason: String? = null

    override val hello: HelloResult get() = helloResult
    override val events: SharedFlow<Event> = _events.asSharedFlow()
    override val isOpen: Boolean get() = open

    override suspend fun request(method: String, params: JsonObject?, timeout: Duration?): JsonElement =
        requestFull(method, params, timeout).result

    override suspend fun requestFull(method: String, params: JsonObject?, timeout: Duration?): RpcResult {
        if (!open) throw ConnectionLostException(lossReason ?: "connection closed")
        val id = ids.incrementAndGet()
        val entry = PendingRequest()
        pending[id] = entry
        try {
            val text = DtaJson.encodeToString(Message.serializer(), Request(id, method, params))
            webSocket.sendText(text, true).await()

            val effective = timeout ?: defaultRequestTimeout
            val (response, binaries) = try {
                withTimeout(effective) { entry.deferred.await() }
            } catch (e: TimeoutCancellationException) {
                throw RequestTimeoutException(
                    "No response to '$method' within $effective — the app may be suspended; " +
                        "bring it to the foreground and retry",
                )
            }
            response.error?.let { throw SidekickRpcException(it.code, it.message, it.details) }
            return RpcResult(
                result = response.result ?: JsonNull,
                binaryPayloads = binaries,
                degraded = response.degraded,
            )
        } finally {
            pending.remove(id)
        }
    }

    override suspend fun close() {
        open = false
        runCatching { webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").await() }
    }

    private fun handleText(text: String) {
        val message = try {
            DtaJson.decodeFromString(Message.serializer(), text)
        } catch (_: SerializationException) {
            return
        } catch (_: IllegalArgumentException) {
            return
        }
        when (message) {
            // Unknown id = late response after timeout: ignored by contract §2.
            is Response -> pending[message.id]?.onResponse(message)
            is Event -> _events.tryEmit(message)
            is Request -> Unit // v1: sidekick never issues requests
        }
    }

    private fun handleBinary(frame: ByteArray) {
        if (frame.size < 8) return // malformed tag; contract §2 spirit: ignore, don't die
        var id = 0L
        for (i in 0 until 8) id = (id shl 8) or (frame[i].toLong() and 0xFF)
        pending[id]?.onBinary(frame.copyOfRange(8, frame.size))
    }

    private fun handleLost(reason: String) {
        if (!open && lossReason != null) return
        open = false
        lossReason = reason
        val waiting = pending.values.toList()
        pending.clear()
        waiting.forEach { it.fail(ConnectionLostException(reason)) }
    }

    private inner class Listener : WebSocket.Listener {
        private val textBuffer = StringBuilder()
        private val binaryBuffer = ByteArrayOutputStream()

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            textBuffer.append(data)
            if (last) {
                val text = textBuffer.toString()
                textBuffer.setLength(0)
                handleText(text)
            }
            webSocket.request(1)
            return null
        }

        override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
            val chunk = ByteArray(data.remaining())
            data.get(chunk)
            binaryBuffer.write(chunk)
            if (last) {
                val frame = binaryBuffer.toByteArray()
                binaryBuffer.reset()
                handleBinary(frame)
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            handleLost("app disconnected (close $statusCode${if (reason.isBlank()) "" else ": $reason"})")
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            handleLost("app disconnected (${error.message ?: error.javaClass.simpleName})")
        }
    }

    companion object {
        /** Dials, performs hello, verifies versions and package. Throws
         * [VersionIncompatibleException] / [ConnectException] with actionable
         * messages; never returns a half-open connection. */
        suspend fun connect(
            uri: URI,
            expectedPackage: String? = null,
            clientVersion: String = "dev",
            requestTimeout: Duration = 10.seconds,
        ): SidekickConnection {
            val connection = SidekickConnection(requestTimeout)
            connection.webSocket = try {
                HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5))
                    .buildAsync(uri, connection.Listener())
                    .await()
            } catch (e: Exception) {
                throw ConnectException(
                    "cannot reach sidekick at $uri (${e.message ?: e.javaClass.simpleName}) — " +
                        "restart the app and retry",
                )
            }
            connection.open = true

            try {
                val params = DtaJson.encodeToJsonElement(
                    HelloParams.serializer(),
                    HelloParams(PROTOCOL_VERSION, "dta-daemon", clientVersion),
                ).jsonObject

                val result = try {
                    connection.request("hello", params)
                } catch (e: SidekickRpcException) {
                    if (e.code == ErrorCodes.VERSION_INCOMPATIBLE) {
                        val sidekickVersion =
                            e.details?.get("sidekick")?.jsonPrimitive?.intOrNull ?: "?"
                        throw VersionIncompatibleException(
                            "sidekick protocol $sidekickVersion != daemon protocol $PROTOCOL_VERSION — " +
                                "rebuild the app with the matching dta-sidekick",
                        )
                    }
                    throw ConnectException("hello failed: ${e.message}")
                }

                connection.helloResult = DtaJson.decodeFromJsonElement(HelloResult.serializer(), result)
                if (expectedPackage != null && connection.helloResult.packageName != expectedPackage) {
                    throw ConnectException(
                        "wrong socket: dialed $expectedPackage but reached " +
                            connection.helloResult.packageName,
                    )
                }
                return connection
            } catch (e: Exception) {
                runCatching { connection.close() }
                throw e
            }
        }
    }
}
