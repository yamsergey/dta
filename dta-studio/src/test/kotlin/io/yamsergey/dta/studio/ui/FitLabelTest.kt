package io.yamsergey.dta.studio.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** D-144: a text that may be cut has its whole in the tooltip; a class name keeps its simple name whole. */
class FitLabelTest : BasePlatformTestCase() {
    fun `test the whole text is the tooltip`() {
        val l = FitLabel()
        l.show("1080×2400 px · MainActivity · sidekick · 17:31:35 · 49 ms")
        assertEquals("1080×2400 px · MainActivity · sidekick · 17:31:35 · 49 ms", l.toolTipText)
    }

    fun `test it asks for no width`() {
        val l = FitLabel()
        l.show("x".repeat(300))
        assertEquals(0, l.minimumSize.width)
    }

    fun `test a class name keeps its simple name whole when short of room`() {
        val l = FitLabel(asClass = true)
        l.show("androidx.compose.ui.platform.ComposeView")
        l.setBounds(0, 0, 110, 20)
        assertTrue(l.text, l.text.endsWith("ComposeView"))
        assertTrue(l.text, l.getFontMetrics(l.font).stringWidth(l.text) <= 110)
        assertEquals("androidx.compose.ui.platform.ComposeView", l.toolTipText)
    }
}
