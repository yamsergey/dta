package io.yamsergey.dta.sidekick.runtime

import io.yamsergey.dta.protocol.data.HeapOrigin
import io.yamsergey.dta.protocol.data.LoaderKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** Feature 074 (B1): row assembly from the native walk's per-class sums. */
class HeapRowsTest {
    private val ns = listOf("com.example")

    private fun raw(name: String, loader: LoaderKind, label: String?, n: Long, b: Long) = HeapRows.Raw(name, loader, label, n, b)

    @Test fun classNamesUseSourceFormForArrays() {
        assertEquals("int[]", HeapRows.className(IntArray::class.java))
        assertEquals("java.lang.String[][]", HeapRows.className(Array<Array<String>>::class.java))
        assertEquals("java.util.Map\$Entry", HeapRows.className(Map.Entry::class.java))
    }

    @Test fun dropsClassesWithoutInstancesAndSortsByBytes() {
        val rows = HeapRows.assemble(
            listOf(raw("A", LoaderKind.APP, "l", 0, 0), raw("com.example.B", LoaderKind.APP, "l", 2, 20), raw("java.lang.String", LoaderKind.BOOT, null, 5, 100)),
            ns,
        )
        assertEquals(listOf("java.lang.String", "com.example.B"), rows.map { it.className })
        assertEquals(HeapOrigin.PLATFORM, rows[0].origin)
        assertEquals(HeapOrigin.APP, rows[1].origin)
    }

    @Test fun loaderIsNamedOnlyWhenNamesCollide() {
        val rows = HeapRows.assemble(
            listOf(raw("x.Dup", LoaderKind.OTHER, "L1", 1, 10), raw("x.Dup", LoaderKind.OTHER, "L2", 1, 5), raw("x.Solo", LoaderKind.OTHER, "L1", 1, 1)),
            ns,
        )
        assertEquals(listOf("L1", "L2", null), rows.map { it.loader })
        assertEquals(HeapOrigin.UNKNOWN, rows[0].origin)
    }

    @Test fun emptyNamespacesGiveUnknownNeverGuessed() {
        val rows = HeapRows.assemble(listOf(raw("com.example.B", LoaderKind.APP, "l", 1, 1)), emptyList())
        assertEquals(HeapOrigin.UNKNOWN, rows.single().origin)
    }

    @Test fun namespacesFromPackageNamesAreDeduplicated() {
        assertEquals(
            listOf("com.example", "org.other"),
            HeapRows.namespaces("com.example", listOf("com.example.ui.Main", "org.other.Foo", "com.example.App", null, "NoPackage")),
        )
    }
}
