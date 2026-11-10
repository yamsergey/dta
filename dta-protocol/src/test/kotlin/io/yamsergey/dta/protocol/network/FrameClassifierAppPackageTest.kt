package io.yamsergey.dta.protocol.network

import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.layout.ComposeNode
import io.yamsergey.dta.protocol.layout.Frame
import io.yamsergey.dta.protocol.layout.Node
import io.yamsergey.dta.protocol.layout.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Feature 054: one app/library rule for network frames and Compose calls. */
class FrameClassifierAppPackageTest {
    @Test
    fun `the app's own package is app code even under a library prefix`() {
        assertTrue(FrameClassifier.isApp("com.google.myapp.ui.HomeKt", appPackage = "com.google.myapp"))
        assertFalse(FrameClassifier.isApp("com.google.android.material.chip.Chip", appPackage = "com.google.myapp"))
        assertFalse(FrameClassifier.isApp("com.google.myappx.Foo", appPackage = "com.google.myapp"))
    }

    @Test
    fun `common UI libraries are library code`() {
        assertFalse(FrameClassifier.isApp("com.google.accompanist.pager.PagerKt"))
        assertFalse(FrameClassifier.isApp("coil.compose.AsyncImageKt"))
        assertFalse(FrameClassifier.isApp("coil3.compose.AsyncImageKt"))
        assertTrue(FrameClassifier.isApp("io.example.shop.ui.CartKt"))
    }

    @Test
    fun `packages are classified by the same rule`() {
        assertFalse(FrameClassifier.isAppPackage("androidx.compose.material3.internal"))
        assertTrue(FrameClassifier.isAppPackage("io.example.shop.ui"))
        assertTrue(FrameClassifier.isAppPackage("com.google.myapp", appPackage = "com.google.myapp"))
    }

    @Test
    fun `calls carry their package and an unknown mark on the wire`() {
        val node: Node = ComposeNode(bounds = Rect(0, 0, 1, 1), name = "Text", sourceFile = "Home.kt", lineNumber = 3, packageName = "io.example.ui",
            callChain = listOf(Frame("Header", "h.kt", 1, packageName = null, unknownOrigin = true)))
        val text = DtaJson.encodeToString(Node.serializer(), node)
        assertTrue("\"packageName\":\"io.example.ui\"" in text, text)
        assertTrue("\"unknownOrigin\":true" in text, text)
        assertEquals(node, DtaJson.decodeFromString(Node.serializer(), text))
    }
}
