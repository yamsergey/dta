package io.yamsergey.dta.present.schemas

import io.yamsergey.dta.present.Statement
import io.yamsergey.dta.present.StatementKind
import io.yamsergey.dta.present.network.arr
import io.yamsergey.dta.present.network.bool
import io.yamsergey.dta.present.network.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A schema set: its name, where it came from, and its message types. */
data class SetRow(val name: String, val detail: String, val types: List<String>)

/** A mapping as one line; [broken] states why it cannot be used; built-in rules cannot be removed. */
data class MappingRow(val id: String, val text: String, val broken: Statement?, val builtin: Boolean) {
    val removable: Boolean get() = !builtin
}

/** One entry of the type picker when adding a mapping. */
data class TypeChoice(val schema: String, val type: String)

/**
 * The Schemas view (FR-014): the app's protobuf schema sets and the mappings that say which
 * bodies decode with which type — from `network.schema.list` and `network.mapping.list`.
 */
data class SchemasView(val sets: List<SetRow>, val mappings: List<MappingRow>, val types: List<TypeChoice>, val empty: String?) {
    companion object {
        fun from(sets: JsonArray, mappings: JsonArray): SchemasView {
            val setRows = sets.map { element ->
                val set = element as JsonObject
                val source = if (set.str("source") == "descriptor-set") "descriptor set" else ".proto sources"
                val files = set.arr("files").size
                SetRow(set.str("name").orEmpty(), "$source · $files ${if (files == 1) "file" else "files"}", set.arr("types").map { (it as JsonPrimitive).content })
            }
            val mappingRows = mappings.map { element ->
                val m = element as JsonObject
                val text = listOfNotNull(m.str("method"), m.str("urlPattern"), m.str("part"), m.str("direction"), "→ ${m.str("type")}", "(${m.str("schema")})").joinToString(" ")
                MappingRow(
                    id = m.str("id").orEmpty(),
                    text = text,
                    broken = m.str("broken")?.let { Statement(StatementKind.DEGRADED, detail = "broken: $it") },
                    builtin = m.bool("builtin"),
                )
            }
            val types = setRows.flatMap { set -> set.types.map { TypeChoice(set.name, it) } }
            return SchemasView(setRows, mappingRows, types, empty = if (setRows.isEmpty()) "No schema sets. Add .proto files from the project." else null)
        }
    }
}
