package io.yamsergey.dta.sidekick.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

/**
 * Feature 028 (research R2, R3): the app's values as JSON and back. Rendering is bounded (depth,
 * 50 items) and cuts cycles; conversion follows the current value's type; a field inside a data
 * class is changed by rebuilding each level with its `copy`.
 */
internal object Values {
    private const val ITEMS = 50

    fun render(value: Any?, maxDepth: Int = 4): JsonElement = render(value, 0, maxDepth, IdentityHashMap())

    private fun render(v: Any?, depth: Int, max: Int, seen: IdentityHashMap<Any, Boolean>): JsonElement = when {
        v == null -> JsonNull
        v is String -> JsonPrimitive(v)
        v is Boolean -> JsonPrimitive(v)
        v is Number -> JsonPrimitive(v)
        v is Char -> JsonPrimitive(v.toString())
        v is Enum<*> -> JsonPrimitive(v.name)
        v is CharSequence -> JsonPrimitive(v.toString())
        depth >= max -> JsonPrimitive("${v.javaClass.simpleName}…")
        seen.containsKey(v) -> JsonPrimitive("(cycle: ${v.javaClass.simpleName})")
        else -> {
            seen[v] = true
            try {
                when (v) {
                    is Map<*, *> -> JsonObject(v.entries.take(ITEMS).associate { (k, x) -> k.toString() to render(x, depth + 1, max, seen) })
                    is Iterable<*> -> JsonArray(v.take(ITEMS).map { render(it, depth + 1, max, seen) })
                    is Array<*> -> JsonArray(v.take(ITEMS).map { render(it, depth + 1, max, seen) })
                    is IntArray -> JsonArray(v.take(ITEMS).map { JsonPrimitive(it) })
                    is LongArray -> JsonArray(v.take(ITEMS).map { JsonPrimitive(it) })
                    is ByteArray -> JsonPrimitive("${v.size} bytes")
                    is android.os.Bundle -> JsonObject(v.keySet().associateWith { k -> @Suppress("DEPRECATION") render(v.get(k), depth + 1, max, seen) })
                    else -> holder(v)?.let { render(it.second, depth, max, seen) } ?: fields(v, depth, max, seen)
                }
            } finally {
                seen.remove(v)
            }
        }
    }

    /** A StateFlow / LiveData / Compose State: its value (kind, value). */
    fun holder(v: Any): Pair<String, Any?>? {
        fun implements(name: String) = isA(v.javaClass, name)
        return when {
            implements("kotlinx.coroutines.flow.StateFlow") -> "stateflow" to runCatching { v.javaClass.getMethod("getValue").invoke(v) }.getOrNull()
            implements("androidx.lifecycle.LiveData") -> "livedata" to runCatching { v.javaClass.getMethod("getValue").invoke(v) }.getOrNull()
            implements("androidx.compose.runtime.State") -> "state" to runCatching { v.javaClass.getMethod("getValue").invoke(v) }.getOrNull()
            else -> null
        }
    }

    /**
     * Feature 033: `asStateFlow()` / `asSharedFlow()` wrap the mutable flow in a read-only one; the
     * ViewModel's public `state` then is the same state as its `_state`. The wrapped flow, else [v].
     */
    fun unwrapReadonly(v: Any?): Any? {
        if (v == null) return null
        val name = v.javaClass.name
        if (name != "kotlinx.coroutines.flow.ReadonlyStateFlow" && name != "kotlinx.coroutines.flow.ReadonlySharedFlow") return v
        // The wrapped flow is the class delegate (`$$delegate_0`), which instanceFields leaves out.
        return v.javaClass.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.firstNotNullOfOrNull { f ->
            runCatching { f.isAccessible = true; f.get(v) }.getOrNull()?.takeIf { isA(it.javaClass, "kotlinx.coroutines.flow.SharedFlow") }
        } ?: v
    }

    /**
     * Feature 033: an object the ViewModel uses rather than state it holds — a repository, a client,
     * a context: not a holder, not a value (text, number, enum, data class, collection, array) and
     * not a plain Java/Kotlin value type. Shown by its class, not serialized.
     */
    fun isReference(v: Any?): Boolean {
        if (v == null || v is CharSequence || v is Number || v is Boolean || v is Char || v is Enum<*>) return false
        if (v is Collection<*> || v is Map<*, *> || v.javaClass.isArray) return false
        if (holder(v) != null || copyMethod(v.javaClass) != null) return false
        val name = v.javaClass.name
        return !(name.startsWith("java.") || name.startsWith("kotlin."))
    }

    fun isA(type: Class<*>, name: String): Boolean {
        var c: Class<*>? = type
        while (c != null) {
            if (c.name == name || c.interfaces.any { isA(it, name) }) return true
            c = c.superclass
        }
        return false
    }

    private fun fields(v: Any, depth: Int, max: Int, seen: IdentityHashMap<Any, Boolean>): JsonElement {
        val fs = instanceFields(v.javaClass)
        if (fs.isEmpty() || v.javaClass.name.startsWith("java.") || v.javaClass.name.startsWith("android.")) {
            return JsonPrimitive(runCatching { v.toString() }.getOrDefault(v.javaClass.name).take(200))
        }
        return JsonObject(fs.take(ITEMS).associate { f -> f.name to render(runCatching { f.get(v) }.getOrNull(), depth + 1, max, seen) })
    }

    /** Instance fields in declaration order, the class's own last-declared hierarchy first. */
    fun instanceFields(type: Class<*>): List<Field> =
        generateSequence(type) { it.superclass }.takeWhile { it != Any::class.java && !it.name.startsWith("java.") }
            .toList().reversed()
            .flatMap { c -> c.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic && !it.name.contains('$') } }
            .onEach { runCatching { it.isAccessible = true } }

    /** [json] as a value of [current]'s type (or [declared] when current is null). */
    fun convert(json: JsonElement, current: Any?, declared: Class<*>): Any? {
        if (json is JsonNull) return null
        val type = current?.javaClass ?: declared
        val p = json as? JsonPrimitive
        fun fail(): Nothing = throw IllegalArgumentException("expected ${describe(type, current)}; got $json")
        return when {
            type == String::class.java -> p?.takeIf { it.isString }?.content ?: fail()
            type == Int::class.java || type == Int::class.javaObjectType -> p?.longOrNull?.toInt() ?: fail()
            type == Long::class.java || type == Long::class.javaObjectType -> p?.longOrNull ?: fail()
            type == Double::class.java || type == Double::class.javaObjectType -> p?.doubleOrNull ?: fail()
            type == Float::class.java || type == Float::class.javaObjectType -> p?.doubleOrNull?.toFloat() ?: fail()
            type == Boolean::class.java || type == Boolean::class.javaObjectType -> p?.booleanOrNull ?: fail()
            type.isEnum -> p?.content?.let { name -> type.enumConstants.firstOrNull { (it as Enum<*>).name == name } } ?: fail()
            List::class.java.isAssignableFrom(type) || Set::class.java.isAssignableFrom(type) -> {
                val arr = json as? JsonArray ?: fail()
                val sample = (current as? Collection<*>)?.firstOrNull()
                val items = arr.map { convert(it, sample, sample?.javaClass ?: String::class.java) }
                if (Set::class.java.isAssignableFrom(type)) items.toSet() else items
            }
            json is JsonObject && copyMethod(type) != null -> {
                var result: Any = current ?: fail()
                for ((k, v) in json) result = with(result, listOf(k), v)
                result
            }
            else -> throw IllegalArgumentException("${type.simpleName} values are not set from JSON by DTA; set a field inside it (path a.b)")
        }
    }

    private fun describe(type: Class<*>, current: Any?): String = when {
        type.isEnum -> "one of ${type.enumConstants.joinToString { (it as Enum<*>).name }}"
        else -> "a${if (type.simpleName.first().lowercaseChar() in "aeiou") "n" else ""} ${type.kotlinName()}"
    }

    private fun Class<*>.kotlinName() = when (this) {
        Int::class.javaObjectType, Int::class.java -> "Int"
        Long::class.javaObjectType, Long::class.java -> "Long"
        Boolean::class.javaObjectType, Boolean::class.java -> "Boolean"
        Double::class.javaObjectType, Double::class.java -> "Double"
        Float::class.javaObjectType, Float::class.java -> "Float"
        else -> simpleName
    }

    /** A Kotlin data class's `copy` (all properties), or null. */
    fun copyMethod(type: Class<*>) = type.methods.firstOrNull { m ->
        m.name == "copy" && !Modifier.isStatic(m.modifiers) && m.parameterTypes.size == instanceFields(type).size && m.returnType == type
    }

    /**
     * A data class's fields in constructor (= `copy`) order. The runtime may order declared
     * fields differently (ART does), so the order comes from the data class's generated
     * `toString` (`Name(a=…, b=…)`), else by matching `componentN()` values to fields.
     */
    fun constructorFields(value: Any): List<Field> {
        val fields = instanceFields(value.javaClass)
        val byName = fields.associateBy { it.name }
        val names = topLevelNames(runCatching { value.toString() }.getOrDefault(""), value.javaClass.simpleName)
        if (names.size == fields.size && names.all { it in byName }) return names.map { byName.getValue(it) }
        val left = fields.toMutableList()
        return (1..fields.size).map { i ->
            val component = runCatching { value.javaClass.getMethod("component$i").invoke(value) }.getOrNull()
            val match = left.firstOrNull { f -> runCatching { f.get(value) }.getOrNull().let { it === component || (it != null && it == component && left.count { o -> o.get(value) == component } == 1) } }
                ?: left.first()
            left.remove(match)
            match
        }
    }

    private fun topLevelNames(text: String, simpleName: String): List<String> {
        if (!text.startsWith("$simpleName(") || !text.endsWith(")")) return emptyList()
        val body = text.substring(simpleName.length + 1, text.length - 1)
        val names = mutableListOf<String>()
        var depth = 0
        var start = 0
        for ((i, ch) in body.withIndex()) {
            when (ch) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) start = i + 1
                '=' -> if (depth == 0 && body.substring(start, i).trim().let { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '_' } }) {
                    names += body.substring(start, i).trim(); start = body.length
                }
            }
        }
        return names
    }

    /** [value] with the field at [path] set to [json], each data-class level rebuilt with `copy`. */
    fun with(value: Any, path: List<String>, json: JsonElement): Any {
        val type = value.javaClass
        val copy = copyMethod(type) ?: throw IllegalArgumentException("${type.simpleName} is not a data class: DTA cannot rebuild it to change ${path.first()}")
        val fields = constructorFields(value)
        val index = fields.indexOfFirst { it.name == path.first() }
        require(index >= 0) { "${type.simpleName} has no ${path.first()} (it has ${fields.joinToString { it.name }})" }
        val args = fields.map { it.get(value) }.toTypedArray()
        val current = args[index]
        args[index] = if (path.size == 1) convert(json, current, copy.parameterTypes[index])
        else with(current ?: throw IllegalArgumentException("${path.first()} is null: set it whole"), path.drop(1), json)
        return copy.invoke(value, *args)!!
    }
}
