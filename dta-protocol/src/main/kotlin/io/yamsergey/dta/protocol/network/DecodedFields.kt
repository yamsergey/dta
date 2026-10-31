package io.yamsergey.dta.protocol.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Where in a decoded body (its proto3 JSON) a text occurs: field paths such as `items[0].name`. */
object DecodedFields {
    fun paths(json: String, needle: String): List<String> {
        val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() ?: return emptyList()
        return buildList { walk(root, "", needle, this) }
    }

    private fun walk(e: JsonElement, path: String, needle: String, out: MutableList<String>) {
        when (e) {
            is JsonObject -> e.forEach { (k, v) -> walk(v, if (path.isEmpty()) k else "$path.$k", needle, out) }
            is JsonArray -> e.forEachIndexed { i, v -> walk(v, "$path[$i]", needle, out) }
            JsonNull -> Unit
            is JsonPrimitive -> if (needle in e.content) out += path.ifEmpty { "(value)" }
        }
    }
}
