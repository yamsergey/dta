package io.yamsergey.dta.studio.layout

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBSplitter
import io.yamsergey.dta.present.layout.ScreenGeometry
import javax.swing.JPanel

/** D-123: the caption under the picture must never change the picture pane's width (so never re-fits the picture). */
class ScreenColumnTest : BasePlatformTestCase() {
    private fun layoutWith(caption: String): Pair<Int, ScreenGeometry> {
        val picture = JPanel()
        val column = ScreenColumn(picture) {}
        column.setCaption(caption)
        val inner = JBSplitter(false, 0.5f).apply { firstComponent = column; secondComponent = JPanel() }
        inner.setSize(476, 683)
        inner.doLayout()
        column.doLayout()
        return column.width to ScreenGeometry(1080, 2400, picture.width, picture.height)
    }

    fun `test the pane and the picture fit do not depend on the caption`() {
        val rest = layoutWith(" ")
        for (text in listOf("FrameLayout", "TextView  \"Channel Fixture\"", "not the app: system Taskbar", "x".repeat(300))) {
            val now = layoutWith(text)
            assertEquals("pane width with caption '$text'", rest.first, now.first)
            assertEquals("picture fit with caption '$text'", rest.second, now.second)
        }
    }
}

/** D-144: at a narrow column the caption keeps a hovered element's class name whole; the link gives way. */
class ScreenColumnCaptionTest : BasePlatformTestCase() {
    fun `test the caption has room for a class name at the narrow width`() {
        val column = ScreenColumn(JPanel()) {}
        column.setCaption("LinearLayout")
        column.setSize(238, 400)
        fun lay(c: java.awt.Container) { c.doLayout(); c.components.filterIsInstance<java.awt.Container>().forEach { lay(it) } }
        lay(column)
        val metrics = column.captionLabel.getFontMetrics(column.captionLabel.font)
        val need = metrics.stringWidth("LinearLayout") + column.captionLabel.insets.left + column.captionLabel.insets.right
        assertTrue("caption ${column.captionLabel.width} px, needs $need", column.captionLabel.width >= need)
    }
}
