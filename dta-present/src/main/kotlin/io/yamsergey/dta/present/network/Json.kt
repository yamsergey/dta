package io.yamsergey.dta.present.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

internal fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
internal fun JsonObject.arr(name: String): JsonArray = this[name] as? JsonArray ?: JsonArray(emptyList())
internal fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
internal fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.bool(name: String): Boolean = (this[name] as? JsonPrimitive)?.booleanOrNull ?: false
internal val JsonElement.o: JsonObject get() = this as? JsonObject ?: JsonObject(emptyMap())
internal val JsonElement.text: String get() = (this as? JsonPrimitive)?.content ?: toString()
