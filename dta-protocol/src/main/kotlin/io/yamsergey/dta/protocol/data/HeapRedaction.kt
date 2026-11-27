package io.yamsergey.dta.protocol.data

import io.yamsergey.dta.protocol.network.MaskedLocation
import io.yamsergey.dta.protocol.network.RedactionInfo
import io.yamsergey.dta.protocol.network.Redactor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Feature 074 (B3), following 072: for agents every string and primitive field value is masked by value, as
 * `[redacted len=N #fp12]` with the keyed fingerprint (decision: names do not decide, so `MODE_PRIVATE` is as masked as
 * `password`). Names, types, static flags, lengths, nulls and the shape of nested values stay readable; every masked
 * place is listed (up to [MAX_LISTED]) with its fingerprint and length.
 */
object HeapRedaction {
    /** Masked places listed in the answer; the rest are counted in the notes. */
    const val MAX_LISTED = 200

    fun apply(f: HeapFields): Pair<HeapFields, RedactionInfo> {
        val masked = mutableListOf<MaskedLocation>()
        var total = 0
        fun walk(place: String, v: JsonElement): JsonElement = when (v) {
            is JsonNull -> v
            is JsonObject -> JsonObject(v.mapValues { (k, x) -> walk("$place.$k", x) })
            is JsonArray -> JsonArray(v.mapIndexed { i, x -> walk("$place[$i]", x) })
            is JsonPrimitive -> {
                total++
                val shown = ArrayList<MaskedLocation>(1)
                val text = Redactor.hide(v.content, place, shown)
                if (masked.size < MAX_LISTED) masked += shown
                JsonPrimitive(text)
            }
        }
        val fields = f.fields.map { fld -> fld.value?.let { fld.copy(value = walk("instance ${f.instance} field ${fld.name}", it)) } ?: fld }
        val notes = if (total > masked.size) f.notes + "${total - masked.size} more masked values are not listed individually" else f.notes
        return f.copy(fields = fields, notes = notes) to RedactionInfo(masked = masked)
    }
}
