package io.yamsergey.dta.sidekick.runtime

import io.yamsergey.dta.protocol.data.HeapField
import io.yamsergey.dta.protocol.data.HeapFields
import java.lang.reflect.Modifier

/** Feature 074 (B2): class names as the histogram shows them, back to classes. */
internal object HeapClassNames {
    private val primitives = mapOf(
        "int" to Int::class.javaPrimitiveType!!, "long" to Long::class.javaPrimitiveType!!, "short" to Short::class.javaPrimitiveType!!,
        "byte" to Byte::class.javaPrimitiveType!!, "char" to Char::class.javaPrimitiveType!!, "boolean" to Boolean::class.javaPrimitiveType!!,
        "float" to Float::class.javaPrimitiveType!!, "double" to Double::class.javaPrimitiveType!!,
    )

    /** `byte[]`, `java.lang.String[][]`, `com.x.Foo$Bar`; looked up in [loader], then the platform's; null when no such class is loaded. */
    fun resolve(name: String, loader: ClassLoader?): Class<*>? {
        var base = name.trim()
        var dims = 0
        while (base.endsWith("[]")) { base = base.removeSuffix("[]"); dims++ }
        var cls: Class<*> = primitives[base] ?: (load(base, loader) ?: load(base, null) ?: return null)
        repeat(dims) { cls = java.lang.reflect.Array.newInstance(cls, 0).javaClass }
        return cls
    }

    private fun load(name: String, loader: ClassLoader?): Class<*>? = try {
        Class.forName(name, false, loader)
    } catch (_: ClassNotFoundException) { null } catch (_: LinkageError) { null }
}

/** Feature 074 (B2): the ids of a class's instances, in id order, paged. */
internal object HeapInstancePaging {
    fun page(ids: LongArray, offset: Int, limit: Int): List<Long> = ids.sorted().drop(offset.coerceAtLeast(0)).take(limit.coerceAtLeast(0))
}

/** Feature 074 (B2): one instance's fields by reflection, declared and inherited, statics marked; values only when asked. */
internal object HeapFieldReader {
    fun read(obj: Any, instanceId: Long, values: Boolean): HeapFields {
        val notes = mutableListOf<String>()
        val out = mutableListOf<HeapField>()
        var unreadable = 0
        var cut = false
        generateSequence<Class<*>>(obj.javaClass) { it.superclass }.takeWhile { it != Any::class.java }.forEach { cls ->
            val declared = try { cls.declaredFields } catch (e: Throwable) { notes += "fields of ${cls.name} cannot be listed (${e.javaClass.simpleName})"; emptyArray() }
            for (f in declared) {
                if (f.isSynthetic && f.name.startsWith("$")) continue
                val static = Modifier.isStatic(f.modifiers)
                var value: Any? = null
                var readable = true
                try { f.isAccessible = true; value = f.get(if (static) null else obj) } catch (e: Throwable) { readable = false; unreadable++ }
                val length = if (readable) when (value) {
                    is CharSequence -> value.length
                    is Array<*> -> value.size
                    is IntArray -> value.size; is LongArray -> value.size; is ByteArray -> value.size; is ShortArray -> value.size
                    is CharArray -> value.size; is BooleanArray -> value.size; is FloatArray -> value.size; is DoubleArray -> value.size
                    else -> null
                } else null
                val json = if (values && readable) {
                    val (j, what) = Values.renderChecked(value, 3)
                    if (what != null) cut = true
                    j
                } else null
                out += HeapField(f.name, cls.name, HeapRows.className(f.type), static, json, length)
            }
        }
        if (unreadable > 0) notes += "$unreadable fields could not be read (runtime access restrictions); they show their name and type only"
        if (cut) notes += "long lists and maps show their first ${Values.ITEMS} items; objects nested deeper than 3 levels show their class"
        return HeapFields(instanceId, HeapRows.className(obj.javaClass), out, notes = notes)
    }
}
