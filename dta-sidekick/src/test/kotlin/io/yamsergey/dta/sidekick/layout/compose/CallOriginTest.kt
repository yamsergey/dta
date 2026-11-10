package io.yamsergey.dta.sidekick.layout.compose

import io.yamsergey.dta.sidekick.layout.compose.CallOrigin.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 054: app, library or unknown, by the package the hash names (one rule with network). */
class CallOriginTest {
    private fun h(p: String) = ComposableClassifier.packageHash(p)
    private val index = PackageIndex(setOf(
        "androidx.compose.material3", "androidx.compose.material3.internal", "io.example.shop.ui", "com.google.shop.home",
    ))
    private val classify = CallClassifier(index, appPackage = "com.google.shop")

    @Test
    fun `internal library packages are library, the app's are app with their package`() {
        assertEquals(CallOrigin(Kind.LIBRARY, "androidx.compose.material3.internal"), classify.of("ProvideContentColorTextStyle.kt", h("androidx.compose.material3.internal")))
        assertEquals(CallOrigin(Kind.APP, "io.example.shop.ui"), classify.of("Cart.kt", h("io.example.shop.ui")))
        assertEquals(CallOrigin(Kind.APP, "com.google.shop.home"), classify.of("Home.kt", h("com.google.shop.home")), "the app's own package first")
    }

    @Test
    fun `a package not shipped is unknown, and counted`() {
        val c = CallClassifier(index, null)
        assertEquals(CallOrigin(Kind.UNKNOWN), c.of("a.kt", h("a.b")))
        c.of("b.kt", h("a.c"))
        assertEquals(2, c.unknownCalls)
    }

    @Test
    fun `a hash naming an app and a library package is unknown`() {
        val clash = object : PackageIndexLookup { override fun packagesOf(hash: Int) = listOf("androidx.x", "io.example.y") }
        assertEquals(Kind.UNKNOWN, CallClassifier(clash, null).of("x.kt", 1).kind)
        val two = object : PackageIndexLookup { override fun packagesOf(hash: Int) = listOf("io.a", "io.b") }
        assertEquals(CallOrigin(Kind.APP), CallClassifier(two, null).of("x.kt", 1), "app either way; no package named")
    }

    @Test
    fun `without source or package information, or without an index, today's rules apply`() {
        assertEquals(Kind.LIBRARY, classify.of(null, -1).kind)
        assertEquals(Kind.LIBRARY, classify.of("Text.kt", -1).kind)
        assertEquals(Kind.APP, classify.of("Cart.kt", -1).kind)
        val none = CallClassifier(null, null)
        assertEquals(Kind.APP, none.of("ProvideContentColorTextStyle.kt", h("androidx.compose.material3.internal")).kind, "the fixed list misses internal packages")
        assertEquals(Kind.LIBRARY, none.of("Text.kt", h("androidx.compose.material3")).kind)
        assertNull(none.of("Text.kt", h("androidx.compose.material3")).packageName)
        assertEquals(0, none.unknownCalls)
    }

    @Test
    fun `the nearest app call labels a node, else the nearest unknown, else a library one`() {
        fun call(name: String, file: String, line: Int, origin: CallOrigin, vararg children: ToolingGroup) =
            ToolingGroup(name, file, line, emptyList(), null, children.toList(), origin = origin)
        fun node(id: Long) = ToolingGroup(null, null, null, emptyList(), id, emptyList())
        val lib = CallOrigin(Kind.LIBRARY, "androidx.compose.material3.internal")
        val roots = listOf(
            call("Screen", "Home.kt", 10, CallOrigin(Kind.APP, "io.example.ui"),
                call("TopAppBar", "Home.kt", 12, CallOrigin(Kind.APP, "io.example.ui"),
                    call("CompositionLocalProvider", "ProvideContentColorTextStyle.kt", 41, lib, node(1)))),
            call("Header", "h.kt", 3, CallOrigin(Kind.UNKNOWN), call("Text", "Text.kt", 70, lib, node(2))),
            call("Text", "Text.kt", 70, lib, node(3)),
        )
        val labels = ToolingTree.label(roots)
        assertEquals("TopAppBar" to 12, labels[1]!!.name to labels[1]!!.lineNumber)
        assertEquals("io.example.ui", labels[1]!!.packageName)
        assertEquals(false, labels[1]!!.library)
        assertEquals("Header", labels[2]!!.name)
        assertTrue(labels[2]!!.unknown)
        assertEquals(true, labels[3]!!.library)
        val chain = ToolingTree.chains(roots, emptyList(), frameworkFrames = false).chains
        assertEquals(listOf("Screen", "TopAppBar"), chain[1]!!.map { it.name })
        assertEquals(listOf("Header"), chain[2]!!.map { it.name })
        assertEquals(true, chain[2]!!.single().unknownOrigin)
        val all = ToolingTree.chains(roots, emptyList(), frameworkFrames = true).chains
        assertEquals("androidx.compose.material3.internal", all[1]!!.last().packageName)
        assertEquals(true, all[1]!!.last().library)
    }
}
