package io.yamsergey.dta.protocol.rpc

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Thrown by a handler to signal a parameter problem (contract §7, -32602). */
class InvalidParamsException(message: String) : Exception(message)

class MethodRegistry {
    private val handlers = ConcurrentHashMap<String, (JsonObject?) -> JsonElement?>()

    fun register(method: String, handler: (JsonObject?) -> JsonElement?) {
        handlers[method] = handler
    }

    operator fun get(method: String): ((JsonObject?) -> JsonElement?)? = handlers[method]
}

/**
 * Server-side RPC message pump: routes incoming requests to [registry],
 * emits responses and events through [sendText]. Transport-agnostic —
 * the WebSocket adapter wires it to a real connection.
 */
class RpcConnection(
    private val registry: MethodRegistry,
    private val sendText: (String) -> Unit,
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
        val response = if (handler == null) {
            errorResponse(request.id, ErrorCodes.METHOD_NOT_FOUND, "Unknown method: ${request.method}")
        } else {
            try {
                Response(id = request.id, result = handler(request.params) ?: buildJsonObject { })
            } catch (e: InvalidParamsException) {
                errorResponse(request.id, ErrorCodes.INVALID_PARAMS, e.message ?: "invalid params")
            } catch (e: Exception) {
                // FR-013: short and actionable, never a stack trace.
                errorResponse(request.id, ErrorCodes.INTERNAL, shortMessage(e))
            }
        }
        send(response)
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
