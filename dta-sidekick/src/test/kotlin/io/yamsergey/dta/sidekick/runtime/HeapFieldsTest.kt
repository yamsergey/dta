package io.yamsergey.dta.sidekick.runtime

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 074 (B2): fields by reflection, values only when asked; class names from the histogram back to classes. */
class HeapFieldsTest {
    open class Base { val baseName: String = "base-value"; companion object { @JvmField val SHARED = "shared-value" } }
    class Sample : Base() {
        val label: String = "hello"
        val count: Int = 3
        val flag: Boolean = true
        val items: List<String> = listOf("a", "b")
        val bytes: ByteArray = ByteArray(5)
        val nothing: String? = null
    }

    private fun read(values: Boolean) = HeapFieldReader.read(Sample(), instanceId = 11, values = values)

    @Test fun listsDeclaredAndInheritedFieldsWithDeclaringClass() {
        val f = read(values = false)
        assertEquals(11L, f.instance)
        assertEquals(Sample::class.java.name, f.className)
        val byName = f.fields.associateBy { it.name }
        assertEquals(Base::class.java.name, byName.getValue("baseName").declaredIn)
        assertEquals(Sample::class.java.name, byName.getValue("label").declaredIn)
        assertEquals("int", byName.getValue("count").type)
        assertTrue(byName.getValue("SHARED").static)
        assertFalse(byName.getValue("label").static)
    }

    @Test fun withoutValuesOnlyLengthsOfStringsAndArrays() {
        val byName = read(values = false).fields.associateBy { it.name }
        assertNull(byName.getValue("label").value)
        assertEquals(5, byName.getValue("label").length)
        assertEquals(5, byName.getValue("bytes").length)
        assertNull(byName.getValue("count").value)
        assertNull(byName.getValue("count").length)
        assertNull(byName.getValue("nothing").length)
        assertNull(byName.getValue("SHARED").value)
    }

    @Test fun withValuesAsJson() {
        val byName = read(values = true).fields.associateBy { it.name }
        assertEquals(JsonPrimitive("hello"), byName.getValue("label").value)
        assertEquals(JsonPrimitive(3), byName.getValue("count").value)
        assertEquals(JsonPrimitive(true), byName.getValue("flag").value)
        assertEquals("""["a","b"]""", byName.getValue("items").value.toString())
        assertEquals(JsonPrimitive("shared-value"), byName.getValue("SHARED").value)
    }

    @Test fun arrayClassNamesResolve() {
        assertEquals(IntArray::class.java, HeapClassNames.resolve("int[]", null))
        assertEquals(Array<Array<String>>::class.java, HeapClassNames.resolve("java.lang.String[][]", null))
        assertEquals(String::class.java, HeapClassNames.resolve("java.lang.String", null))
        assertEquals(Map.Entry::class.java, HeapClassNames.resolve("java.util.Map\$Entry", null))
        assertNull(HeapClassNames.resolve("no.such.Clazz", null))
    }

    @Test fun pagesByIdOrder() {
        val tags = longArrayOf(30, 10, 20, 40)
        assertEquals(listOf(10L, 20L), HeapInstancePaging.page(tags, offset = 0, limit = 2))
        assertEquals(listOf(30L, 40L), HeapInstancePaging.page(tags, offset = 2, limit = 5))
        assertEquals(emptyList(), HeapInstancePaging.page(tags, offset = 9, limit = 5))
    }
}
