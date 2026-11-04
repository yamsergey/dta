package io.yamsergey.dta.protocol.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Feature 038: what a WebSocket message is — the envelope common protocols put around their
 * payload: [format] (json, json-rpc, graphql-ws, prefix+json, socket.io, phoenix, stomp,
 * signalr), its [kind] (type, event, command, method), [id] (request / message / ack id) and
 * [topic] (channel, destination), and where the [payload] is. Works on the whole text or on a cut
 * preview. Null when the text has no envelope DTA knows: then it is shown as it is.
 * Feature 039: [rule] is the app's own format rule that read it (its name is the [format]);
 * [note] says what did not come out as the rule says (a payload that is not JSON).
 * The daemon puts it on every text message it returns; clients show it.
 */
@Serializable
data class Envelope(
    val format: String,
    val kind: String? = null,
    val id: String? = null,
    val topic: String? = null,
    val payload: String? = null,
    val rule: String? = null,
    val note: String? = null,
)

object WsFormats {
    private val KIND_KEYS = listOf("type", "event", "action", "op", "cmd", "command", "method", "t", "e")
    private val ID_KEYS = listOf("id", "requestId", "request_id", "reqId", "ref", "correlationId", "correlation_id", "messageId", "message_id", "seq", "nonce")
    private val TOPIC_KEYS = listOf("topic", "channel", "destination", "stream", "room", "subject", "identifier", "symbol")
    private val GRAPHQL_WS = setOf("connection_init", "connection_ack", "connection_error", "subscribe", "next", "complete", "error", "start", "stop", "data", "ka", "connection_keep_alive", "connection_terminate")
    private val STOMP = Regex("^(CONNECT|CONNECTED|STOMP|SEND|SUBSCRIBE|UNSUBSCRIBE|MESSAGE|RECEIPT|ERROR|ACK|NACK|DISCONNECT|BEGIN|COMMIT|ABORT)\\r?\\n")
    private val SOCKET_IO = Regex("^([0-6])([0-6])?(/[^,\\[]*,)?(\\d+)?(\\[.*)?$", RegexOption.DOT_MATCHES_ALL)
    private val PHOENIX = Regex("^\\[\\s*(null|\"[^\"]*\")\\s*,\\s*(null|\"[^\"]*\")\\s*,\\s*\"([^\"]*)\"\\s*,\\s*\"([^\"]*)\"\\s*,")
    private val PREFIX = Regex("^([A-Za-z_][\\w.:/-]{0,63})[ \\t]*[\\r\\n:| \\t][ \\t\\r\\n]*([{\\[].*)$", RegexOption.DOT_MATCHES_ALL)
    private val json = Json { isLenient = false }

    /** Feature 039: the app's [rule] for this socket first, then the formats DTA knows. */
    fun detect(text: String, rule: FormatRule?): Envelope? =
        rule?.let { FormatRules.apply(it, text) } ?: detect(text)

    fun detect(text: String): Envelope? {
        if (text.isBlank()) return null
        val t = text.trimStart()
        if ('\u001e' in t) return signalR(t)
        STOMP.find(t)?.let { return stomp(t, it.groupValues[1]) }
        if (t.startsWith("[")) PHOENIX.find(t)?.let { m ->
            return Envelope("phoenix", m.groupValues[4], unquote(m.groupValues[2]), m.groupValues[3], null)
        }
        if (t.first().isDigit()) socketIo(t)?.let { return it }
        if (t.startsWith("{")) return jsonEnvelope(t)
        PREFIX.find(t)?.let { m ->
            val payload = m.groupValues[2].trimEnd()
            val inner = if (payload.startsWith("{")) jsonEnvelope(payload) else null
            return Envelope("prefix+json", m.groupValues[1], inner?.id, inner?.topic, payload)
        }
        return null
    }

    private fun jsonEnvelope(t: String): Envelope {
        val keys = topLevel(t)
        fun first(names: List<String>) = names.firstNotNullOfOrNull { keys[it] }
        val kind = first(KIND_KEYS)
        val id = first(ID_KEYS)
        val topic = first(TOPIC_KEYS)
        return when {
            keys.containsKey("jsonrpc") -> Envelope("json-rpc", keys["method"] ?: (if (keys.containsKey("error")) "error" else if (keys.containsKey("result")) "result" else null), keys["id"], null, t)
            kind != null && kind in GRAPHQL_WS && (keys.containsKey("payload") || kind.startsWith("connection") || kind == "complete" || kind == "ka") -> Envelope("graphql-ws", kind, keys["id"], null, t)
            else -> Envelope("json", kind, id, topic, t)
        }
    }

    /**
     * The scalar values of the object's top-level keys: parsed when the text is whole JSON, else
     * read with a depth-tracking scan of the cut text (nested keys are never taken).
     */
    private fun topLevel(t: String): Map<String, String> {
        runCatching { json.parseToJsonElement(t) as JsonObject }.getOrNull()?.let { o ->
            return o.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.let { k to it.content } ?: (if (v is JsonObject || v is JsonArray) k to "" else null) }
                .filter { it.second.isNotEmpty() || it.first in setOf("payload", "result", "error", "params") }.toMap()
        }
        val out = LinkedHashMap<String, String>()
        var depth = 0; var i = 0; var inString = false; var keyStart = -1; var key: String? = null; var expectValue = false
        while (i < t.length) {
            val c = t[i]
            if (inString) {
                if (c == '\\') { i += 2; continue }
                if (c == '"') {
                    inString = false
                    val s = t.substring(keyStart, i)
                    if (depth == 1) { if (expectValue && key != null) { out[key] = s; key = null; expectValue = false } else key = s }
                }
                i++; continue
            }
            when (c) {
                '"' -> { inString = true; keyStart = i + 1 }
                '{', '[' -> { if (depth == 1 && expectValue && key != null) { out[key!!] = ""; key = null; expectValue = false }; depth++ }
                '}', ']' -> depth--
                ':' -> if (depth == 1) expectValue = true
                ',' -> if (depth == 1) { key = null; expectValue = false }
                else -> if (depth == 1 && expectValue && key != null && !c.isWhitespace()) {
                    val end = t.indexOfAny(charArrayOf(',', '}', ']'), i).let { if (it < 0) t.length else it }
                    out[key!!] = t.substring(i, end).trim(); key = null; expectValue = false; i = end; continue
                }
            }
            i++
        }
        return out
    }

    private fun stomp(t: String, command: String): Envelope {
        val head = t.substringBefore("\n\n").substringBefore("\r\n\r\n")
        val headers = head.lineSequence().drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
        val body = if ("\n\n" in t) t.substringAfter("\n\n").trimEnd('\u0000', '\n', '\r') else null
        val id = headers["message-id"] ?: headers["receipt-id"] ?: headers["receipt"] ?: headers["id"] ?: headers["subscription"]
        return Envelope("stomp", command, id, headers["destination"], body?.ifEmpty { null })
    }

    private fun socketIo(t: String): Envelope? {
        val m = SOCKET_IO.matchEntire(t) ?: return null
        val engine = m.groupValues[1]
        val packet = m.groupValues[2]
        val data = m.groupValues[5]
        if (packet.isEmpty() && data.isEmpty() && m.groupValues[3].isEmpty() && m.groupValues[4].isEmpty()) {
            // Engine.IO alone: 2 ping, 3 pong, …; only a bare packet type, never a longer number.
            return if (t.length == 1) Envelope("socket.io", ENGINE[engine] ?: engine) else null
        }
        if (engine != "4" || data.isEmpty()) return null
        val event = Regex("^\\[\\s*\"([^\"]*)\"").find(data)?.groupValues?.get(1)
        return Envelope("socket.io", event ?: SOCKET[packet] ?: packet, m.groupValues[4].ifEmpty { null }, m.groupValues[3].removeSuffix(",").ifEmpty { null }, data)
    }

    private fun signalR(t: String): Envelope? {
        val record = t.substringBefore('\u001e')
        val keys = runCatching { topLevel(record) }.getOrDefault(emptyMap())
        val type = keys["type"]?.toIntOrNull()
        val kind = keys["target"] ?: SIGNALR[type] ?: if (keys.containsKey("protocol")) "handshake" else null
        return Envelope("signalr", kind, keys["invocationId"], null, record)
    }

    private fun unquote(s: String) = if (s == "null") null else s.trim('"')

    private val ENGINE = mapOf("0" to "open", "1" to "close", "2" to "ping", "3" to "pong", "4" to "message", "5" to "upgrade", "6" to "noop")
    private val SOCKET = mapOf("0" to "connect", "1" to "disconnect", "2" to "event", "3" to "ack", "4" to "error", "5" to "binary event", "6" to "binary ack")
    private val SIGNALR = mapOf(1 to "invocation", 2 to "stream item", 3 to "completion", 4 to "stream invocation", 5 to "cancel", 6 to "ping", 7 to "close")
}
