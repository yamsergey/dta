package io.yamsergey.dta.studio.toolwindow

import com.intellij.ui.JBColor
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.present.agents.AgentRow
import io.yamsergey.dta.studio.ui.ClassNameFit
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

/**
 * The Agents tab's list of connected agents (073). Truncation rules: the name is the identifying part, so the Agent
 * column gets the room first; the other columns take only their content's width. A name that still does not fit ends
 * in an ellipsis with the whole name in the tooltip.
 */
class AgentsTable(rows: List<AgentRow>, narrow: Boolean) {
    private val columns = if (narrow) arrayOf("Agent", "How", "Since") else arrayOf("Agent", "How", "Version", "Since")
    val table: JBTable
    val component: JComponent

    init {
        val data = rows.map { r -> if (narrow) arrayOf<Any>(r.name, r.howShort, r.since) else arrayOf<Any>(r.name, r.how, r.version, r.since) }.toTypedArray()
        val model = object : DefaultTableModel(data, columns as Array<Any>) { override fun isCellEditable(row: Int, column: Int) = false }
        table = JBTable(model).apply {
            setShowGrid(false)
            rowSelectionAllowed = false
            rowHeight = JBUI.scale(24)
            autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
            val plain = object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(t: JTable, v: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int): Component {
                    super.getTableCellRendererComponent(t, v, false, false, row, col)
                    val name = columns[col]
                    // App data and names are mono; the way and the time are muted.
                    font = if (name == "How") JBUI.Fonts.label() else mono()
                    foreground = if (name == "Agent") JBColor.foreground() else JBColor.GRAY
                    border = JBUI.Borders.empty(0, 6)
                    horizontalAlignment = SwingConstants.LEFT
                    val whole = v?.toString().orEmpty()
                    val metrics = getFontMetrics(font)
                    val room = t.columnModel.getColumn(col).width - JBUI.scale(12)
                    text = ClassNameFit.end(whole, { metrics.stringWidth(it) }, room)
                    toolTipText = whole
                    return this
                }
            }
            columnModel.columns.toList().forEach { it.cellRenderer = plain }
            tableHeader.reorderingAllowed = false
            fitColumns(this, plain)
        }
        component = JPanel(BorderLayout()).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            add(table.tableHeader, BorderLayout.NORTH)
            add(table, BorderLayout.CENTER)
            border = JBUI.Borders.emptyTop(2)
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

    /** The name is the identifying part: Agent takes what is left, every other column its content's width. */
    private fun fitColumns(t: JBTable, r: DefaultTableCellRenderer) {
        val cm = t.columnModel
        for (i in 1 until cm.columnCount) {
            val col = cm.getColumn(i)
            val header = t.tableHeader.defaultRenderer.getTableCellRendererComponent(t, col.headerValue, false, false, -1, i).preferredSize.width
            val cells = (0 until t.rowCount).maxOfOrNull { row ->
                val c = t.prepareRenderer(r, row, i)
                t.getFontMetrics(c.font).stringWidth(t.getValueAt(row, i)?.toString().orEmpty()) + JBUI.scale(12)
            } ?: 0
            val w = maxOf(header, cells) + JBUI.scale(4)
            col.minWidth = w; col.maxWidth = w; col.preferredWidth = w
        }
        cm.getColumn(0).apply { minWidth = JBUI.scale(60); preferredWidth = JBUI.scale(400) }
    }

    private fun mono(): Font = JBUI.Fonts.create(Font.MONOSPACED, JBUI.Fonts.label().size)
}
