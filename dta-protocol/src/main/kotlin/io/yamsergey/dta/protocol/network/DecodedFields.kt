package io.yamsergey.dta.protocol.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Where in a decoded body (its proto3 JSON) a text occurs: field paths such as `items[0].name`. */
object DecodedFields {
    fun paths(json: String, needle: String, ignoreCase: Boolean = false): List<String> {
        val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() ?: return emptyList()
        return buildList { walk(root, "", needle, ignoreCase, this) }
    }

    /** Feature 053: paths of the values [test] accepts. */
    fun paths(json: String, test: (String) -> Boolean): List<String> {
        val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() ?: return emptyList()
        return buildList { walkWith(root, "", test, this) }
    }

    private fun walkWith(e: JsonElement, path: String, test: (String) -> Boolean, out: MutableList<String>) {
        when (e) {
            is JsonObject -> e.forEach { (k, v) -> walkWith(v, if (path.isEmpty()) k else "$path.$k", test, out) }
            is JsonArray -> e.forEachIndexed { i, v -> walkWith(v, "$path[$i]", test, out) }
            JsonNull -> Unit
            is JsonPrimitive -> if (test(e.content)) out += path.ifEmpty { "(value)" }
        }
    }

    private fun walk(e: JsonElement, path: String, needle: String, ignoreCase: Boolean, out: MutableList<String>) {
        when (e) {
            is JsonObject -> e.forEach { (k, v) -> walk(v, if (path.isEmpty()) k else "$path.$k", needle, ignoreCase, out) }
            is JsonArray -> e.forEachIndexed { i, v -> walk(v, "$path[$i]", needle, ignoreCase, out) }
            JsonNull -> Unit
            is JsonPrimitive -> if (e.content.contains(needle, ignoreCase)) out += path.ifEmpty { "(value)" }
        }
    }
}
