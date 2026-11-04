package io.yamsergey.dta.present.data

import io.yamsergey.dta.protocol.data.ViewModelInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Feature 028 US5: a ViewModel's properties and saved state as editable rows of the Runtime tab.
 * Feature 031: each row has a short preview for the table and a pretty text for the value viewer.
 */
class PropertyRows(private val vm: ViewModelInfo) {
    data class Row(
        val label: String, val kind: String, val value: String, val editable: Boolean, val saved: Boolean, val path: String, val note: String?,
        val type: String = "", val json: JsonElement = Json.parseToJsonElement(value),
    ) {
        val preview: String get() = preview(json)
    }

    val rows: List<Row> = vm.properties.map { Row(it.path, it.kind, it.value.toString(), it.editable, false, it.path, it.note, it.type, it.value) } +
        vm.savedState.orEmpty().map { Row("saved: ${it.path}", it.kind, it.value.toString(), true, true, it.path, null, it.type, it.value) }

    /** Row [i]'s value indented, as the viewer shows it. */
    fun pretty(i: Int): String = PRETTY.encodeToString(JsonElement.serializer(), rows[i].json)

    /** Whether row [i] has structure to show as a tree (an object or an array). */
    fun structured(i: Int): Boolean = rows[i].json.let { it is JsonObject || it is JsonArray }

    /** The viewer's header for row [i]: path · kind · type · editable or why not. */
    fun header(i: Int): String = rows[i].let { r ->
        listOf(r.label, r.kind, r.type, if (r.editable) "editable" else "read-only" + (r.note?.let { " ($it)" } ?: "")).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /** Every property by its path, saved state under `savedState`, indented: shown when no row is chosen. */
    fun whole(): String {
        val props = vm.properties.associate { it.path to it.value }
        val saved = vm.savedState?.takeIf { it.isNotEmpty() }?.let { s -> mapOf("savedState" to JsonObject(s.associate { it.path to it.value })) }.orEmpty()
        return PRETTY.encodeToString(JsonElement.serializer(), JsonObject(props + saved))
    }

    /** The operation and params an edit of row [i] to [text] makes. */
    fun edit(i: Int, text: String): Pair<String, Map<String, String>> {
        val r = rows[i]
        require(r.editable) { "${r.label} is ${r.note ?: "read-only"}" }
        val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull()
        // The parser takes a bare word as a literal; only true, false, null and numbers are.
        val bare = parsed is kotlinx.serialization.json.JsonPrimitive && !parsed.isString &&
            parsed.content !in setOf("true", "false", "null") && parsed.content.toDoubleOrNull() == null
        require(parsed != null && !bare) { "a property value is JSON: a string in quotes (\"text\"), a number, true/false, null or an object" }
        // Sent compact, whatever indentation the viewer's editor had.
        val compact = parsed.toString()
        return if (r.saved) "runtime.saved.set" to mapOf("viewModel" to vm.id, "key" to r.path, "value" to compact)
        else "runtime.vm.set" to mapOf("viewModel" to vm.id, "path" to r.path, "value" to compact)
    }

    companion object {
        const val PREVIEW_LIMIT = 80
        private val PRETTY = Json { prettyPrint = true; prettyPrintIndent = "  " }

        /** A value in one short line: objects and arrays prefixed with their size, cut with … at [PREVIEW_LIMIT]. */
        fun preview(value: JsonElement): String {
            val size = when (value) { is JsonObject -> "{${value.size}} "; is JsonArray -> "[${value.size}] "; else -> "" }
            val text = size + value.toString()
            return if (text.length <= PREVIEW_LIMIT) text else text.take(PREVIEW_LIMIT - 1) + "…"
        }
    }
}
