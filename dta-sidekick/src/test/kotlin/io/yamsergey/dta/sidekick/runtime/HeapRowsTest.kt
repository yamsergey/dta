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
        assertEquals(HeapOrigin.LIBRARY, rows[0].origin)
    }

    @Test fun sameAppClassInTwoLoadersIsAppInBoth() {
        val rows = HeapRows.assemble(
            listOf(raw("com.example.HeldC", LoaderKind.APP, "L1", 7, 70), raw("com.example.HeldC", LoaderKind.OTHER, "L2", 3, 30)),
            ns,
        )
        assertEquals(listOf(HeapOrigin.APP, HeapOrigin.APP), rows.map { it.origin })
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

    @Test fun artHandsOutABootClassLoaderObjectForPlatformClasses() {
        assertEquals(LoaderKind.BOOT, HeapRows.loaderKind(null, isAppLoader = false))
        assertEquals(LoaderKind.BOOT, HeapRows.loaderKind("java.lang.BootClassLoader", isAppLoader = false))
        assertEquals(LoaderKind.APP, HeapRows.loaderKind("dalvik.system.PathClassLoader", isAppLoader = true))
        assertEquals(LoaderKind.OTHER, HeapRows.loaderKind("dalvik.system.DexClassLoader", isAppLoader = false))
    }
}
