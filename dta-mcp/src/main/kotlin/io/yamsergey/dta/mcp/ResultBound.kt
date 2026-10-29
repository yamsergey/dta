package io.yamsergey.dta.mcp

import io.yamsergey.dta.protocol.ops.Selected
import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Keeps an agent tool result within [maxBytes] (research R8, FR-017): lists are cut at item
 * boundaries, layout trees at a depth, and the result says what was left out and how to get
 * it. Never cuts silently.
 */
class ResultBound(private val maxBytes: Int = DEFAULT_MAX_BYTES) {

    fun apply(operation: String, selected: Selected): String {
        val full = encode(selected, null)
        if (full.toByteArray().size <= maxBytes) return full
        val result = selected.result as? JsonObject
        return when {
            result != null && result["entries"] is JsonArray -> cutList(selected, result, "entries", "use limit with before (the page cursor), sinceMs, or narrower filters")
            result != null && result["windows"] is JsonArray -> cutTree(selected, result)
            result != null && result["matches"] is JsonArray -> cutList(selected, result, "matches", "narrow the filter (text, type, resourceId) or search under a rootId")
            result != null && result["lines"] is JsonArray -> cutList(selected, result, "lines", "call again with since set to the last line's seq")
            else -> {
                val text = full.take(maxBytes / 2)
                encode(
                    Selected(selected.selection, JsonPrimitive(text)),
                    truncated("everything after the first ${text.length} characters of $operation's result", "request less (filters), or for bodies use output to write a file"),
                )
            }
        }
    }

    private fun cutList(selected: Selected, result: JsonObject, key: String, how: String): String {
        val items = (result[key] as JsonArray)
        var keep = items.size
        var text: String
        do {
            keep = if (keep > 64) keep * 3 / 4 else keep - 1
            val cut = JsonObject(result + (key to JsonArray(items.take(keep.coerceAtLeast(0)))))
            text = encode(Selected(selected.selection, cut), truncated("${items.size - keep} of ${items.size} $key (the last ones)", how))
        } while (text.toByteArray().size > maxBytes && keep > 0)
        return text
    }

    private fun cutTree(selected: Selected, result: JsonObject): String {
        var depth = 32
        var text: String
        do {
            depth = if (depth > 8) depth - 4 else depth - 1
            var omitted = 0
            fun prune(node: JsonElement, level: Int): JsonElement {
                val obj = node as? JsonObject ?: return node
                val children = obj["children"] as? JsonArray ?: return obj
                if (level >= depth && children.isNotEmpty()) {
                    omitted += count(children)
                    return JsonObject(obj + ("children" to JsonArray(emptyList())) + ("childrenOmitted" to JsonPrimitive(children.size)))
                }
                return JsonObject(obj + ("children" to JsonArray(children.map { prune(it, level + 1) })))
            }
            val windows = (result["windows"] as JsonArray).map { w ->
                val window = w as JsonObject
                JsonObject(window + ("root" to prune(window["root"]!!, 0)))
            }
            val cut = JsonObject(result + ("windows" to JsonArray(windows)))
            text = encode(
                Selected(selected.selection, cut),
                truncated("$omitted nodes deeper than level $depth (marked with childrenOmitted)", "use filters (text, type, resourceId) or rootId to fetch a subtree"),
            )
        } while (text.toByteArray().size > maxBytes && depth > 1)
        return text
    }

    private fun count(nodes: JsonArray): Int = nodes.sumOf { n -> 1 + ((n as? JsonObject)?.get("children") as? JsonArray)?.let(::count).let { it ?: 0 } }

    private fun truncated(omitted: String, how: String) = buildJsonObject { put("omitted", omitted); put("how", how) }

    private fun encode(selected: Selected, truncated: JsonObject?): String {
        val base = DtaJson.encodeToJsonElement(Selected.serializer(), selected) as JsonObject
        return DtaJson.encodeToString(JsonObject.serializer(), if (truncated == null) base else JsonObject(base + ("truncated" to truncated)))
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 65_536

        fun fromEnv(env: Map<String, String> = System.getenv()) = ResultBound(env["DTA_MCP_MAX_BYTES"]?.toIntOrNull() ?: DEFAULT_MAX_BYTES)
    }
}
