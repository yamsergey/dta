package io.yamsergey.dta.studio.toolwindow

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.present.network.EntryRow
import io.yamsergey.dta.present.network.EntryRows
import io.yamsergey.dta.present.network.LiveEvent
import io.yamsergey.dta.present.network.NetworkFilters
import io.yamsergey.dta.present.network.Row
import io.yamsergey.dta.studio.ContextController
import io.yamsergey.dta.studio.network.EntryDetailPane
import io.yamsergey.dta.studio.network.NetworkController
import io.yamsergey.dta.studio.network.NetworkViewState
import io.yamsergey.dta.studio.ui.DtaColors
import io.yamsergey.dta.studio.ui.Keyboard
import io.yamsergey.dta.studio.ui.StatementMark
import io.yamsergey.dta.studio.ui.ViewKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

/**
 * The Network view (US1, contracts/tool-window.md): one live list of HTTP and WebSocket traffic
 * that never jumps, filter chips with "shown of total", pause, and the entry detail beside or
 * below it. Everything shown comes from [NetworkController]'s state; this class only binds it.
 */
class NetworkPanel(
    private val project: Project,
    private val network: NetworkController,
    private val context: ContextController,
    private val scope: CoroutineScope,
) : JPanel(BorderLayout()) {
    private val model = RowsModel()
    private val table = JBTable(model).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        setShowGrid(false)
        autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        COLUMNS.forEachIndexed { i, c -> columnModel.getColumn(i).preferredWidth = JBUI.scale(c.width) }
        setDefaultRenderer(Any::class.java, RowRenderer())
    }
    private val scroll = JBScrollPane(table)
    private val chips = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
    private val caption = JBLabel().apply { foreground = com.intellij.ui.JBColor.GRAY }
    private val statements = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
    private val kinds = io.yamsergey.dta.present.network.KindChoice.entries.associateWith { javax.swing.JToggleButton(it.label) }
    private val pause = JBCheckBox("Pause")
    private val redact = JBCheckBox("Redact")
    private val newBar = ActionLink("") { network.list(LiveEvent.JumpToEnd); scrollToEnd() }
    private val empty = JBLabel("", javax.swing.SwingConstants.CENTER).apply { foreground = com.intellij.ui.JBColor.GRAY }
    private val detail = EntryDetailPane(project, scope)
    private val splitter = JBSplitter(false, 0.55f)
    private var programmaticScroll = false
    private var last = NetworkViewState()

    init {
        val kindGroup = javax.swing.ButtonGroup()
        val kindSwitch = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            kinds.forEach { (choice, button) ->
                kindGroup.add(button)
                button.addActionListener { if (last.filters.kind != choice) network.setKind(choice) }
                add(button)
            }
        }
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(2))).apply {
            add(kindSwitch)
            add(ActionLink("+ Filter") { addFilter() })
            add(chips)
            add(caption)
            add(pause)
            add(redact)
            add(ActionLink("Clear") { clear() })
        }
        val master = JPanel(BorderLayout()).apply {
            add(JPanel(BorderLayout()).apply { add(toolbar, BorderLayout.NORTH); add(statements, BorderLayout.SOUTH) }, BorderLayout.NORTH)
            add(scroll, BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply { add(newBar, BorderLayout.WEST); add(empty, BorderLayout.CENTER) }, BorderLayout.SOUTH)
        }
        splitter.firstComponent = master
        splitter.secondComponent = detail
        add(splitter, BorderLayout.CENTER)
        // Detail to the right when wide, below when narrow (patterns.md).
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) { splitter.orientation = width < JBUI.scale(900) }
        })

        pause.addActionListener { network.list(if (pause.isSelected) LiveEvent.Pause else LiveEvent.Resume) }
        redact.addActionListener { network.setRedact(redact.isSelected) }
        table.selectionModel.addListSelectionListener { e ->
            if (e.valueIsAdjusting || model.updating) return@addListSelectionListener
            val row = table.selectedRow.takeIf { it >= 0 }?.let { model.rows[it] }
            network.list(LiveEvent.Selected(row?.id))
        }
        // Only the person's own scrolling stops following: rows arriving also move the scroll bar.
        scroll.addMouseWheelListener { e -> if (e.wheelRotation < 0 && last.list.following) network.list(LiveEvent.ScrolledAway) }
        scroll.verticalScrollBar.addAdjustmentListener { e ->
            if (!programmaticScroll && e.valueIsAdjusting && !atEnd() && last.list.following) network.list(LiveEvent.ScrolledAway)
        }
        Keyboard.bind(this, ViewKey.START) { if (model.rowCount > 0) table.changeSelection(0, 0, false, false) }
        Keyboard.bind(this, ViewKey.END) { network.list(LiveEvent.JumpToEnd); table.clearSelection(); scrollToEnd() }
        Keyboard.bind(this, ViewKey.PAUSE) { pause.doClick() }
        Keyboard.bind(this, ViewKey.FILTERS) { addFilter() }
        Keyboard.bind(this, ViewKey.SEARCH) { addFilter("url") }
        Keyboard.bind(this, ViewKey.COPY) { copySelected() }
        Keyboard.bind(this, ViewKey.OPEN) { detail.requestFocusInWindow() }
        Keyboard.bind(this, ViewKey.BACK) { table.requestFocusInWindow() }

        scope.launch {
            // At most one repaint per 100 ms however fast entries arrive (SC-002): a StateFlow keeps only the latest.
            network.state.collect { s ->
                withContext(Dispatchers.Main) { show(s) }
                delay(100)
            }
        }
    }

    private fun show(s: NetworkViewState) {
        val previous = last
        last = s
        model.update(s.list.rows)
        // Keep the selection on its row by id, whatever moved around it.
        s.list.selectedId?.let { id -> model.indexOf(id)?.let { i -> if (table.selectedRow != i) { model.updating = true; table.setRowSelectionInterval(i, i); model.updating = false } } }
        if (s.list.following && s.list.selectedId == null) scrollToEnd()

        // A kinds filter the switch cannot express is a chip; then no switch position is lit.
        val kind = s.filters.kind
        kinds.forEach { (choice, button) -> button.isSelected = choice == kind }
        if (kind == null) kinds.values.firstOrNull()?.let { (it.model as? javax.swing.DefaultButtonModel)?.group?.clearSelection() }
        if (s.filters != previous.filters || chips.componentCount == 0) {
            chips.removeAll()
            s.filters.chips.forEach { chip -> chips.add(ActionLink("${chip.label} ×") { network.removeFilter(chip.name) }) }
        }
        caption.text = s.filters.caption(s.list.shown, s.list.total)
        statements.removeAll()
        (s.statements + listOfNotNull(
            if (s.list.paused) io.yamsergey.dta.present.Statement(io.yamsergey.dta.present.StatementKind.PENDING, word = "paused", detail = "paused: ${s.list.backlog} waiting; capture goes on") else null,
        )).forEach { statements.add(StatementMark(it)) }
        s.error?.let { statements.add(JBLabel(it).apply { foreground = DtaColors.of("status-server") }) }
        newBar.text = if (s.list.newCount > 0) "${s.list.newCount} new — G to follow" else ""
        newBar.isVisible = s.list.newCount > 0
        empty.text = when {
            s.app == null -> ""
            s.loading -> "Loading…"
            s.list.rows.isEmpty() && s.filters.isEmpty -> "No traffic yet. Requests appear here as the app makes them."
            s.list.rows.isEmpty() -> "Nothing matches these filters."
            else -> ""
        }
        val target = JsonObject(listOfNotNull(s.device?.let { "device" to JsonPrimitive(it) }, s.app?.let { "app" to JsonPrimitive(it) }).toMap())
        detail.show(context.client, target, s.list.selectedId, s.redact, s.list.selectedGone)
        revalidate()
        repaint()
    }

    private fun atEnd(): Boolean {
        val bar = scroll.verticalScrollBar
        return bar.value + bar.visibleAmount >= bar.maximum - JBUI.scale(4)
    }

    private fun scrollToEnd() {
        if (model.rowCount == 0) return
        programmaticScroll = true
        table.scrollRectToVisible(table.getCellRect(model.rowCount - 1, 0, true))
        programmaticScroll = false
    }

    private fun clear() {
        val app = last.app ?: return
        if (Messages.showYesNoDialog(project, "Delete the captured network history of $app?", "Clear Network History", null) == Messages.YES) network.clear()
    }

    private fun copySelected() {
        val row = table.selectedRow.takeIf { it >= 0 }?.let { model.rows[it] } ?: return
        val url = (row.entry["url"] as? JsonPrimitive)?.content ?: row.id
        CopyPasteManager.getInstance().setContents(StringSelection(url))
    }

    /** Asks for one filter value; the choices are the network.list parameters. */
    private fun addFilter(preset: String? = null) {
        val names = NetworkFilters.NAMES
        fun ask(name: String) {
            val value = Messages.showInputDialog(project, prompt(name), "Network Filter", null) ?: return
            if (value.isBlank()) return
            network.setFilter(name, when (name) {
                "kinds" -> JsonArray(value.split(',').map { JsonPrimitive(it.trim()) })
                "intervened" -> JsonPrimitive(value.trim().lowercase() in setOf("true", "yes", "scripted"))
                "sinceMs", "untilMs" -> JsonPrimitive(value.trim().toLongOrNull() ?: return)
                else -> JsonPrimitive(value.trim())
            })
        }
        if (preset != null) return ask(preset)
        JBPopupFactory.getInstance().createPopupChooserBuilder(names).setTitle("Filter by").setItemChosenCallback { ask(it) }.createPopup().showUnderneathOf(chips)
    }

    private fun prompt(name: String) = when (name) {
        "url" -> "URL contains (case-insensitive):"
        "method" -> "HTTP method:"
        "status" -> "Status: a code (404), a class (4xx), failed or pending:"
        "kinds" -> "Kinds, comma-separated (http, ws-connection, ws-message):"
        "intervened" -> "Scripted only (true) or untouched only (false):"
        "bodyContains" -> "Body contains (case-sensitive):"
        "runs" -> "Runs: current or all:"
        else -> "$name (epoch milliseconds):"
    }

    private data class Column(val title: String, val width: Int)

    private inner class RowsModel : AbstractTableModel() {
        var rows: List<Row> = emptyList()
            private set
        var updating = false
        private val cache = HashMap<Row, EntryRow>()

        fun view(row: Row): EntryRow = cache.getOrPut(row) { EntryRows.of(row.entry) }

        fun indexOf(id: String): Int? = rows.indexOfFirst { it.id == id }.takeIf { it >= 0 }

        /** Appends, updates in place, or reloads, so rows never jump (patterns.md live list). */
        fun update(next: List<Row>) {
            val before = rows
            if (next === before) return
            updating = true
            rows = next
            if (cache.size > 20_000) cache.clear()
            when {
                next.size >= before.size && before.indices.all { before[it].id == next[it].id } -> {
                    val changed = before.indices.filter { before[it] != next[it] }
                    changed.forEach { fireTableRowsUpdated(it, it) }
                    if (next.size > before.size) fireTableRowsInserted(before.size, next.size - 1)
                }
                else -> fireTableDataChanged()
            }
            updating = false
        }

        override fun getRowCount() = rows.size
        override fun getColumnCount() = COLUMNS.size
        override fun getColumnName(column: Int) = COLUMNS[column].title
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = view(rows[rowIndex])
    }

    private inner class RowRenderer : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            val r = value as? EntryRow ?: return
            val kind = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, DtaColors.of(r.kind.token))
            val muted = SimpleTextAttributes.GRAYED_ATTRIBUTES
            when (column) {
                0 -> append(r.time, muted)
                1 -> { append(r.kind.label, SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, DtaColors.of(r.kind.token))); toolTipText = r.kind.description }
                2 -> append(r.verb, SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, DtaColors.of(r.kind.token)))
                3 -> { r.host?.let { append(it, muted) }; append(r.path) }
                4 -> {
                    val st = r.outcome.statement
                    when {
                        st != null -> append("${st.kind.glyph} ${st.word}", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, DtaColors.text(st.kind)))
                        r.outcome.token != null -> append(r.outcome.text, SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, DtaColors.of(r.outcome.token!!)))
                        else -> append(r.outcome.text)
                    }
                }
                5 -> append(r.timing, muted)
                6 -> append(r.run, muted)
                7 -> {
                    r.marks.shown.forEach { append("${it.kind.glyph} ${it.word}  ", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, DtaColors.text(it.kind))) }
                    r.marks.moreLabel?.let { append(it, muted) }
                    toolTipText = r.marks.shown.joinToString("<br>", "<html>", "</html>") { it.sentence }
                }
            }
        }
    }

    private companion object {
        val COLUMNS = listOf(
            Column("Time", 90), Column("Type", 52), Column("Method / Op", 80), Column("URL", 420),
            Column("Status", 80), Column("Time · Size", 110), Column("Run", 50), Column("Statements", 200),
        )
    }
}
