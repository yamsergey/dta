package io.yamsergey.dta.studio.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
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
import com.intellij.ui.dsl.builder.SegmentedButton
import com.intellij.ui.dsl.builder.panel
import io.yamsergey.dta.present.network.EntryRows
import io.yamsergey.dta.present.network.KindChoice
import io.yamsergey.dta.present.network.ListOrder
import io.yamsergey.dta.present.network.LiveEvent
import io.yamsergey.dta.present.network.QueryLineModel
import io.yamsergey.dta.present.network.Suggestion
import io.yamsergey.dta.present.network.Row
import io.yamsergey.dta.present.network.Scrolling
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
 * that never jumps, newest at the bottom or on top as the person prefers, a toggle that follows
 * the newest rows (feature 021), a query line that filters and searches (040) with its chips,
 * hit counter and "shown of total", pause, and the entry detail beside or below it. Everything shown comes from [NetworkController]'s state; this class only binds it.
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
        autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        sizeColumns(this)
        // A column that needs explaining says so on its header.
        tableHeader.addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseMoved(e: java.awt.event.MouseEvent) {
                tableHeader.toolTipText = COLUMNS.getOrNull(convertColumnIndexToModel(columnAtPoint(e.point)))?.tooltip
            }
        })
        setDefaultRenderer(Any::class.java, RowRenderer())
        // Feature 026: right-click selects the row and offers its copy actions.
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) = popup(e)
            override fun mouseReleased(e: java.awt.event.MouseEvent) = popup(e)
            private fun popup(e: java.awt.event.MouseEvent) {
                if (!e.isPopupTrigger) return
                val row = rowAtPoint(e.point).takeIf { it >= 0 } ?: return
                setRowSelectionInterval(row, row)
                val r = this@NetworkPanel.model.rowAt(row)
                copyMenu.show(r.id, (r.entry["type"] as? JsonPrimitive)?.content, this@apply, e.x, e.y)
            }
        })
    }
    private val scroll = JBScrollPane(table)
    private val chips = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
    /** Feature 040: the query line — key:value filters and search words, as in the CLI and MCP. */
    private val query = com.intellij.ui.components.JBTextField().apply {
        emptyText.text = "Filter and search: POST status:4xx host:api.example.com -host:… token \"a phrase\""
        toolTipText = "<html>key:value words filter (method: status: host: url: kind: has:intervention body: run: since:5m until:, GET/POST…, http, ws);<br>" +
            "-key:value leaves matches out; other words are searched in URLs, headers and bodies and marked, nothing hidden.<br>" +
            "Enter / Shift+Enter (F3 / Shift+F3): next / previous hit.</html>"
    }
    private val hitLabel = JBLabel("").apply { foreground = com.intellij.ui.JBColor.GRAY }
    private val previousHit = ActionLink("↑") { stepHit(forward = false) }.apply { toolTipText = "Previous hit (Shift+Enter, Shift+F3)" }
    private val nextHit = ActionLink("↓") { stepHit(forward = true) }.apply { toolTipText = "Next hit (Enter, F3)" }
    private val typing = javax.swing.Timer(250) { network.setQuery(query.text) }.apply { isRepeats = false }
    private val suggestionList = com.intellij.ui.components.JBList<Suggestion>().apply {
        cellRenderer = com.intellij.ui.SimpleListCellRenderer.create("") { it.text + (it.hint?.let { h -> "   $h" } ?: "") }
    }
    private var suggestionPopup: com.intellij.openapi.ui.popup.JBPopup? = null
    private val caption = SteadyLabel().apply { foreground = com.intellij.ui.JBColor.GRAY }
    private val statements = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
    // The IDE's own segmented control: a plain JToggleButton's pressed state barely shows in the new UI.
    private lateinit var kinds: SegmentedButton<KindChoice>
    private val pause = JBCheckBox("Pause")
    private val redact = JBCheckBox("Redact")
    /** Live traffic, or the history this computer kept after the device forgot it (feature 011). */
    private val source = com.intellij.openapi.ui.ComboBox(arrayOf(LIVE, ARCHIVE)).apply {
        toolTipText = "Live: the app's traffic now. Archive: history the device cleared or lost, kept on this computer (browse it in the Daemon tab)."
    }
    private val newBar = ActionLink("") { network.list(LiveEvent.JumpToEnd); scrollToEdge() }
    private val orderLink = ActionLink("") { setOrder(model.order.toggled()) }
    private val empty = JBLabel("", javax.swing.SwingConstants.CENTER).apply { foreground = com.intellij.ui.JBColor.GRAY }
    /** Feature 026: copy actions, with the view's redaction. */
    private val copyMenu = io.yamsergey.dta.studio.network.EntryCopyMenu(project, scope, { context.client }) {
        JsonObject(listOfNotNull(last.device?.let { "device" to JsonPrimitive(it) }, last.app?.let { "app" to JsonPrimitive(it) }).toMap() +
            if (last.redact) mapOf("redact" to JsonPrimitive(true)) else emptyMap())
    }
    private val detail = EntryDetailPane(project, scope, copyMenu) { id -> network.reveal(id) }
    // The list gets most of the width; the person's own split is remembered.
    private val splitter = JBSplitter(false, "dta.network.split", 0.68f)
    private var programmaticScroll = false
    private var scrollValue = 0
    /** "Scroll to the newest", as the IDE's Build and Run views have it: shows and sets following. */
    private val follow = object : ToggleAction("Scroll to the Newest", "Follow new rows; scrolling away or selecting a row stops it", AllIcons.RunConfigurations.Scroll_down) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = last.list.following
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state) { network.list(LiveEvent.JumpToEnd); scrollToEdge() } else network.list(LiveEvent.ScrolledAway)
        }
    }
    private val followBar = ActionManager.getInstance().createActionToolbar("DtaNetworkFollow", DefaultActionGroup(follow), true).apply {
        targetComponent = this@NetworkPanel
        component.isOpaque = false
        component.border = JBUI.Borders.empty()
    }
    private var last = NetworkViewState()

    init {
        val kindSwitch = panel {
            row {
                kinds = segmentedButton(KindChoice.entries) { text = it.label }
                    .whenItemSelectedFromUi { choice -> if (last.line.kind != choice) { typing.stop(); network.setKind(choice) } }
            }
        }.apply { isOpaque = false }
        kinds.selectedItem = KindChoice.ALL
        // Row 1: the query line with its hit counter; row 2: its filters as chips and the count; row 3: how the list is shown.
        val queryRow = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            border = JBUI.Borders.empty(4, 8, 2, 8)
            add(query, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { isOpaque = false; add(hitLabel); add(previousHit); add(nextHit) }, BorderLayout.EAST)
        }
        val chipRow = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(0, 4)
            add(chips, BorderLayout.CENTER)
            add(caption, BorderLayout.EAST)
        }
        val more = ActionLink("⋯") { e ->
            val group = DefaultActionGroup(
                object : com.intellij.openapi.project.DumbAwareAction("Export HAR…") { override fun actionPerformed(e: AnActionEvent) = exportHar() },
                object : com.intellij.openapi.project.DumbAwareAction("Clear History…") { override fun actionPerformed(e: AnActionEvent) = clear() },
            )
            JBPopupFactory.getInstance().createActionGroupPopup(null, group, com.intellij.ide.DataManager.getInstance().getDataContext(this), JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false)
                .showUnderneathOf(e.source as java.awt.Component)
        }.apply { toolTipText = "Export HAR…, Clear history…" }
        val viewRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(2))).apply {
            add(kindSwitch)
            add(source)
            add(orderLink)
            add(followBar.component)
            add(pause)
            add(redact)
            add(more)
        }
        val toolbar = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            listOf(queryRow, chipRow, viewRow).forEach { it.alignmentX = LEFT_ALIGNMENT; add(it) }
        }
        bindQuery()
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
        source.addActionListener {
            val wanted = if (source.selectedItem == ARCHIVE) (last.archive ?: "all") else null
            if (wanted != last.archive) network.setArchive(wanted)
        }
        table.selectionModel.addListSelectionListener { e ->
            if (e.valueIsAdjusting || model.updating) return@addListSelectionListener
            val row = table.selectedRow.takeIf { it >= 0 }?.let { model.rowAt(it) }
            network.list(LiveEvent.Selected(row?.id))
        }
        // Only the person's own scrolling changes following: away from the newest edge stops it, back to it resumes.
        // Rows arriving grow the list without moving the view; DTA's own scrolling and reloads are flagged.
        scroll.verticalScrollBar.addAdjustmentListener { e ->
            val moved = e.value != scrollValue
            scrollValue = e.value
            if (programmaticScroll) return@addAdjustmentListener
            Scrolling.event(last.list.following, moved, atEdge())?.let { network.list(it) }
        }
        setOrder(ListOrder.of(PropertiesComponent.getInstance().getValue(ORDER_KEY)))
        Keyboard.bind(this, ViewKey.START) { if (model.rowCount > 0) table.changeSelection(0, 0, false, false) }
        Keyboard.bind(this, ViewKey.END) { network.list(LiveEvent.JumpToEnd); table.clearSelection(); scrollToEdge() }
        Keyboard.bind(this, ViewKey.PAUSE) { pause.doClick() }
        Keyboard.bind(this, ViewKey.FILTERS) { query.requestFocusInWindow() }
        Keyboard.bind(this, ViewKey.SEARCH) { query.requestFocusInWindow() }
        fun key(stroke: String, action: () -> Unit) = registerKeyboardAction({ action() }, javax.swing.KeyStroke.getKeyStroke(stroke), WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        key("F3") { stepHit(forward = true) }
        key("shift F3") { stepHit(forward = false) }
        Keyboard.bind(this, ViewKey.COPY) { copySelected() }
        Keyboard.bind(this, ViewKey.OPEN) { detail.requestFocusInWindow() }
        Keyboard.bind(this, ViewKey.BACK) { table.requestFocusInWindow() }

        scope.launch {
            // At most one repaint per 100 ms however fast entries arrive (SC-002): a StateFlow keeps only the latest.
            network.state.collect { s ->
                // The latest state, not the one collected: a click handled meanwhile must not be undone by an older selection.
                withContext(Dispatchers.Main) { show(network.state.value) }
                delay(100)
            }
        }
    }

    private fun show(s: NetworkViewState) {
        val previous = last
        last = s
        // A reload can move the scroll bar too: that is not the person scrolling.
        programmaticScroll = true
        val added = try { model.update(s.list.rows) } finally { scroll.validate(); programmaticScroll = false }
        // Keep the selection on its row by id, whatever moved around it.
        s.list.selectedId?.let { id -> model.indexOf(id)?.let { i -> if (table.selectedRow != i) { model.updating = true; table.setRowSelectionInterval(i, i); model.updating = false } } }
        if (s.list.following) scrollToEdge()
        // Rows added above what the person is reading push it down; scroll by as much so nothing jumps.
        else if (added > 0 && model.order == ListOrder.NEWEST_FIRST) keepInPlace(added)
        if (s.list.following != previous.list.following) followBar.updateActionsAsync()

        // Set from the archive browser too: the switch follows the state.
        val wanted = if (s.archive != null) ARCHIVE else LIVE
        if (source.selectedItem != wanted) source.selectedItem = wanted
        pause.isEnabled = s.archive == null

        // A kinds filter the switch cannot express is a chip; then no switch position is lit.
        val kind = s.line.kind
        if (kinds.selectedItem != kind) kinds.selectedItem = kind
        // The line is the person's while they type; set from the state only when changed elsewhere (a chip, the switch).
        if (s.line.text != query.text && !typing.isRunning && s.line.text != previous.line.text) query.text = s.line.text
        val problems = s.line.parsed.problems.takeIf { s.line.text == query.text }.orEmpty()
        query.putClientProperty("JComponent.outline", if (problems.isEmpty()) null else "error")
        if (s.line != previous.line || chips.componentCount == 0) {
            chips.removeAll()
            s.line.chips.forEach { chip -> chips.add(ActionLink("${chip.label} ×") { typing.stop(); network.removeChip(chip) }.apply { toolTipText = "Remove this filter from the line" }) }
        }
        hitLabel.foreground = if (problems.isEmpty()) com.intellij.ui.JBColor.GRAY else DtaColors.of("status-server")
        hitLabel.text = when {
            problems.isNotEmpty() -> problems.first().message
            !s.line.hasSearch -> ""
            s.searching && s.hits.total == 0 -> "Searching…"
            else -> s.hits.label
        }
        hitLabel.toolTipText = problems.takeIf { it.size > 1 }?.joinToString("<br>", "<html>", "</html>") { it.message }
        previousHit.isVisible = s.line.hasSearch && s.hits.total > 0
        nextHit.isVisible = previousHit.isVisible
        // The caption keeps its width while traffic arrives (024); it starts over on new filters or a cleared list.
        if (s.line != previous.line || s.list.total < previous.list.total) caption.reset()
        val filterCount = s.line.chips.size
        caption.text = "${s.list.shown} of ${s.list.total} shown" + when (filterCount) { 0 -> ""; 1 -> " · 1 filter"; else -> " · $filterCount filters, combined with AND" }
        if (s.hits != previous.hits) table.repaint()
        statements.removeAll()
        (io.yamsergey.dta.present.Statements.forNetworkTab(s.statements) + s.searchStatements + listOfNotNull(
            if (s.list.paused) io.yamsergey.dta.present.Statement(io.yamsergey.dta.present.StatementKind.PENDING, word = "paused", detail = "paused: ${s.list.backlog} waiting; capture goes on") else null,
        )).forEach { statements.add(StatementMark(it)) }
        s.error?.let { statements.add(JBLabel(it).apply { foreground = DtaColors.of("status-server") }) }
        newBar.text = if (s.list.newCount > 0) "${s.list.newCount} new ${if (model.order == ListOrder.NEWEST_FIRST) "above" else "below"} — G to follow" else ""
        newBar.isVisible = s.list.newCount > 0
        empty.text = when {
            s.app == null -> ""
            s.loading -> "Loading…"
            s.list.rows.isEmpty() && s.line.chips.isEmpty() -> "No traffic yet. Requests appear here as the app makes them."
            s.list.rows.isEmpty() -> "Nothing matches these filters."
            else -> ""
        }
        val target = JsonObject(listOfNotNull(s.device?.let { "device" to JsonPrimitive(it) }, s.app?.let { "app" to JsonPrimitive(it) }).toMap())
        val selectedRow = s.list.selectedId?.let(model::indexOf)
        // Reached by a link (025): bring the row into view; one the filters hide is still shown, saying so.
        if (selectedRow != null && s.list.selectedId != previous.list.selectedId) table.scrollRectToVisible(table.getCellRect(selectedRow, 0, true))
        val unlisted = s.list.selectedId != null && selectedRow == null && !s.list.selectedGone && !s.loading
        val terms = if (s.line.hasSearch) s.line.parsed.terms.map { it.text } else emptyList()
        val hitTab = s.hits.current?.takeIf { it.id == s.list.selectedId }?.let { s.hits.tabOf(it.hits) }
        detail.show(context.client, target, s.list.selectedId, s.redact, s.list.selectedGone, unlisted, terms, hitTab)
        revalidate()
        repaint()
    }

    /** Whether the view is at the edge where new rows appear. */
    private fun atEdge(): Boolean {
        val bar = scroll.verticalScrollBar
        return if (model.order == ListOrder.NEWEST_FIRST) bar.value <= JBUI.scale(4)
        else bar.value + bar.visibleAmount >= bar.maximum - JBUI.scale(4)
    }

    private fun scrollToEdge() {
        if (model.rowCount == 0) return
        programmaticScroll = true
        table.scrollRectToVisible(table.getCellRect(model.order.followEdge(model.rowCount), 0, true))
        programmaticScroll = false
    }

    private fun keepInPlace(added: Int) {
        val bar = scroll.verticalScrollBar
        programmaticScroll = true
        scroll.validate()
        bar.value += added * table.rowHeight
        programmaticScroll = false
    }

    /** Shows newest on top or at the bottom, remembered across sessions; the selection and following stay. */
    private fun setOrder(order: ListOrder) {
        PropertiesComponent.getInstance().setValue(ORDER_KEY, order.name, ListOrder.DEFAULT.name)
        network.setHitsNewestFirst(order == ListOrder.NEWEST_FIRST)
        orderLink.text = if (order == ListOrder.NEWEST_FIRST) "↓ ${order.label}" else "↑ ${order.label}"
        orderLink.toolTipText = "Show ${order.toggled().label.lowercase()}"
        if (model.order == order && model.rowCount > 0) return
        model.reorder(order)
        val selected = last.list.selectedId?.let { model.indexOf(it) }
        if (selected != null) {
            model.updating = true; table.setRowSelectionInterval(selected, selected); model.updating = false
            table.scrollRectToVisible(table.getCellRect(selected, 0, true))
        } else if (last.list.following) scrollToEdge()
    }

    private fun clear() {
        val app = last.app ?: return
        if (Messages.showYesNoDialog(project, "Delete the captured network history of $app?", "Clear Network History", null) == Messages.YES) network.clear()
    }

    /** Feature 026: what the list shows (filters, live or archive, redaction) as a HAR file. */
    private fun exportHar() {
        if (last.app == null) return
        val descriptor = com.intellij.openapi.fileChooser.FileSaverDescriptor("Export HAR", "The entries the list shows, with bodies and WebSocket messages", "har")
        val name = "${last.app}-${java.time.LocalDateTime.now().withNano(0).toString().replace(':', '-')}.har"
        val file = com.intellij.openapi.fileChooser.FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save(name)?.file ?: return
        com.intellij.openapi.wm.StatusBar.Info.set("Exporting HAR to ${file.path}…", project)
        network.export(file.absolutePath) { result ->
            result.onSuccess { o ->
                fun n(k: String) = (o[k] as? JsonPrimitive)?.content ?: "0"
                val omitted = n("bodiesOmitted").takeIf { it != "0" }?.let { " $it bodies left out (the file says why)." } ?: ""
                com.intellij.openapi.wm.StatusBar.Info.set("Exported ${n("entries")} entries, ${n("webSocketMessages")} WebSocket messages, ${n("bodies")} bodies to ${file.path}.$omitted", project)
            }.onFailure { e -> Messages.showErrorDialog(project, e.message ?: e.javaClass.simpleName, "Export HAR") }
        }
    }

    private fun copySelected() {
        val row = table.selectedRow.takeIf { it >= 0 }?.let { model.rowAt(it) } ?: return
        val url = (row.entry["url"] as? JsonPrimitive)?.content ?: row.id
        CopyPasteManager.getInstance().setContents(StringSelection(url))
    }

    /** Feature 040: typing runs the line (debounced), suggestions follow the caret, Enter steps through hits. */
    private fun bindQuery() {
        query.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = changed()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = changed()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = changed()
            private fun changed() {
                if (query.text == last.line.text) return
                typing.restart()
                javax.swing.SwingUtilities.invokeLater { if (query.hasFocus()) suggest() }
            }
        })
        query.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent) { suggestionPopup?.cancel() }
        })
        fun on(stroke: String, action: () -> Unit) {
            query.registerKeyboardAction({ action() }, javax.swing.KeyStroke.getKeyStroke(stroke), WHEN_FOCUSED)
        }
        on("DOWN") { if (popupShown()) moveSuggestion(1) else suggest() }
        on("UP") { if (popupShown()) moveSuggestion(-1) }
        on("TAB") { if (popupShown()) applySuggestion() }
        on("ENTER") {
            if (popupShown()) { applySuggestion(); return@on }
            if (typing.isRunning) { typing.stop(); network.setQuery(query.text) }
            stepHit(forward = true)
        }
        on("shift ENTER") { stepHit(forward = false) }
        on("ESCAPE") { if (popupShown()) suggestionPopup?.cancel() else table.requestFocusInWindow() }
        suggestionList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) { applySuggestion(); query.requestFocusInWindow() }
        })
    }

    private fun popupShown() = suggestionPopup?.isVisible == true

    private fun suggest() {
        val items = last.line.withText(query.text).suggestions(query.caretPosition, QueryLineModel.seen(model.views()))
        suggestionPopup?.cancel()
        if (items.isEmpty()) return
        suggestionList.setListData(items.toTypedArray())
        suggestionList.selectedIndex = 0
        suggestionList.visibleRowCount = minOf(items.size, 8)
        suggestionPopup = JBPopupFactory.getInstance().createComponentPopupBuilder(JBScrollPane(suggestionList), null)
            .setRequestFocus(false).setFocusable(false).setCancelOnClickOutside(true).setMinSize(java.awt.Dimension(JBUI.scale(220), 0))
            .createPopup().also { it.showUnderneathOf(query) }
    }

    private fun moveSuggestion(by: Int) {
        val n = suggestionList.model.size
        if (n == 0) return
        suggestionList.selectedIndex = (suggestionList.selectedIndex + by).mod(n)
        suggestionList.ensureIndexIsVisible(suggestionList.selectedIndex)
    }

    private fun applySuggestion() {
        val chosen = suggestionList.selectedValue ?: return
        suggestionPopup?.cancel()
        val (applied, caret) = last.line.withText(query.text).apply(chosen, query.caretPosition)
        query.text = applied.text
        query.caretPosition = caret.coerceAtMost(query.text.length)
        // A key just completed ("status:") offers its values next.
        if (chosen.text.endsWith(":")) suggest()
    }

    /** Down the list = the next hit, whichever order the list shows. */
    private fun stepHit(forward: Boolean) {
        if (!last.line.hasSearch) return
        if (forward) network.nextHit() else network.previousHit()
    }

    private data class Column(val title: String, val sample: String?, val tooltip: String? = null)

    /** The live list's rows (oldest to newest) shown in [order]; indexes here are shown places. */
    private inner class RowsModel : AbstractTableModel() {
        private var rows: List<Row> = emptyList()
        var order = ListOrder.DEFAULT
            private set
        var updating = false
        private val cache = HashMap<Row, EntryRow>()

        fun view(row: Row): EntryRow = cache.getOrPut(row) { EntryRows.of(row.entry) }

        /** The newest rows as shown, for the query line's value suggestions (hosts, statuses, methods). */
        fun views(): List<EntryRow> = rows.takeLast(500).map(::view)

        fun rowAt(shown: Int): Row = rows[order.shown(shown, rows.size)]

        fun indexOf(id: String): Int? = rows.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { order.shown(it, rows.size) }

        fun reorder(next: ListOrder) {
            order = next
            updating = true
            fireTableDataChanged()
            updating = false
        }

        /** Appends, updates in place, or reloads, so rows never jump (patterns.md live list); returns how many were appended. */
        fun update(next: List<Row>): Int {
            val before = rows
            if (next === before) return 0
            updating = true
            rows = next
            if (cache.size > 20_000) cache.clear()
            var added = 0
            when {
                next.size >= before.size && before.indices.all { before[it].id == next[it].id } -> {
                    added = next.size - before.size
                    if (added > 0) order.appended(before.size, next.size).let { fireTableRowsInserted(it.first, it.last) }
                    before.indices.filter { before[it] != next[it] }.forEach { val i = order.shown(it, next.size); fireTableRowsUpdated(i, i) }
                }
                else -> fireTableDataChanged()
            }
            updating = false
            return added
        }

        override fun getRowCount() = rows.size
        override fun getColumnCount() = COLUMNS.size
        override fun getColumnName(column: Int) = COLUMNS[column].title
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = view(rowAt(rowIndex))
    }

    private inner class RowRenderer : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            // One renderer draws every cell: a tooltip set for one cell must not show on the next.
        toolTipText = null
        val r = value as? EntryRow ?: return
            // Feature 040: rows holding the search words are tinted; the current hit more.
            if (!selected && last.line.hasSearch && last.hits.hitsOf(r.id) != null) {
                background = if (last.hits.current?.id == r.id) io.yamsergey.dta.studio.ui.SEARCH_MARK else com.intellij.ui.JBColor(java.awt.Color(0xFF, 0xF6, 0xD6), java.awt.Color(0x3D, 0x37, 0x24))
            }
            val kind = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, DtaColors.of(r.kind.token))
            val muted = SimpleTextAttributes.GRAYED_ATTRIBUTES
            when (column) {
                0 -> append(r.time, muted)
                1 -> { append(r.kind.label, SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, DtaColors.of(r.kind.token))); toolTipText = r.kind.description.takeIf { it.isNotBlank() } }
                2 -> append(r.verb, SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, DtaColors.of(r.kind.token)))
                // The URL, nothing else: a message's payload is under its connection, in Messages.
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
                6 -> {
                    r.marks.shown.forEach { append("${it.kind.glyph} ${it.word}  ", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, DtaColors.text(it.kind))) }
                    r.marks.moreLabel?.let { append(it, muted) }
                    // No notes, no popup (an empty one showed before 021).
                    toolTipText = r.marks.shown.takeIf { it.isNotEmpty() }?.joinToString("<br>", "<html>", "</html>") { it.sentence }
                }
            }
        }
    }

    private companion object {
        const val ORDER_KEY = "dta.network.order"
        const val LIVE = "Live"
        const val ARCHIVE = "Archive"

        /** [sample] is the widest content the column shows; null = flexible, takes what is left. */
        val COLUMNS = listOf(
            Column("Time", "00:00:00.000"), Column("Type", "WS ↓"), Column("Method", "Received"), Column("URL", null),
            Column("Status", "closed 1000"), Column("Duration · Size", "1.3 s · 18.4 KB"),
            Column("Notes", null, "What DTA states about the entry: binary, scripted, redacted, incomplete, decoded…"),
        )

        /** Fixed columns as wide as their widest content (bold, as kinds and methods are drawn); URL and Notes share the rest. */
        fun sizeColumns(table: JTable) {
            val bold = table.getFontMetrics(table.font.deriveFont(java.awt.Font.BOLD))
            val header = table.tableHeader.getFontMetrics(table.tableHeader.font)
            COLUMNS.forEachIndexed { i, c ->
                val column = table.columnModel.getColumn(i)
                if (c.sample == null) {
                    column.preferredWidth = JBUI.scale(if (c.title == "URL") 420 else 180)
                } else {
                    val width = maxOf(bold.stringWidth(c.sample), header.stringWidth(c.title)) + JBUI.scale(16)
                    column.minWidth = width
                    column.preferredWidth = width
                    column.maxWidth = width + JBUI.scale(24)
                }
            }
        }
    }
}
