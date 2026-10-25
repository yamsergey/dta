package io.yamsergey.dta.protocol.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The one redactor (constitution VI), applied by the daemon when output is requested
 * with `redact = true` (US6, FR-030..032). Masks credential headers, and fields whose
 * names look like secrets or personal data in URLs, JSON and form bodies (a sensitive
 * JSON key masks its whole value, nested objects included). Formats it
 * cannot inspect are withheld rather than passed through, and every masked or withheld
 * location is listed, so redacted output is safe to share and says what it hid.
 */
object Redactor {

    const val MASK = "[redacted]"

    private val SECRET_HEADERS = setOf("authorization", "proxy-authorization", "cookie", "set-cookie")
    private val SENSITIVE_PARTS = listOf(
        "token", "secret", "password", "passwd", "apikey", "api_key", "api-key", "auth", "session", "cookie",
        "credential", "private", "signature", "email", "phone", "ssn", "card",
    )
    private val json = Json { isLenient = false }

    data class BodyRedaction(
        /** Redacted text; null when withheld. */
        val text: String?,
        val masked: List<String>,
        /** Why the body was withheld; null when it was inspected. */
        val withheld: String?,
    )

    fun isSensitive(name: String): Boolean {
        val lower = name.lowercase()
        return lower in SECRET_HEADERS || SENSITIVE_PARTS.any { it in lower }
    }

    fun headers(headers: List<Header>, location: String): Pair<List<Header>, List<String>> {
        val masked = mutableListOf<String>()
        val result = headers.map { h ->
            if (isSensitive(h.name)) {
                masked += "$location.header ${h.name}"
                h.copy(value = MASK)
            } else h
        }
        return result to masked
    }

    fun url(url: String, location: String): Pair<String, List<String>> {
        val q = url.indexOf('?')
        if (q < 0) return url to emptyList()
        val fragment = url.indexOf('#', q).takeIf { it >= 0 } ?: url.length
        val (query, masked) = pairs(url.substring(q + 1, fragment), "$location url.query")
        return url.substring(0, q + 1) + query + url.substring(fragment) to masked
    }

    fun body(text: String, contentType: String?, location: String): BodyRedaction {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase()
        if (type == "application/x-www-form-urlencoded") {
            val (redacted, masked) = pairs(text, "$location form")
            return BodyRedaction(redacted, masked, null)
        }
        val parsed = if (type == null || "json" in type) runCatching { json.parseToJsonElement(text) }.getOrNull() else null
        if (parsed != null) {
            val masked = mutableListOf<String>()
            return BodyRedaction(redactJson(parsed, "$", location, masked).toString(), masked, null)
        }
        return BodyRedaction(null, emptyList(), "$location (${type ?: "unrecognized format"} not inspected)")
    }

    /** List form of an entry: URL, headers and previews. Withheld previews are dropped. */
    fun entry(entry: NetworkEntry): Pair<NetworkEntry, RedactionInfo> {
        val masked = mutableListOf<String>()
        val withheld = mutableListOf<String>()
        val at = "entry ${entry.id}"
        fun preview(info: BodyInfo?, name: String): BodyInfo? {
            val text = info?.preview ?: return info
            val r = body(text, info.contentType, "$at $name preview")
            masked += r.masked
            r.withheld?.let { withheld += it }
            return info.copy(preview = r.text)
        }
        fun hdrs(list: List<Header>?, name: String) = list?.let { headers(it, "$at $name").also { (_, m) -> masked += m }.first }

        val redacted = when (entry) {
            is HttpEntry -> {
                val (u, m) = url(entry.url, at)
                masked += m
                entry.copy(
                    url = u,
                    requestHeaders = hdrs(entry.requestHeaders, "request"),
                    responseHeaders = hdrs(entry.responseHeaders, "response"),
                    requestBody = preview(entry.requestBody, "request"),
                    responseBody = preview(entry.responseBody, "response"),
                )
            }
            is WsConnectionEntry -> {
                val (u, m) = url(entry.url, at)
                masked += m
                entry.copy(url = u, handshakeHeaders = hdrs(entry.handshakeHeaders, "handshake"))
            }
            is WsMessageEntry -> entry.copy(payload = preview(entry.payload, "payload")!!)
        }
        return redacted to RedactionInfo(masked = masked, withheld = withheld)
    }

    private fun pairs(encoded: String, location: String): Pair<String, List<String>> {
        val masked = mutableListOf<String>()
        val result = encoded.split('&').joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            val name = if (eq < 0) pair else pair.substring(0, eq)
            if (eq >= 0 && isSensitive(name)) {
                masked += "$location $name"
                "$name=$MASK"
            } else pair
        }
        return result to masked
    }

    private fun redactJson(element: JsonElement, path: String, location: String, masked: MutableList<String>): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.mapValues { (key, value) ->
                val childPath = "$path.$key"
                if (isSensitive(key)) {
                    masked += "$location $childPath"
                    JsonPrimitive(MASK)
                } else redactJson(value, childPath, location, masked)
            },
        )
        is JsonArray -> JsonArray(element.mapIndexed { i, value -> redactJson(value, "$path[$i]", location, masked) })
        else -> element
    }
}
