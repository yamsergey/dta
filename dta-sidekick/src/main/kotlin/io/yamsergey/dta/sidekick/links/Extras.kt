package io.yamsergey.dta.sidekick.links

import io.yamsergey.dta.protocol.network.IntentExtra

/**
 * An intent's extras as text (feature 012, research R2): each value read on its own, so one
 * that cannot be read (its class is not loadable, its parcel is broken) is stated, not fatal.
 * [nested] opens a nested bundle as a map. The values come from a copy of the app's intent.
 */
object Extras {
    private const val MAX_TEXT = 500

    fun render(keys: Collection<String>, get: (String) -> Any?, nested: (Any) -> Map<String, Any?>? = { null }): List<IntentExtra> = keys.map { key ->
        try {
            val value = get(key)
            IntentExtra(key, value?.let { typeName(it) }, text(value, nested))
        } catch (t: Throwable) {
            IntentExtra(key, notRead = t.message?.take(MAX_TEXT) ?: t.javaClass.simpleName)
        }
    }

    private fun typeName(value: Any): String = when (value) {
        is String -> "String"
        is Int -> "Integer"
        is Long -> "Long"
        is Boolean -> "Boolean"
        else -> value.javaClass.name.substringAfterLast('.').replace('$', '.')
    }

    private fun text(value: Any?, nested: (Any) -> Map<String, Any?>?): String = when (value) {
        null -> "null"
        is String, is Number, is Boolean, is Char -> value.toString()
        is IntArray -> value.contentToString()
        is LongArray -> value.contentToString()
        is BooleanArray -> value.contentToString()
        is ByteArray -> "${value.size} bytes"
        is CharArray -> String(value)
        is FloatArray -> value.contentToString()
        is DoubleArray -> value.contentToString()
        is Array<*> -> value.contentToString()
        is Collection<*> -> value.toString()
        else -> nested(value)?.toString() ?: value.toString()
    }.take(MAX_TEXT)
}
