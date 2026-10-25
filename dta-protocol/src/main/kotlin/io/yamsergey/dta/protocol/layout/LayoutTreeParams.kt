package io.yamsergey.dta.protocol.layout

import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.InvalidParamsException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * `layout_tree` request params (002 contract §1, 003 contract §1). Shared so
 * the daemon encodes and the sidekick decodes the same shape (constitution VI).
 */
@Serializable
data class LayoutTreeParams(
    val filters: LayoutFilter? = null,
    val detail: DetailLevel = DetailLevel.SEMANTIC,
    /** composition level only; ignored at other levels. */
    val frameworkFrames: Boolean = false,
) {
    /** The effective filter: an empty filter object means no filter. */
    val filter: LayoutFilter? get() = filters?.takeUnless { it.isEmpty }

    fun toJson(): JsonObject = DtaJson.encodeToJsonElement(serializer(), this).jsonObject

    companion object {
        private val LEVELS = DetailLevel.entries.joinToString(", ") { it.wireName() }

        /** Null params are the defaults. Malformed params throw
         * [InvalidParamsException] (→ INVALID_PARAMS). */
        fun parse(params: JsonObject?): LayoutTreeParams {
            if (params == null) return LayoutTreeParams()
            val detail = params["detail"]
            if (detail is JsonPrimitive && detail.isString && DetailLevel.entries.none { it.wireName() == detail.content }) {
                throw InvalidParamsException("unknown detail level '${detail.content}'; valid values: $LEVELS")
            }
            return try {
                DtaJson.decodeFromJsonElement(serializer(), params)
            } catch (e: SerializationException) {
                throw InvalidParamsException("malformed layout_tree params: ${e.message?.lineSequence()?.first()}")
            } catch (e: IllegalArgumentException) {
                throw InvalidParamsException("malformed layout_tree params: ${e.message?.lineSequence()?.first()}")
            }
        }

        private fun DetailLevel.wireName(): String = DtaJson.encodeToJsonElement(DetailLevel.serializer(), this).let {
            (it as JsonPrimitive).content
        }
    }
}
