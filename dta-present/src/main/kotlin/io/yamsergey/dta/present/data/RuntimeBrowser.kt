package io.yamsergey.dta.present.data

import io.yamsergey.dta.protocol.data.ViewModelInfo
import kotlinx.serialization.json.Json

/** Feature 028 US5: a ViewModel's properties and saved state as editable rows of the Runtime tab. */
class PropertyRows(private val vm: ViewModelInfo) {
    data class Row(val label: String, val kind: String, val value: String, val editable: Boolean, val saved: Boolean, val path: String, val note: String?)

    val rows: List<Row> = vm.properties.map { Row(it.path, it.kind, it.value.toString(), it.editable, false, it.path, it.note) } +
        vm.savedState.orEmpty().map { Row("saved: ${it.path}", it.kind, it.value.toString(), true, true, it.path, null) }

    /** The operation and params an edit of row [i] to [text] makes. */
    fun edit(i: Int, text: String): Pair<String, Map<String, String>> {
        val r = rows[i]
        require(r.editable) { "${r.label} is ${r.note ?: "read-only"}" }
        val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull()
        // The parser takes a bare word as a literal; only true, false, null and numbers are.
        val bare = parsed is kotlinx.serialization.json.JsonPrimitive && !parsed.isString &&
            parsed.content !in setOf("true", "false", "null") && parsed.content.toDoubleOrNull() == null
        require(parsed != null && !bare) { "a property value is JSON: a string in quotes (\"text\"), a number, true/false, null or an object" }
        return if (r.saved) "runtime.saved.set" to mapOf("viewModel" to vm.id, "key" to r.path, "value" to text)
        else "runtime.vm.set" to mapOf("viewModel" to vm.id, "path" to r.path, "value" to text)
    }
}
