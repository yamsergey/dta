package io.yamsergey.dta.studio.toolwindow

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.yamsergey.dta.present.agents.AgentRow
import java.awt.Component
import java.awt.Container

/** Truncation rules, rule 3: the Agent column gets the room first; the name is whole when it can be. */
class AgentsTableTest : BasePlatformTestCase() {
    private val name = "claude-code-long-client-name-for-testing"  // 40 characters
    private fun layout(c: Component) { c.doLayout(); (c as? Container)?.components?.forEach(::layout) }

    private fun shown(width: Int, narrow: Boolean): String {
        val t = AgentsTable(listOf(AgentRow(name, "streamable HTTP (agent server)", "HTTP", "2.1.0-beta.3", "12:04")), narrow)
        t.component.setSize(width, 80)
        layout(t.component)
        val c = t.table.prepareRenderer(t.table.getCellRenderer(0, 0), 0, 0) as javax.swing.JLabel
        return c.text
    }

    fun testAt900AFortyCharacterNameIsWhole() {
        assertEquals(name, shown(800, narrow = false))
    }

    fun testAt420TheNameGetsTheRoomTheOthersLeaveAndEndsInAnEllipsis() {
        val s = shown(380, narrow = true)
        assertTrue(s, s.endsWith("…") && s.length > "claude-code-".length)
    }
}
