package io.yamsergey.dta.studio.network

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.ActionLink

/** D-153: a long URL title never pushes Copy out of the pane. */
class EntryTitleRowTest : BasePlatformTestCase() {
    private val url = "POST http://127.0.0.1:8099/echo?access_token=SEED-QUERY-123456789&refresh=another-long-value&page=12/items/end"

    fun `test the title is cut in the middle and Copy stays inside the pane`() {
        val copy = ActionLink("Copy ▾") {}
        val row = EntryTitleRow(url, copy)
        row.setSize(380, 30)
        row.doLayout()
        assertTrue("Copy ${copy.x}+${copy.width} in 380", copy.x >= 0 && copy.x + copy.width <= 380)
        assertTrue("title ${row.title.x}+${row.title.width} before Copy ${copy.x}", row.title.x + row.title.width <= copy.x)
        val metrics = row.title.getFontMetrics(row.title.font)
        assertTrue("title ${row.title.text} is ${metrics.stringWidth(row.title.text)} px in ${row.title.width}", metrics.stringWidth(row.title.text) <= row.title.width)
        assertTrue(row.title.text, row.title.text.startsWith("POST http://127") && "…" in row.title.text && row.title.text.endsWith("end"))
        assertEquals(url, row.title.toolTipText)
    }
}
