package io.yamsergey.dta.protocol.data

import io.yamsergey.dta.protocol.network.Redactor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Feature 074: field values for agents go through the one redactor (constitution VI): a field whose name looks
 * like a secret or personal data is masked whole, other values by the redactor's text rules, nested objects by
 * their keys. Names, types, lengths and what is static stay, so the answer is still a map of the object.
 */
object HeapRedaction {
    fun apply(f: HeapFields): HeapFields {
        var masked = 0
        fun walk(name: String?, v: JsonElement, top: Boolean): JsonElement = when {
            name != null && Redactor.isSensitive(name) && !(v is JsonPrimitive && v.isString && v.content == Redactor.MASK) -> { masked++; JsonPrimitive(Redactor.MASK) }
            v is JsonObject -> JsonObject(v.mapValues { (k, x) -> walk(k, x, false) })
            v is JsonArray -> JsonArray(v.map { walk(null, it, false) })
            v is JsonPrimitive && v.isString -> Redactor.text(v.content, "heap field ${name ?: ""}").let { (text, hits) -> if (hits.isNotEmpty()) { masked++; JsonPrimitive(text) } else v }
            else -> v
        }
        val fields = f.fields.map { fld -> fld.value?.let { fld.copy(value = walk(fld.name, it, true)) } ?: fld }
        val said = if (masked == 0) "Values redacted for agents: no value needed masking; names, types and lengths are shown as they are."
        else "Values redacted for agents: $masked masked (names that look like secrets or personal data, and credentials in text); names, types and lengths are shown as they are."
        return f.copy(fields = fields, redaction = said)
    }
}
