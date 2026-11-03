package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.protocol.network.Direction
import io.yamsergey.dta.protocol.network.Header
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.MessageOpcode
import io.yamsergey.dta.protocol.network.WsConnectionEntry
import io.yamsergey.dta.protocol.network.WsMessageEntry
import io.yamsergey.dta.protocol.network.WsState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLDecoder
import java.time.Instant

/**
 * HAR 1.2 (feature 026), as browsers' developer tools and proxies import it. WebSocket traffic
 * follows Chrome: a `websocket` entry with `_webSocketMessages`. DTA's own fields start with `_`.
 */
object Har {
    /** A body for the file: [text] as text, or base64 of binary bytes; null [text] with a [comment] when it is not stored. */
    data class Content(val mimeType: String?, val size: Long, val text: String?, val base64: Boolean = false, val comment: String? = null)

    fun http(e: HttpEntry, request: Content?, response: Content?): JsonObject = buildJsonObject {
        put("startedDateTime", iso(e.timeMs))
        put("time", e.durationMs ?: 0)
        put("request", buildJsonObject {
            put("method", e.method)
            put("url", e.url)
            put("httpVersion", httpVersion(e.protocol))
            put("cookies", JsonArray(emptyList()))
            put("headers", headers(e.requestHeaders))
            put("queryString", query(e.url))
            request?.let { body ->
                put("postData", buildJsonObject {
                    put("mimeType", body.mimeType ?: "")
                    body.text?.let { put("text", it) }
                    if (body.base64) put("_encoding", "base64")
                    body.comment?.let { put("comment", it) }
                })
            }
            put("headersSize", -1)
            put("bodySize", request?.size ?: 0)
        })
        put("response", buildJsonObject {
            put("status", e.status ?: 0)
            put("statusText", "")
            put("httpVersion", httpVersion(e.protocol))
            put("cookies", JsonArray(emptyList()))
            put("headers", headers(e.responseHeaders))
            put("content", content(response))
            put("redirectURL", e.responseHeaders?.firstOrNull { it.name.equals("Location", true) }?.value ?: "")
            put("headersSize", -1)
            put("bodySize", response?.size ?: -1)
        })
        put("cache", JsonObject(emptyMap()))
        put("timings", timings(e.durationMs ?: 0))
        e.failure?.let { put("_error", it) }
        put("_dta", dta(e.id, e.library, e.state.name.lowercase()))
    }

    fun webSocket(c: WsConnectionEntry, messages: List<Pair<WsMessageEntry, Content?>>): JsonObject = buildJsonObject {
        put("startedDateTime", iso(c.timeMs))
        val time = ((c.openedAtMs ?: c.timeMs) - c.timeMs).coerceAtLeast(0)
        put("time", time)
        put("request", buildJsonObject {
            put("method", "GET")
            put("url", c.url)
            put("httpVersion", "HTTP/1.1")
            put("cookies", JsonArray(emptyList()))
            put("headers", headers(c.handshakeHeaders))
            put("queryString", query(c.url))
            put("headersSize", -1)
            put("bodySize", 0)
        })
        put("response", buildJsonObject {
            put("status", if (c.openedAtMs != null || c.state == WsState.OPEN || c.state == WsState.CLOSED) 101 else 0)
            put("statusText", "")
            put("httpVersion", "HTTP/1.1")
            put("cookies", JsonArray(emptyList()))
            put("headers", JsonArray(emptyList()))
            put("content", content(null))
            put("redirectURL", "")
            put("headersSize", -1)
            put("bodySize", 0)
        })
        put("cache", JsonObject(emptyMap()))
        put("timings", timings(time))
        put("_resourceType", "websocket")
        put("_webSocketMessages", buildJsonArray {
            messages.forEach { (m, body) ->
                add(buildJsonObject {
                    put("type", if (m.direction == Direction.OUT) "send" else "receive")
                    put("time", m.timeMs / 1000.0)
                    put("opcode", if (m.opcode == MessageOpcode.TEXT) 1 else 2)
                    put("data", body?.text ?: "")
                    body?.comment?.let { put("_comment", it) }
                })
            }
        })
        (c.failure ?: c.closeReason?.let { "closed ${c.closeCode ?: ""} $it".trim() })?.let { put("_error", it) }
        put("_dta", dta(c.id, c.library, c.state.name.lowercase()))
    }

    fun document(entries: List<JsonObject>, version: String): JsonObject = buildJsonObject {
        put("log", buildJsonObject {
            put("version", "1.2")
            put("creator", buildJsonObject { put("name", "DTA"); put("version", version) })
            put("pages", JsonArray(emptyList()))
            put("entries", JsonArray(entries))
        })
    }

    private fun content(body: Content?) = buildJsonObject {
        put("size", body?.size ?: 0)
        put("mimeType", body?.mimeType ?: "")
        body?.text?.let { put("text", it) }
        if (body?.base64 == true) put("encoding", "base64")
        body?.comment?.let { put("comment", it) }
    }

    private fun timings(wait: Long) = buildJsonObject {
        put("blocked", -1); put("dns", -1); put("connect", -1); put("ssl", -1)
        put("send", 0); put("wait", wait); put("receive", 0)
    }

    private fun dta(id: String, library: String, state: String) = buildJsonObject {
        put("id", id); put("library", library); put("state", state)
    }

    private fun headers(list: List<Header>?) = buildJsonArray {
        list.orEmpty().forEach { h -> add(buildJsonObject { put("name", h.name); put("value", h.value) }) }
    }

    private fun query(url: String) = buildJsonArray {
        val raw = runCatching { URI(url).rawQuery }.getOrNull() ?: return@buildJsonArray
        raw.split('&').filter { it.isNotEmpty() }.forEach { pair ->
            val (k, v) = pair.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            add(buildJsonObject { put("name", decode(k)); put("value", decode(v)) })
        }
    }

    private fun decode(s: String) = runCatching { URLDecoder.decode(s, Charsets.UTF_8) }.getOrDefault(s)

    private fun httpVersion(protocol: String?): String = when (protocol?.lowercase()) {
        null, "" -> "HTTP/1.1"
        "h2", "h2_prior_knowledge" -> "HTTP/2"
        "h3", "quic" -> "HTTP/3"
        else -> protocol.uppercase()
    }

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

}
