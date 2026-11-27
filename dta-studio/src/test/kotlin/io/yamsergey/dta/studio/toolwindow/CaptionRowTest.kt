package io.yamsergey.dta.studio.toolwindow

import java.awt.Dimension
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** D-106: the footer caption is never hard-cut: it fits its row or ends in an ellipsis, with the whole text in its tooltip. */
class CaptionRowTest {
    private val long = "37 shown · 48 210 in this run · earlier runs are in the archive (source: All) · 1 saved filter on · more as you scroll"
    private val short = "37 shown · 48 210 in this run"

    private fun row(text: String, width: Int): Triple<CaptionRow, JLabel, JLabel> {
        val caption = JLabel(); val link = JLabel("Stop")
        val row = CaptionRow(JPanel().apply { preferredSize = Dimension(16, 16); isVisible = false }, caption, link)
        row.setCaption(text)
        row.setSize(width, row.preferredSize.height); row.doLayout()
        return Triple(row, caption, link)
    }

    @Test
    fun `at the widths of the list, nothing is laid out past the row`() {
        for (width in listOf(394, 300, 220)) {
            val (row, caption, link) = row(long, width)
            assertTrue(caption.x + caption.width <= row.width, "caption ends at ${caption.x + caption.width} in $width px")
            assertTrue(link.x + link.width <= row.width || !link.isVisible, "link ends at ${link.x + link.width} in $width px")
        }
    }

    @Test
    fun `a caption wider than its room keeps the whole text in the tooltip, a short one needs none`() {
        val (_, cut, _) = row(long, 300)
        assertEquals(long, cut.toolTipText)
        assertTrue(cut.width < cut.preferredSize.width, "it is the label's ellipsis that shortens it")
        val (_, whole, _) = row(short, 600)
        assertTrue(whole.width >= whole.preferredSize.width)
    }
}
