package io.yamsergey.dta.studio.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.JBColor
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.present.agents.AgentServerState
import io.yamsergey.dta.present.agents.AgentsTabModel
import io.yamsergey.dta.present.agents.AgentsView
import io.yamsergey.dta.present.agents.Tone
import io.yamsergey.dta.studio.agents.AgentClientsFeed
import io.yamsergey.dta.studio.agents.AgentRedactionController
import io.yamsergey.dta.studio.agents.AgentServerController
import io.yamsergey.dta.studio.ui.DtaColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

/**
 * Feature 073 (FR-001, FR-003..FR-005, mockup boards 1 and 5): who can reach DTA through agents, now. The agent
 * server's state and address, the agents connected (live, from the daemon), whether they see secrets masked, and
 * the connect block. The actions are the left strip; the list below 800 px drops its Version column. When the daemon
 * is not running that is the only thing the tab says. What it draws is [AgentsTabModel]'s view.
 */
class AgentsTabPanel(
    scope: CoroutineScope,
    feed: AgentClientsFeed,
    private val controller: AgentServerController,
    private val redaction: AgentRedactionController,
    private val connect: ConnectAgentBlock,
    private val openSettings: () -> Unit,
    private val model: AgentsTabModel = AgentsTabModel(),
) : JPanel(BorderLayout()) {
    private val body = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private var shown: Pair<AgentsView, Boolean>? = null
    private val ticker = javax.swing.Timer(1_000) { render() }

    init {
        val strip = ActionManager.getInstance().createActionToolbar("DtaAgentsStrip", DefaultActionGroup(
            Act("Copy Command for the Chosen Agent", AllIcons.Actions.Copy) { connect.copyCommand() },
            Act("Copy a Prompt That Teaches the Agent DTA", AllIcons.Actions.IntentionBulb) { connect.copyPrompt() },
            Separator.getInstance(),
            Act("Agents Settings…", AllIcons.General.Settings) { openSettings() },
        ), false).apply {
            targetComponent = this@AgentsTabPanel
            component.border = JBUI.Borders.emptyRight(2)
        }
        add(strip.component, BorderLayout.WEST)
        val tracking = object : JPanel(BorderLayout()), javax.swing.Scrollable {
            override fun getPreferredScrollableViewportSize() = preferredSize
            override fun getScrollableUnitIncrement(r: java.awt.Rectangle?, o: Int, d: Int) = JBUI.scale(16)
            override fun getScrollableBlockIncrement(r: java.awt.Rectangle?, o: Int, d: Int) = JBUI.scale(64)
            override fun getScrollableTracksViewportWidth() = true
            override fun getScrollableTracksViewportHeight() = false
        }.apply { add(body, BorderLayout.NORTH) }
        add(JBScrollPane(tracking).apply { border = JBUI.Borders.empty(); horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER }, BorderLayout.CENTER)
        addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent?) { render(); wrapped.forEach { it.wrapTo(room()) } }
        })
        scope.launch {
            combine(feed.clients, controller.state, redaction.state) { clients, _, _ -> clients }.collect { clients ->
                withContext(Dispatchers.Main) {
                    model.daemon(clients)
                    // The command names the server's port: it follows the setting.
                    connect.render()
                    if (clients != null) redaction.takeIf { it.state.value.on == null }?.refresh()
                    render()
                }
            }
        }
    }

    private var watching: kotlinx.coroutines.Job? = null

    // D-121: the setting is the daemon's and Settings has its own controller: the tab follows the daemon's change event while it is shown.
    override fun addNotify() { super.addNotify(); ticker.start(); watching?.cancel(); watching = redaction.follow() }
    override fun removeNotify() { ticker.stop(); watching?.cancel(); watching = null; super.removeNotify() }

    private val narrow get() = width in 1 until JBUI.scale(NARROW_BELOW)

    private fun render() {
        val view = model.view(controller.state.value, redaction.state.value.on)
        if (shown == view to narrow) return
        shown = view to narrow
        body.removeAll()
        wrapped.clear()
        body.border = JBUI.Borders.empty(12, 16)
        when (view) {
            AgentsView.DaemonNotRunning -> body.add(left(JBLabel("Daemon not running").apply { foreground = JBColor.GRAY }))
            is AgentsView.Running -> running(view)
        }
        body.revalidate()
        body.repaint()
    }

    private fun running(view: AgentsView.Running) {
        body.add(heading("Agent server") { ActionLink("Settings…") { openSettings() } })
        val server = view.server
        body.add(left(JPanel(LineOrStack(JBUI.scale(8), lastAtEdge = false)).apply {
            isOpaque = false
            val color = when (server.tone) { Tone.LIVE -> DtaColors.of("live"); Tone.FAILED -> DtaColors.of("status-server"); Tone.NEUTRAL -> JBColor.GRAY }
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(JBLabel("●").apply { foreground = color })
                add(JBLabel(server.word).apply { foreground = if (server.tone == Tone.NEUTRAL) JBColor.foreground() else color })
            })
            server.address?.let { add(JBLabel(it).apply { font = mono() }) }
            server.action?.let { add(ActionLink(it) { openSettings() }) }
        }))

        body.add(heading("Connected now"))
        if (view.rows.isEmpty() && view.cliCalls == 0) {
            body.add(left(JBLabel("No agents connected. Connect one below.").apply { foreground = JBColor.GRAY }))
        } else {
            if (view.rows.isNotEmpty()) body.add(table(view))
            if (view.cliCalls > 0) body.add(left(JBLabel("${view.cliCalls} CLI call${if (view.cliCalls == 1) "" else "s"} in the last minute").apply { foreground = JBColor.GRAY; border = JBUI.Borders.emptyTop(4) }))
        }

        view.redaction?.let { line ->
            body.add(heading("Secrets"))
            body.add(left(JPanel(LineOrStack(0, lastAtEdge = false)).apply {
                isOpaque = false
                add(JBLabel(line.text.trimEnd()))
                line.sample?.let { add(JBLabel(it).apply { font = mono(); foreground = JBColor.GRAY; border = JBUI.Borders.emptyLeft(4) }) }
            }))
            body.add(left(note("The server, dta mcp and the CLI, and every export. These tabs show values as sent.")))
            body.add(left(ActionLink("Change in Settings…") { openSettings() }, top = 0))
        }

        body.add(heading("Connect an agent"))
        body.add(left(JPanel(LineOrStack(JBUI.scale(8), lastAtEdge = false)).apply {
            isOpaque = false
            add(connect.agent)
            add(JBLabel("Copy the command or the prompt from the strip at the left.").apply { foreground = JBColor.GRAY; font = JBUI.Fonts.smallFont() })
        }))
        body.add(left(connect.command.also { it.border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border()), JBUI.Borders.empty(6, 8)) }, top = 6))
    }

    private fun table(view: AgentsView.Running): JComponent {
        val columns = if (narrow) arrayOf("Agent", "How", "Since") else arrayOf("Agent", "How", "Version", "Since")
        val data = view.rows.map { r -> if (narrow) arrayOf<Any>(r.name, r.howShort, r.since) else arrayOf<Any>(r.name, r.how, r.version, r.since) }.toTypedArray()
        val model = object : DefaultTableModel(data, columns as Array<Any>) { override fun isCellEditable(row: Int, column: Int) = false }
        val table = JBTable(model).apply {
            setShowGrid(false)
            rowSelectionAllowed = false
            rowHeight = JBUI.scale(24)
            val plain = object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(t: JTable, v: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int): Component {
                    super.getTableCellRendererComponent(t, v, false, false, row, col)
                    val name = columns[col]
                    // App data and names are mono; the way and the time are muted.
                    font = if (name == "How") JBUI.Fonts.label() else mono()
                    foreground = if (name == "Agent") JBColor.foreground() else JBColor.GRAY
                    border = JBUI.Borders.empty(0, 6)
                    horizontalAlignment = SwingConstants.LEFT
                    return this
                }
            }
            columnModel.columns.toList().forEach { it.cellRenderer = plain }
            tableHeader.reorderingAllowed = false
        }
        return JPanel(BorderLayout()).apply {
            alignmentX = LEFT_ALIGNMENT
            add(table.tableHeader, BorderLayout.NORTH)
            add(table, BorderLayout.CENTER)
            border = JBUI.Borders.emptyTop(2)
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

    private fun mono(): Font = JBUI.Fonts.create(Font.MONOSPACED, JBUI.Fonts.label().size)

    private fun heading(text: String, trailing: (() -> JComponent)? = null): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        border = JBUI.Borders.emptyTop(if (body.componentCount == 0) 0 else 12)
        add(TitledSeparator(text), BorderLayout.CENTER)
        trailing?.let { add(it(), BorderLayout.EAST) }
        maximumSize = java.awt.Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    private fun left(c: JComponent, top: Int = 4): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        border = JBUI.Borders.emptyTop(top)
        add(c, BorderLayout.CENTER)
    }

    /** Muted small text that wraps at the width the tab has. */
    private fun note(text: String): JComponent = WrappedLabel(text.replace("&", "&amp;").replace("<", "&lt;")).apply { foreground = JBColor.GRAY; font = JBUI.Fonts.smallFont() }.also { wrapped += it; it.wrapTo(room()) }

    private val wrapped = mutableListOf<WrappedLabel>()
    private fun room() = (width - JBUI.scale(34 + 32)).coerceAtLeast(JBUI.scale(120))

    private class Act(text: String, icon: javax.swing.Icon, private val run: () -> Unit) : DumbAwareAction(text, null, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) = run()
    }

    companion object {
        /** Below this width the list drops its Version column (mockup board 5). */
        const val NARROW_BELOW = 800
    }
}
