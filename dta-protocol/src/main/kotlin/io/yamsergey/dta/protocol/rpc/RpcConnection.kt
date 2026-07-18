package io.yamsergey.dta.protocol.rpc

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Thrown by a handler to signal a parameter problem (contract §7, -32602). */
class InvalidParamsException(message: String) : Exception(message)

/**
 * Per-request context a handler runs against: its params, event push on the
 * calling connection, and binary attachments for the response (contract §4).
 */
class RpcContext internal constructor(
    val params: JsonObject?,
    private val eventSender: (String, JsonElement?) -> Long,
) {
    internal val binaries = mutableListOf<ByteArray>()
    internal val degradations = mutableListOf<DegradationMarker>()

    fun sendEvent(name: String, data: JsonElement? = null): Long = eventSender(name, data)

    /** Queues a binary payload; the dispatcher announces it on the response
     * and ships it as an id-tagged binary frame right after (contract §4). */
    fun attachBinary(payload: ByteArray) {
        binaries += payload
    }

    /** Declares that part of this response came from a degraded path; the
     * marker rides the response (constitution III — no silent degradation). */
    fun markDegraded(capability: String, reason: String) {
        degradations += DegradationMarker(capability, reason)
    }
}

typealias MethodHandler = (RpcContext) -> JsonElement?

class MethodRegistry {
    private val handlers = ConcurrentHashMap<String, MethodHandler>()

    fun register(method: String, handler: MethodHandler) {
        handlers[method] = handler
    }

    operator fun get(method: String): MethodHandler? = handlers[method]
}

/**
 * Server-side RPC message pump: routes incoming requests to [registry],
 * emits responses/events through [sendText] and binary continuations through
 * [sendBinary]. Transport-agnostic — the WebSocket adapter wires it up.
 */
class RpcConnection(
    private val registry: MethodRegistry,
    private val sendText: (String) -> Unit,
    private val sendBinary: (ByteArray) -> Unit = {},
) {
    private val eventSeq = AtomicLong(0)

    fun handleText(text: String) {
        val message = try {
            DtaJson.decodeFromString(Message.serializer(), text)
        } catch (_: SerializationException) {
            // Contract §2: malformed input is logged and ignored, never fatal.
            return
        } catch (_: IllegalArgumentException) {
            return
        }
        when (message) {
            is Request -> handleRequest(message)
            is Response, is Event -> Unit // server side issues these, never receives them
        }
    }

    private fun handleRequest(request: Request) {
        val handler = registry[request.method]
        if (handler == null) {
            send(errorResponse(request.id, ErrorCodes.METHOD_NOT_FOUND, "Unknown method: ${request.method}"))
            return
        }

        val context = RpcContext(request.params, ::sendEvent)
        val response = try {
            val result = handler(context) ?: buildJsonObject { }
            Response(
                id = request.id,
                result = result,
                degraded = context.degradations.ifEmpty { null },
                binary = if (context.binaries.isEmpty()) {
                    null
                } else {
                    BinaryAnnouncement(
                        frames = context.binaries.size,
                        totalBytes = context.binaries.sumOf { it.size.toLong() },
                    )
                },
            )
        } catch (e: InvalidParamsException) {
            errorResponse(request.id, ErrorCodes.INVALID_PARAMS, e.message ?: "invalid params")
        } catch (e: Exception) {
            // FR-013: short and actionable, never a stack trace.
            errorResponse(request.id, ErrorCodes.INTERNAL, shortMessage(e))
        }

        send(response)
        if (response.binary != null) {
            context.binaries.forEach { payload ->
                sendBinary(
                    ByteBuffer.allocate(8 + payload.size).putLong(request.id).put(payload).array(),
                )
            }
        }
    }

    fun sendEvent(name: String, data: JsonElement? = null): Long {
        val seq = eventSeq.incrementAndGet()
        send(Event(name = name, seq = seq, data = data))
        return seq
    }

    private fun send(message: Message) = sendText(DtaJson.encodeToString(Message.serializer(), message))

    private fun errorResponse(id: Long, code: Int, message: String) =
        Response(id = id, error = ErrorInfo(code = code, message = message))

    private fun shortMessage(e: Exception): String {
        val detail = e.message?.lineSequence()?.firstOrNull()?.trim()
        return if (detail.isNullOrBlank()) "${e.javaClass.simpleName} in handler" else detail
    }
}
