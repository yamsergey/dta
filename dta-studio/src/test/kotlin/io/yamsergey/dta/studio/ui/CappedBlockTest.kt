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
}
