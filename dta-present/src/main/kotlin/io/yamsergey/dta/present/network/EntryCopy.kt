package io.yamsergey.dta.present.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** What Copy puts on the clipboard for a network entry (feature 026); from a `network.entry` result's `entry`. */
object EntryCopy {
    fun url(entry: JsonObject): String? = (entry["url"] as? JsonPrimitive)?.content

    /** `name: value` lines of [field] (`requestHeaders`, `responseHeaders`, `handshakeHeaders`); null when there are none. */
    fun headers(entry: JsonObject, field: String): String? =
        pairs(entry, field).takeIf { it.isNotEmpty() }?.joinToString("\n") { (n, v) -> "$n: $v" }

    /**
     * The request as a curl command: method, URL, headers (not HTTP/2 pseudo-headers or
     * Content-Length, which curl sets; Accept-Encoding becomes --compressed), and [body] when text.
     */
    fun curl(entry: JsonObject, body: String?): String {
        val method = (entry["method"] as? JsonPrimitive)?.content ?: "GET"
        val headers = pairs(entry, "requestHeaders").filter { (n, _) -> !n.startsWith(":") && !n.equals("Content-Length", true) }
        val parts = buildList {
            add(listOfNotNull("curl", if (method == "GET" && body == null) null else "-X $method", quote(url(entry) ?: "")).joinToString(" "))
            headers.filter { (n, _) -> !n.equals("Accept-Encoding", true) }.forEach { (n, v) -> add("-H ${quote("$n: $v")}") }
            if (headers.any { (n, _) -> n.equals("Accept-Encoding", true) }) add("--compressed")
            body?.let { add("--data-raw ${quote(it)}") }
        }
        return parts.joinToString(" \\\n  ")
    }

    /** The entry, indented. */
    fun json(entry: JsonObject): String = PrettyJson.format(entry.toString()) ?: entry.toString()

    private fun pairs(entry: JsonObject, field: String): List<Pair<String, String>> =
        (entry[field] as? JsonArray).orEmpty().map { it.jsonObject }.map { h ->
            ((h["name"] as? JsonPrimitive)?.content ?: "") to ((h["value"] as? JsonPrimitive)?.content ?: "")
        }

    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
