package io.yamsergey.dta.studio.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JPanel

/** D-138: text blocks around a table are capped to a share of the height. */
class CappedBlockTest : BasePlatformTestCase() {
    private fun tall(h: Int) = JPanel().apply { preferredSize = Dimension(100, h) }

    private fun heightIn(parentHeight: Int, body: JPanel): Int {
        val block = CappedBlock(listOf(body), 0.3)
        val parent = JPanel(BorderLayout()).apply { add(block, BorderLayout.NORTH); setSize(420, parentHeight) }
        parent.doLayout()
        return block.preferredSize.height
    }

    fun testALongBlockIsCappedToItsShare() {
        val h = heightIn(600, tall(900))
        assertTrue("height $h of 600", h <= 180 + 2)
        assertTrue("height $h", h >= 100)
    }

    fun testAShortBlockKeepsItsOwnHeight() {
        val h = heightIn(600, tall(30))
        assertTrue("height $h", h in 30..40)
    }

    fun testASmallPanelStillGivesTheBlockAReadableLine() {
        assertTrue(heightIn(60, tall(900)) >= 24)
    }

    /** V1: a capped block ends on a whole line and scrolls by whole lines, so no line is half-cut. */
    fun testTheCapIsAWholeNumberOfLinesAndScrollsByLines() {
        val block = CappedBlock(listOf(tall(900)), 0.3)
        val parent = JPanel(BorderLayout()).apply { add(block, BorderLayout.NORTH); setSize(420, 590) }
        parent.doLayout()
        val line = block.getFontMetrics(block.font).height
        val h = block.preferredSize.height
        assertEquals("height $h, line $line", 0, h % line)
        assertEquals(line, block.verticalScrollBar.unitIncrement)
    }

    /** D-148: with real text (a WrappingText has a 1 px border and its own font) the block starts at a line's top and ends on a line. */
    fun testTextBlocksStartAndEndOnTheTextsLineBoundary() {
        val text = WrappingText((1..12).joinToString("\n") { "line $it of the statement" }, java.awt.Color.GRAY)
        val note = WrappingText((1..9).joinToString("\n") { "left out $it" }, java.awt.Color.GRAY)
        val block = CappedBlock(listOf(text, note), 0.25)
        val parent = JPanel(BorderLayout()).apply { add(block, BorderLayout.NORTH); setSize(404, 583) }
        parent.doLayout(); block.doLayout(); (block.viewport.view as java.awt.Container).doLayout()
        val line = text.getFontMetrics(text.font).height
        val h = block.preferredSize.height
        assertEquals("block height $h, text line $line", 0, h % line)
        assertEquals(line, block.verticalScrollBar.unitIncrement)
        assertEquals("the first line starts at the top of the content", 0, text.y)
        assertEquals("the second text starts on a line boundary", 0, note.y % line)
        assertEquals("content is whole lines", 0, block.viewport.view.preferredSize.height % line)
    }

    /**
     * D-148, the IDE's numbers: the LaF puts a 1 px border back (insets 1/1/1/1), so a cap of 2 lines gave a 32 px viewport; the text area's
     * caret scrolled the 3-line text to its end (position 19 = 51 - 32); the left-out block took 25% of 482 px for 204 px of text.
     */
    fun testTheRealIdeBlockViewportIsWholeLinesAtTheTopWithALineCap() {
        val text = WrappingText((1..3).joinToString("\n") { "statement line $it" }, java.awt.Color.GRAY)
        val block = CappedBlock(listOf(text), 0.25, maxLines = 2)
        block.border = com.intellij.util.ui.JBUI.Borders.empty(1) // what the LaF installs (DarculaScrollPaneBorder: insets 1)
        val parent = JPanel(BorderLayout()).apply { add(block, BorderLayout.NORTH); setSize(358, 148) }
        parent.doLayout(); block.setSize(block.preferredSize); block.doLayout(); (block.viewport.view as java.awt.Container).doLayout()
        val line = text.getFontMetrics(text.font).height
        assertEquals("viewport ${block.viewport.extentSize.height} px is two whole lines of $line", 2 * line, block.viewport.extentSize.height)
        // The text was set after the layout (as the panel does): nothing scrolls it to its end.
        text.text = (1..6).joinToString("\n") { "new statement line $it" }
        com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
        assertEquals("position ${block.viewport.viewPosition.y}", 0, block.viewport.viewPosition.y % line)
        assertEquals("a new text opens at its top", 0, block.viewport.viewPosition.y)
    }

    fun testALineCapHoldsWhateverTheParentHeightIs() {
        val text = WrappingText((1..12).joinToString("\n") { "left out $it" }, java.awt.Color.GRAY)
        val block = CappedBlock(listOf(text), 0.25, maxLines = 3)
        block.border = com.intellij.util.ui.JBUI.Borders.empty(1)
        val parent = JPanel(BorderLayout()).apply { add(block, BorderLayout.SOUTH); setSize(358, 482) }
        parent.doLayout()
        val line = text.getFontMetrics(text.font).height
        assertEquals(3 * line + 2, block.preferredSize.height)
    }
}
