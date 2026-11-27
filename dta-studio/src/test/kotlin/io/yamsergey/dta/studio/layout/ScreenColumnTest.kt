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
