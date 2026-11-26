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
import io.yamsergey.dta.studio.network.QueryCompletion
import com.intellij.util.textCompletion.TextFieldWithCompletion
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
 * the newest rows (feature 021), a query line that filters and searches (040), the app's saved
 * filters as chips (043), a tool strip at the table's left and a caption under it as the IDE's
 * own lists have, and the entry detail beside or below it. Everything shown comes from [NetworkController]'s state; this class only binds it.
 */
class NetworkPanel(
    private val project: Project,
    private val network: NetworkController,
    private val context: ContextController,
    private val scope: CoroutineScope,
    /** Feature 066: opens the Daemon tab on this app's network history. */
    private val openHistory: () -> Unit = {},
    /** D-024: opens the Script tab (the script control's popup). */
    private val openScript: () -> Unit = {},
) : JPanel(BorderLayout()) {
    private val model = RowsModel()
    /** D-022: the Load older row, at the list's oldest edge — under the header when the oldest rows are on top, under the rows otherwise. */
    private val olderTop = OlderRow()
    private val olderBottom = OlderRow()
    private val table = object : JBTable(model) {
        // The Load older row sits under the header, inside the scroll pane's header area: it does not scroll away.
        override fun configureEnclosingScrollPane() {
            super.configureEnclosingScrollPane()
            val pane = javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane::class.java, this) as? javax.swing.JScrollPane ?: return
            pane.setColumnHeaderView(JPanel(BorderLayout()).apply { add(tableHeader, BorderLayout.CENTER); add(olderTop, BorderLayout.SOUTH) })
        }
    }.apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        setShowGrid(false)
        autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        sizeColumns(this)
        // Feature 043: the list is ordered from its headers.
        val plainHeader = tableHeader.defaultRenderer
        tableHeader.defaultRenderer = javax.swing.table.TableCellRenderer { t, value, selected, focus, row, column ->
            plainHeader.getTableCellRendererComponent(t, value, selected, focus, row, column).also { c ->
                (c as? javax.swing.JLabel)?.apply {
                    horizontalTextPosition = javax.swing.SwingConstants.LEADING
                    icon = this@NetworkPanel.model.sortIcon(t.convertColumnIndexToModel(column))
                }
            }
        }
        tableHeader.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                val column = convertColumnIndexToModel(columnAtPoint(e.point))
                if (column >= 0) sortBy(column)
            }
        })
        // A column that needs explaining says so on its header.
        tableHeader.addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseMoved(e: java.awt.event.MouseEvent) {
                tableHeader.toolTipText = COLUMNS.getOrNull(convertColumnIndexToModel(columnAtPoint(e.point)))?.tooltip
            }
        })
        setDefaultRenderer(Any::class.java, CellRenderer())
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
    // Feature 043: the app's saved filters; wrapping rows, a narrow pane moves chips to the next line.
    private val chips = JPanel(com.intellij.util.ui.WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply { isOpaque = false }
    private var chipsShown: List<String>? = null
    /** What the line's completion offers: set on the EDT, read by completion off it (063). */
    @Volatile private var completionInput = QueryCompletion.Input()
    /** Feature 040: the query line — key:value filters and search words, as in the CLI and MCP; 063: the IDE's completion field. */
    private val query: TextFieldWithCompletion = TextFieldWithCompletion(
        project,
        QueryCompletion({ completionInput }),
        "", /* oneLineMode = */ true, /* autoPopup = */ true, /* forceAutoPopup = */ false, /* showHint = */ false,
    ).apply {
        setPlaceholder("Filter and search: body:text body:/regex/ status:4xx dir:received host:… -host:… token \"a phrase\" (${completionShortcut()} for every filter)")
        setShowPlaceholderWhenFocused(true)
        toolTipText = "<html>key:value words filter. Any entry: body: url: host: kind: source:webview has:intervention run: since:5m until:<br>" +
            "HTTP: method: (or GET/POST…) status: header: req: resp: mime: duration:&gt;1s size:&gt;100k<br>" +
            "WebSocket: dir:sent|received frame:text|binary type: topic: conn: status:open|closed|1006<br>" +
            "body: req: resp: take text or a /regex/ pattern: body:/\"id\":\\s*\\d+/ (case ignored; body:\"/x/\" is literal text)<br>" +
            "-key:value leaves matches out; other words are searched in URLs, headers, bodies and messages: only the matches are listed.<br>" +
            "The list and search cover the app's current run; Archive includes earlier runs and archived history (run:all alone does not).<br>" +
            "A search shows its matches as it finds them; Stop (or a new query) ends it. No time limit cuts a pattern.<br>" +
            "Enter saves the filters for this app as chips; with only search words Enter / Shift+Enter (F3 / Shift+F3): next / previous hit.<br>" +
            "${completionShortcut()} or Down lists every filter at the caret.</html>"
    }
    private val hitLabel = JBLabel("").apply { foreground = com.intellij.ui.JBColor.GRAY }
    /** D-021: what is wrong with the line, under it, and which query the list keeps meanwhile. */
    private val problemText = io.yamsergey.dta.studio.ui.WrappingText("", DtaColors.of("status-server"))
    private val keptText = io.yamsergey.dta.studio.ui.WrappingText("", com.intellij.ui.JBColor.GRAY)
    private val problemRow = JPanel().apply {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(0, 8, 2, 8)
        isVisible = false
        add(problemText)
        add(keptText)
    }
    private val typing = javax.swing.Timer(250) { network.setQuery(query.text) }.apply { isRepeats = false }
    private val caption = SteadyLabel().apply { foreground = com.intellij.ui.JBColor.GRAY }
    /** Feature 071 (boards 1–7): the spinner while a search reads its scope, and the caption's link (Stop / Include archive). */
    private val spinner = JBLabel(com.intellij.ui.AnimatedIcon.Default()).apply { isVisible = false }
    private var captionAction: io.yamsergey.dta.present.network.ScopeCaption.Action? = null
    private val captionLink = com.intellij.ui.components.ActionLink("") {
        when (captionAction) {
            io.yamsergey.dta.present.network.ScopeCaption.Action.STOP -> network.stopSearch()
            io.yamsergey.dta.present.network.ScopeCaption.Action.INCLUDE_ARCHIVE -> network.setArchiveOn(true)
            null -> Unit
        }
    }.apply { isVisible = false }
    /** Feature 071: the Archive switch, after the source (off at each IDE start). */
    private val archiveBox = com.intellij.ui.components.JBCheckBox("Archive").apply {
        toolTipText = "Include every earlier run and the archived history this computer keeps for the app. Off: only the app's current run (since its process last started)."
        isOpaque = false
    }
    /** Feature 071 (board 5): run:all while the switch is off widens nothing; said under the line. */
    private val runAllHint = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
        isOpaque = false
        border = JBUI.Borders.empty(0, 8, 2, 8)
        add(JBLabel(io.yamsergey.dta.present.network.ScopeCaption.RUN_ALL_HINT, AllIcons.General.Information, javax.swing.SwingConstants.LEFT).apply { foreground = com.intellij.ui.JBColor.GRAY })
        add(com.intellij.ui.components.ActionLink(io.yamsergey.dta.present.network.ScopeCaption.TURN_ON_ARCHIVE) { network.setArchiveOn(true) })
        isVisible = false
    }
    /** Feature 071 (board 4): the app started again; the previous run is named, with the way to include it. */
    private val restartBanner = io.yamsergey.dta.studio.ui.BannerPanel().apply {
        onAction { network.setArchiveOn(true) }
    }
    private val statements = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
    /** Feature 061: browser capture, while a browser switch is on. */
    private val webStatus = WebStatusRow(context, scope)
    private val scriptControl = ScriptControl()
    // The IDE's own segmented control: a plain JToggleButton's pressed state barely shows in the new UI.
    private lateinit var kinds: SegmentedButton<KindChoice>
    /**
     * Live traffic, or one of the app's sessions this computer kept after the device forgot them
     * (feature 011), listed by when they ran (feature 056), then all of them.
     */
    private data class SourceChoice(val scope: String?, val label: String, val meta: String = "", val enabled: Boolean = true) {
        override fun toString() = label
    }
    private val liveChoice = SourceChoice(null, LIVE, "the app now")
    private val sources = javax.swing.DefaultComboBoxModel(arrayOf(liveChoice))
    private val source = com.intellij.openapi.ui.ComboBox(sources).apply {
        toolTipText = "Live: the app's traffic now. Below it: the app's sessions kept on this computer after the device cleared or lost them (delete them in the Daemon tab)."
        // The closed switch fits a session's time; the open list (the IDE's popup) is as wide as its rows.
        // D-024: the closed switch as wide as its choice ("Live" is short), so the view row keeps the script control beside it.
        prototypeDisplayValue = liveChoice
        isSwingPopup = false
        renderer = object : com.intellij.ui.ColoredListCellRenderer<SourceChoice>() {
            override fun customizeCellRenderer(list: javax.swing.JList<out SourceChoice>, value: SourceChoice?, index: Int, selected: Boolean, focus: Boolean) {
                value ?: return
                append(value.label, if (value.enabled) com.intellij.ui.SimpleTextAttributes.REGULAR_ATTRIBUTES else com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES)
                // The meta only in the open list: the closed switch stays one short word or time.
                if (index >= 0 && value.meta.isNotEmpty()) append("   ${value.meta}", com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        addPopupMenuListener(object : javax.swing.event.PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: javax.swing.event.PopupMenuEvent?) = loadSessions()
            override fun popupMenuWillBecomeInvisible(e: javax.swing.event.PopupMenuEvent?) = Unit
            override fun popupMenuCanceled(e: javax.swing.event.PopupMenuEvent?) = Unit
        })
    }
    private var sessionsFor: Pair<String?, String?>? = null

    /** The app's archived sessions, read off the UI thread, into the switch (the choice kept). */
    private fun loadSessions() {
        val client = context.client ?: return
        val st = context.state.value
        sessionsFor = st.device to st.app
        scope.launch {
            val items = runCatching {
                val listing = io.yamsergey.dta.protocol.rpc.DtaJson.decodeFromJsonElement(
                    io.yamsergey.dta.protocol.network.ArchiveListing.serializer(), client.call("archive.list").result,
                )
                io.yamsergey.dta.present.network.ArchiveSessions.of(listing, st.device, st.app, System.currentTimeMillis())
            }.getOrNull() ?: return@launch
            javax.swing.SwingUtilities.invokeLater {
                val keep = (source.selectedItem as? SourceChoice)?.scope
                updatingSources = true
                try {
                    sources.removeAllElements()
                    sources.addElement(liveChoice)
                    items.forEach { sources.addElement(SourceChoice(it.scope, it.label, it.meta, it.enabled)) }
                    selectSource(keep)
                } finally {
                    updatingSources = false
                }
            }
        }
    }
    private var updatingSources = false

    /** Selects the choice of [scope]; one set elsewhere (the Daemon tab) that the list lacks is added. */
    private fun selectSource(scope: String?) {
        val at = (0 until sources.size).firstOrNull { sources.getElementAt(it).scope == scope }
        if (at != null) { source.selectedIndex = at; return }
        val added = SourceChoice(scope, "Archived: $scope")
        sources.addElement(added)
        source.selectedItem = added
    }
    private val newBar = ActionLink("") { network.list(LiveEvent.JumpToEnd); scrollToEdge() }
    private val empty = JBLabel("", javax.swing.SwingConstants.CENTER).apply { foreground = com.intellij.ui.JBColor.GRAY }
    /** Feature 026: copy actions. */
    private val copyMenu = io.yamsergey.dta.studio.network.EntryCopyMenu(project, scope, { context.client }) {
        JsonObject(listOfNotNull(last.device?.let { "device" to JsonPrimitive(it) }, last.app?.let { "app" to JsonPrimitive(it) }).toMap())
    }
    private val detail = EntryDetailPane(project, scope, copyMenu, open = { id -> network.reveal(id) }, formatsChanged = { network.refresh() })
    // The list gets most of the width; the person's own split is remembered.
    private val splitter = JBSplitter(false, "dta.network.split", 0.68f)
    private var programmaticScroll = false
    private var scrollValue = 0
    private inner class Toggle(text: String, icon: javax.swing.Icon, private val enabled: () -> Boolean = { true }, private val on: () -> Boolean, private val set: (Boolean) -> Unit) : ToggleAction(text, null, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = on()
        override fun setSelected(e: AnActionEvent, state: Boolean) = set(state)
        override fun update(e: AnActionEvent) { super.update(e); e.presentation.isEnabled = enabled() }
    }

    private inner class Act(text: String, icon: javax.swing.Icon, private val enabled: () -> Boolean = { true }, private val hint: String? = null, private val run: () -> Unit) : com.intellij.openapi.project.DumbAwareAction(text, null, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) = run()
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = enabled()
            hint?.let { e.presentation.putClientProperty(com.intellij.openapi.actionSystem.ex.ActionUtil.SECONDARY_TEXT, it) }
        }
    }

    /** 072 (FR-013): Export HAR opens a menu: masked (the default), or with secrets, for the developer only. */
    private inner class ExportMenu : com.intellij.openapi.project.DumbAwareAction("Export HAR", "Export the list as a HAR file", AllIcons.ToolbarDecorator.Export) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = last.app != null }
        override fun actionPerformed(e: AnActionEvent) {
            val group = DefaultActionGroup(
                Act("Export HAR…", AllIcons.ToolbarDecorator.Export, hint = "secrets masked") { exportHar(unredacted = false) },
                Act("Export HAR with Secrets…", AllIcons.ToolbarDecorator.Export, hint = "for you only") { exportHar(unredacted = true) },
            )
            com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
                .createActionGroupPopup(null, group, e.dataContext, com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false)
                .showInBestPositionFor(e.dataContext)
        }
    }

    /** Feature 043: the list's actions in a vertical strip at its left, as the IDE's Logcat and Run views have them. */
    private val strip = ActionManager.getInstance().createActionToolbar("DtaNetworkList", DefaultActionGroup(
        Act("Clear History…", AllIcons.Actions.GC) { clear() },
        Act("Network History…", AllIcons.Vcs.History) { openHistory() },
        Toggle("Pause", AllIcons.Actions.Pause, enabled = { last.archive == null }, on = { last.list.paused }) { network.list(if (it) LiveEvent.Pause else LiveEvent.Resume) },
        // "Scroll to the newest", as the IDE's Build and Run views have it: shows and sets following.
        Toggle("Scroll to the Newest", AllIcons.RunConfigurations.Scroll_down, on = { last.list.following }) { state ->
            if (state) { network.list(LiveEvent.JumpToEnd); scrollToEdge() } else network.list(LiveEvent.ScrolledAway)
        },
        Act("Previous Hit (Shift+Enter, Shift+F3)", AllIcons.Actions.PreviousOccurence, enabled = { last.applied.finds && last.hits.total > 0 }) { stepHit(forward = false) },
        Act("Next Hit (Enter, F3)", AllIcons.Actions.NextOccurence, enabled = { last.applied.finds && last.hits.total > 0 }) { stepHit(forward = true) },
        com.intellij.openapi.actionSystem.Separator.getInstance(),
        ExportMenu(),
    ), false).apply {
        targetComponent = this@NetworkPanel
        component.isOpaque = false
        component.border = JBUI.Borders.emptyRight(2)
    }
    private var last = NetworkViewState()

    init {
        // Switching to the tab or activating the window focuses the list, never the query line:
        // the line is focused only when the developer puts the caret there (063).
        isFocusTraversalPolicyProvider = true
        focusTraversalPolicy = object : javax.swing.LayoutFocusTraversalPolicy() {
            override fun getDefaultComponent(root: java.awt.Container?): java.awt.Component = table
        }
        val kindSwitch = panel {
            row {
                kinds = segmentedButton(KindChoice.entries) { text = it.label }
                    .whenItemSelectedFromUi { choice -> if (last.kind != choice) { typing.stop(); network.setKind(choice) } }
            }
        }.apply { isOpaque = false }
        kinds.selectedItem = KindChoice.ALL
        // Row 1: the query line with its hit counter; row 2: the saved filters; row 3: what the list holds.
        val queryRow = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            border = JBUI.Borders.empty(4, 8, 2, 8)
            add(query, BorderLayout.CENTER)
            add(hitLabel, BorderLayout.EAST)
        }
        val chipRow = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(0, 4)
            add(chips, BorderLayout.CENTER)
        }
        val viewRow = JPanel(com.intellij.util.ui.WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply {
            add(kindSwitch)
            add(source)
            add(archiveBox)
            // D-024: the network script in force, after the source; wraps to the next row when narrow.
            add(scriptControl)
        }
        archiveBox.addActionListener { if (archiveBox.isSelected != last.archiveOn) network.setArchiveOn(archiveBox.isSelected) }
        val toolbar = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            listOf(queryRow, problemRow, runAllHint, chipRow, viewRow, restartBanner).forEach { it.alignmentX = LEFT_ALIGNMENT; add(it) }
        }
        bindQuery()
        val master = JPanel(BorderLayout()).apply {
            add(JPanel(BorderLayout()).apply { add(toolbar, BorderLayout.NORTH); add(statements, BorderLayout.CENTER); add(webStatus, BorderLayout.SOUTH) }, BorderLayout.NORTH)
            add(strip.component, BorderLayout.WEST)
            add(JPanel(BorderLayout()).apply { add(scroll, BorderLayout.CENTER); add(olderBottom, BorderLayout.SOUTH) }, BorderLayout.CENTER)
            // Feature 043: what the list shows and what the filters hid sit under it.
            // In one line when it fits, else "N new below" under the caption: never over it.
            add(JPanel(LineOrStack(JBUI.scale(12))).apply {
                border = JBUI.Borders.empty(2, 6, 0, 4)
                add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { isOpaque = false; add(spinner); add(caption); add(captionLink) })
                add(empty)
                add(newBar)
            }, BorderLayout.SOUTH)
        }
        splitter.firstComponent = master
        splitter.secondComponent = detail
        add(splitter, BorderLayout.CENTER)
        // Detail to the right when wide, below when narrow (patterns.md).
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) { splitter.orientation = width < JBUI.scale(NARROW) }
        })
        // D-024: a narrow list (not the whole tab: the split decides the list's width) drops Time and the host.
        scroll.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) { narrowColumns(scroll.width < JBUI.scale(NARROW_LIST)) }
        })

        source.addActionListener {
            (source.selectedItem as? SourceChoice)?.let { if (source.prototypeDisplayValue != it) source.prototypeDisplayValue = it }
            if (updatingSources) return@addActionListener
            val chosen = source.selectedItem as? SourceChoice ?: return@addActionListener
            if (!chosen.enabled) { selectSource(last.archive); return@addActionListener }
            if (chosen.scope != last.archive) network.setArchive(chosen.scope)
        }
        table.selectionModel.addListSelectionListener { e ->
            if (e.valueIsAdjusting || model.updating) return@addListSelectionListener
            // Feature 071: a separator row is not an entry.
            val row = table.selectedRow.takeIf { it >= 0 }?.let { model.rowAt(it) }?.takeUnless { it.id.startsWith(SEPARATOR) }
            network.list(LiveEvent.Selected(row?.id))
        }
        // Only the person's own scrolling changes following: away from the newest edge stops it, back to it resumes.
        // Rows arriving grow the list without moving the view; DTA's own scrolling and reloads are flagged.
        scroll.verticalScrollBar.addAdjustmentListener { e ->
            val moved = e.value != scrollValue
            scrollValue = e.value
            if (programmaticScroll) return@addAdjustmentListener
            Scrolling.event(last.list.following, moved, atEdge())?.let { network.list(it) }
            // D-022: scrolled to the oldest row, the next older page loads.
            if (moved && last.list.older != null && !last.loadingOlder && model.sort == null && atOldestEdge()) network.loadOlder()
        }
        setOrder(ListOrder.of(PropertiesComponent.getInstance().getValue(ORDER_KEY)))
        Keyboard.bind(this, ViewKey.START) { if (model.rowCount > 0) table.changeSelection(0, 0, false, false) }
        Keyboard.bind(this, ViewKey.END) { network.list(LiveEvent.JumpToEnd); table.clearSelection(); scrollToEdge() }
        Keyboard.bind(this, ViewKey.PAUSE) { network.list(if (last.list.paused) LiveEvent.Resume else LiveEvent.Pause) }
        Keyboard.bind(this, ViewKey.FILTERS) { focusQuery() }
        Keyboard.bind(this, ViewKey.SEARCH) { focusQuery() }
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
        // Feature 071 (board 3): with Archive on, earlier runs and archived history under their separators.
        val shownRows = if (model.sort != null) s.list.rows else io.yamsergey.dta.present.network.RunSeparators.insert(
            s.list.rows, { r -> s.epochOf[r.id] ?: (r.entry["runId"] as? JsonPrimitive)?.content.orEmpty() }, s.scope, newestFirst = model.order == ListOrder.NEWEST_FIRST,
        ) { sep -> Row("$SEPARATOR${sep.group}", 0, JsonObject(mapOf("separator" to JsonPrimitive(sep.text)))) }
        val (added, prepended) = try { model.update(shownRows) } finally { scroll.validate(); programmaticScroll = false }
        // Keep the selection on its row by id, whatever moved around it.
        model.updating = true
        try { TableSelection.follow(table.selectionModel, s.list.selectedId?.let(model::indexOf)) } finally { model.updating = false }
        if (s.list.following && model.sort == null) scrollToEdge()
        // Rows added above what the person is reading push it down; scroll by as much so nothing jumps.
        else if (added > 0 && model.order == ListOrder.NEWEST_FIRST) keepInPlace(added)
        // D-022: an older page above the rows read: the view moves by as much, the reading position stays.
        if (prepended > 0 && model.order == ListOrder.OLDEST_FIRST && model.sort == null) keepInPlace(prepended)
        val more = s.list.older != null && model.sort == null
        val filteredNow = s.applied.text.isNotBlank() || s.kind != KindChoice.ALL || s.savedApplied?.let { !it.ignored && it.words.isNotEmpty() } == true
        val olderWords = io.yamsergey.dta.present.network.SavedChip.older(s.list.shown, s.list.history, filteredNow)
        olderTop.show(more && model.order == ListOrder.OLDEST_FIRST, s.loadingOlder, "↑", olderWords)
        olderBottom.show(more && model.order == ListOrder.NEWEST_FIRST, s.loadingOlder, "↓", olderWords)
        if (s.list.following != previous.list.following || s.list.paused != previous.list.paused ||
            s.hits.total != previous.hits.total || s.applied.finds != previous.applied.finds || s.archive != previous.archive) strip.updateActionsAsync()

        // Set from the archive browser too: the switch follows the state.
        if ((source.selectedItem as? SourceChoice)?.scope != s.archive) {
            updatingSources = true
            try { selectSource(s.archive) } finally { updatingSources = false }
        }
        // Read ahead for the app shown (the open list does not grow while open); refreshed again on each opening.
        context.state.value.let { st -> if (st.app != null && sessionsFor != st.device to st.app) loadSessions() }

        if (kinds.selectedItem != s.kind) kinds.selectedItem = s.kind
        scriptControl.show(s.script)
        // The line is the person's while they type; set from the state only when changed elsewhere (a chip, the switch).
        if (s.line.text != query.text && !typing.isRunning && s.line.text != previous.line.text) query.text = s.line.text
        val problems = s.line.parsed.problems.takeIf { s.line.text == query.text }.orEmpty()
        query.putClientProperty("JComponent.outline", if (problems.isEmpty()) null else "error")
        if (s.saved.saved != chipsShown) {
            chipsShown = s.saved.saved
            chips.removeAll()
            s.saved.saved.forEach { word ->
                chips.add(io.yamsergey.dta.studio.network.FilterChip(word, edit = { typing.stop(); network.editSaved(word); focusQuery() }, remove = { network.removeSaved(word) }))
            }
            chips.isVisible = s.saved.saved.isNotEmpty()
        }
        // D-021: the problem under the field (wrapping), never beside it: the field keeps its width.
        problemText.text = problems.joinToString("\n") { it.message }
        keptText.text = "The list keeps the last valid query: ${s.applied.text.trim().ifEmpty { "none (everything)" }}"
        problemRow.isVisible = problems.isNotEmpty()
        hitLabel.text = when {
            !s.applied.finds -> ""
            s.searching && s.hits.total == 0 -> "Searching…"
            else -> s.hits.label
        }
        // The caption keeps its width while traffic arrives (024); it starts over on new filters or a cleared list.
        if (s.applied != previous.applied || s.list.history < previous.list.history) caption.reset()
        // Feature 069: rows shown and the scope's size (071); matches are not counted.
        val filtered = s.applied.text.isNotBlank() || s.kind != io.yamsergey.dta.present.network.KindChoice.ALL
        val words = if (s.scope == null) null else io.yamsergey.dta.present.network.ScopeCaption.of(s.scope, s.list.shown, filtered, s.find, s.savedApplied, s.findError)
        caption.text = words?.text ?: io.yamsergey.dta.present.network.SavedChip.caption(s.list.shown, s.list.history, s.savedApplied, filtered, more = s.list.older != null)
        // D-083: no daemon, no run: no count of one ("0 entries in this run").
        if (!s.online) caption.text = ""
        if (s.findHeld && words != null) caption.text = words.text + " · more as you scroll"
        spinner.isVisible = words?.searching == true
        captionAction = words?.action?.takeIf { s.online }?.takeUnless { it == io.yamsergey.dta.present.network.ScopeCaption.Action.INCLUDE_ARCHIVE && (s.archiveOn || s.archive != null) }
        captionLink.text = captionAction?.let { io.yamsergey.dta.present.network.ScopeCaption.label(it) }.orEmpty()
        captionLink.isVisible = captionAction != null
        if (archiveBox.isSelected != s.archiveOn) archiveBox.isSelected = s.archiveOn
        archiveBox.isEnabled = s.archive == null
        runAllHint.isVisible = !s.archiveOn && s.archive == null && s.applied.parsed.filter.runs == io.yamsergey.dta.protocol.network.RunScope.ALL && s.applied.parsed.problems.isEmpty()
        restartBanner.show(s.restart?.let { io.yamsergey.dta.present.context.Banner(io.yamsergey.dta.present.StatementKind.PROCESS_ENDED, it, "Include archive".takeUnless { s.archiveOn }) })
        if (s.hits != previous.hits) table.repaint()
        statements.removeAll()
        s.saved.problem?.let { statements.add(JBLabel(it).apply { foreground = DtaColors.text(io.yamsergey.dta.present.StatementKind.DEGRADED) }) }
        (io.yamsergey.dta.present.Statements.forNetworkTab(s.statements) + s.searchStatements + listOfNotNull(
            if (s.list.paused) io.yamsergey.dta.present.Statement(io.yamsergey.dta.present.StatementKind.PENDING, word = "paused", detail = "paused: ${s.list.backlog} waiting; capture goes on") else null,
        )).forEach { statements.add(StatementMark(it)) }
        s.error?.let { statements.add(JBLabel(it).apply { foreground = DtaColors.of("status-server") }) }
        newBar.text = if (s.list.newCount > 0) "${s.list.newCount} new ${if (model.order == ListOrder.NEWEST_FIRST) "above" else "below"} — G to follow" else ""
        newBar.isVisible = s.list.newCount > 0
        empty.text = when {
            s.app == null || !s.online -> ""
            s.loading -> "Loading…"
            s.list.rows.isEmpty() && s.saved.saved.isEmpty() && s.applied.text.isBlank() && s.kind == io.yamsergey.dta.present.network.KindChoice.ALL -> "No traffic yet. Requests appear here as the app makes them."
            s.list.rows.isEmpty() -> "Nothing matches these filters."
            else -> ""
        }
        val target = JsonObject(listOfNotNull(s.device?.let { "device" to JsonPrimitive(it) }, s.app?.let { "app" to JsonPrimitive(it) }).toMap())
        val selectedRow = s.list.selectedId?.let(model::indexOf)
        // Reached by a link (025): bring the row into view; one the filters hide is still shown, saying so.
        if (selectedRow != null && s.list.selectedId != previous.list.selectedId) table.scrollRectToVisible(table.getCellRect(selectedRow, 0, true))
        val unlisted = s.list.selectedId != null && selectedRow == null && !s.list.selectedGone && !s.loading
        // D-034: the search words and the body filters are marked, each where it applies.
        val marks = if (s.applied.finds) s.applied.marks else io.yamsergey.dta.present.network.SearchMarks()
        // Feature 041: any selected row that matched opens on its hit, saying where the words (or the body filter) are.
        val rowHits = s.list.selectedId?.takeIf { s.applied.finds }?.let { s.hits.hitsOf(it) }.orEmpty()
        val hitTab = rowHits.takeIf { it.isNotEmpty() }?.let { s.hits.tabOf(it) }
        detail.show(context.client, target, s.list.selectedId, s.list.selectedGone, unlisted, marks, hitTab, rowHits, s.selectedRevision)
        revalidate()
        repaint()
    }

    /** D-022: whether the view is at the edge where the oldest rows are. */
    private fun atOldestEdge(): Boolean {
        val bar = scroll.verticalScrollBar
        return if (model.order == ListOrder.OLDEST_FIRST) bar.value <= JBUI.scale(4)
        else bar.value + bar.visibleAmount >= bar.maximum - JBUI.scale(4)
    }

    /** D-022: "↑ Load 500 older" and what the history holds beyond the rows; hidden when everything is loaded. */
    private inner class OlderRow : JPanel(BorderLayout(JBUI.scale(12), 0)) {
        private val link = ActionLink("") { network.loadOlder() }.apply { toolTipText = "Also when you scroll to the oldest row" }
        // Cut with … when the list is narrow; the link always shows.
        private val note = JBLabel("").apply { foreground = com.intellij.ui.JBColor.GRAY }

        init {
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(com.intellij.ui.JBColor.border()), JBUI.Borders.empty(3, 8))
            add(link, BorderLayout.WEST)
            add(note, BorderLayout.CENTER)
            isVisible = false
        }

        fun show(visible: Boolean, loading: Boolean, arrow: String, words: String) {
            isVisible = visible
            if (!visible) return
            link.text = if (loading) "Loading older…" else "$arrow Load ${NetworkController.PAGE} older"
            link.isEnabled = !loading
            note.text = words
        }
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

    /** Feature 043, a click on a header: Time is the list's order, any other column sorts the loaded rows; again reverses. */
    private fun sortBy(column: Int) {
        if (column == 0) {
            if (model.sort != null) model.setSort(null) else setOrder(model.order.toggled())
        } else {
            model.setSort(if (model.sort?.column == column) model.sort!!.copy(ascending = !model.sort!!.ascending) else io.yamsergey.dta.present.network.RowSort(column))
        }
        table.tableHeader.repaint()
        show(last)
    }

    /** Shows newest on top or at the bottom, remembered across sessions; the selection and following stay. */
    private fun setOrder(order: ListOrder) {
        PropertiesComponent.getInstance().setValue(ORDER_KEY, order.name, ListOrder.DEFAULT.name)
        network.setHitsNewestFirst(order == ListOrder.NEWEST_FIRST)
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

    /** Feature 026: what the list shows (filters, live or archive) as a HAR file, masked or, for the developer, with secrets (072). */
    private fun exportHar(unredacted: Boolean) {
        if (last.app == null) return
        val descriptor = com.intellij.openapi.fileChooser.FileSaverDescriptor("Export HAR", "The entries the list shows, with bodies and WebSocket messages", "har")
        val name = "${last.app}-${java.time.LocalDateTime.now().withNano(0).toString().replace(':', '-')}.har"
        val file = com.intellij.openapi.fileChooser.FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save(name)?.file ?: return
        com.intellij.openapi.wm.StatusBar.Info.set("Exporting HAR${if (unredacted) " with secrets" else ""} to ${file.path}…", project)
        network.export(file.absolutePath, unredacted) { result ->
            result.onSuccess { o ->
                fun n(k: String) = (o[k] as? JsonPrimitive)?.content ?: "0"
                val omitted = n("bodiesOmitted").takeIf { it != "0" }?.let { " $it bodies left out (the file says why)." } ?: ""
                com.intellij.openapi.wm.StatusBar.Info.set("Exported${if (unredacted) " with secrets" else " (secrets masked)"} ${n("entries")} entries, ${n("webSocketMessages")} WebSocket messages, ${n("bodies")} bodies to ${file.path}.$omitted", project)
            }.onFailure { e -> Messages.showErrorDialog(project, e.message ?: e.javaClass.simpleName, "Export HAR") }
        }
    }

    private fun copySelected() {
        val row = table.selectedRow.takeIf { it >= 0 }?.let { model.rowAt(it) } ?: return
        val url = (row.entry["url"] as? JsonPrimitive)?.content ?: row.id
        CopyPasteManager.getInstance().setContents(StringSelection(url))
    }

    /**
     * Feature 040/063: typing runs the line (debounced); the IDE's completion offers the words (it opens on typing
     * or when asked, never on text set here). With no completion list open, Enter saves the filters or steps
     * to the next hit, Shift+Enter to the previous, Down lists every key, Esc goes back to the list.
     */
    private fun bindQuery() {
        query.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                refreshCompletionInput()
                if (query.text != last.line.text) typing.restart()
            }
        })
        query.addSettingsProvider { editor ->
            // A press in the line is the developer putting the caret there; the editor asks for focus
            // itself on a press, so the focus event does not carry the mouse as its cause.
            // The offer waits for the release: the press places the caret, which would close it.
            editor.contentComponent.addMouseListener(object : java.awt.event.MouseAdapter() {
                private var pressedOutside = false
                override fun mousePressed(e: java.awt.event.MouseEvent) {
                    pressedOutside = !editor.contentComponent.hasFocus()
                }
                override fun mouseReleased(e: java.awt.event.MouseEvent) {
                    if (!pressedOutside) return
                    pressedOutside = false
                    if (query.text.isNotEmpty() || completionShown()) return
                    javax.swing.SwingUtilities.invokeLater {
                        refreshCompletionInput()
                        QueryCompletion.offer(project, editor)
                    }
                }
            })
            // Feature 043: focusing the empty line offers the app's recent filters (and every key).
            editor.contentComponent.addFocusListener(object : java.awt.event.FocusAdapter() {
                override fun focusGained(e: java.awt.event.FocusEvent) {
                    val asked = focusAsked
                    focusAsked = false
                    if (!io.yamsergey.dta.studio.network.QueryFocus.offers(e.cause, asked, query.text.isEmpty())) return
                    refreshCompletionInput()
                    QueryCompletion.offer(project, editor)
                }
            })
        }
        fun on(stroke: String, action: () -> Unit) {
            object : com.intellij.openapi.project.DumbAwareAction() {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                // An open completion list keeps the IDE's own meaning of the key.
                override fun update(e: AnActionEvent) { e.presentation.isEnabled = !completionShown() }
                override fun actionPerformed(e: AnActionEvent) = action()
            }.registerCustomShortcutSet(com.intellij.openapi.actionSystem.CustomShortcutSet.fromString(stroke), query)
        }
        on("ENTER") {
            typing.stop()
            if (network.commitLine(query.text)) {
                query.text = network.state.value.line.text
                return@on
            }
            network.setQuery(query.text)
            stepHit(forward = true)
        }
        on("shift ENTER") { stepHit(forward = false) }
        on("DOWN") {
            val editor = query.editor ?: return@on
            refreshCompletionInput()
            com.intellij.codeInsight.completion.CodeCompletionHandlerBase(com.intellij.codeInsight.completion.CompletionType.BASIC).invokeCompletion(project, editor)
        }
        on("ESCAPE") { table.requestFocusInWindow() }
    }

    private fun completionShown() = query.editor?.let { com.intellij.codeInsight.lookup.LookupManager.getActiveLookup(it) } != null

    /** What completion reads: hosts, statuses and methods seen in the list, the app's recent filters, the kind switch. */
    private fun refreshCompletionInput() {
        completionInput = QueryCompletion.Input(QueryLineModel.seen(model.views()), last.saved.recent, kinds.selectedItem ?: KindChoice.ALL, last.saved.saved)
    }

    /** The developer moved the caret to the line (a shortcut, a chip): the empty line may offer filters. */
    @Volatile private var focusAsked = false

    private fun focusQuery() {
        // Already there: no focus event comes, and none must be offered later by itself.
        focusAsked = !com.intellij.util.ui.UIUtil.isFocusAncestor(query)
        com.intellij.openapi.wm.IdeFocusManager.getInstance(project).requestFocus(query, true)
    }

    /** Down the list = the next hit, whichever order the list shows. */
    private fun stepHit(forward: Boolean) {
        if (!last.applied.finds) return
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
        var sort: io.yamsergey.dta.present.network.RowSort? = null
            private set
        private var sorted: List<Row> = emptyList()

        fun setSort(next: io.yamsergey.dta.present.network.RowSort?) {
            sort = next
            resort()
            updating = true
            fireTableDataChanged()
            updating = false
        }

        private fun resort() {
            val s = sort ?: return
            val by = s.comparator()
            sorted = rows.sortedWith { a, b -> by.compare(view(a), view(b)) }
        }

        fun sortIcon(column: Int): javax.swing.Icon? {
            val up = AllIcons.General.ArrowUp
            val down = AllIcons.General.ArrowDown
            return when {
                sort?.column == column -> if (sort!!.ascending) up else down
                sort == null && column == 0 -> if (order == ListOrder.OLDEST_FIRST) up else down
                else -> null
            }
        }

        fun view(row: Row): EntryRow = cache.getOrPut(row) { EntryRows.of(row.entry) }

        /** The newest rows as shown, for the query line's value suggestions (hosts, statuses, methods). */
        fun views(): List<EntryRow> = rows.takeLast(500).filterNot { it.id.startsWith(SEPARATOR) }.map(::view)

        fun rowAt(shown: Int): Row = if (sort != null) sorted[shown] else rows[order.shown(shown, rows.size)]

        fun indexOf(id: String): Int? =
            if (sort != null) sorted.indexOfFirst { it.id == id }.takeIf { it >= 0 }
            else rows.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { order.shown(it, rows.size) }

        fun reorder(next: ListOrder) {
            order = next
            updating = true
            fireTableDataChanged()
            updating = false
        }

        /**
         * Appends, updates in place, or reloads, so rows never jump (patterns.md live list); returns how many
         * were appended and how many older rows came before the ones there (D-022).
         */
        fun update(next: List<Row>): Pair<Int, Int> {
            val before = rows
            if (next === before) return 0 to 0
            updating = true
            rows = next
            if (cache.size > 20_000) cache.clear()
            if (sort != null) {
                resort()
                fireTableDataChanged()
                updating = false
                return 0 to 0
            }
            var added = 0
            var prepended = 0
            val shift = next.size - before.size
            when {
                next.size >= before.size && before.indices.all { before[it].id == next[it].id } -> {
                    added = next.size - before.size
                    if (added > 0) order.appended(before.size, next.size).let { fireTableRowsInserted(it.first, it.last) }
                    before.indices.filter { before[it] != next[it] }.forEach { val i = order.shown(it, next.size); fireTableRowsUpdated(i, i) }
                }
                // D-022: an older page joined before the rows there (which stay as they were).
                shift > 0 && before.isNotEmpty() && before.indices.all { before[it].id == next[it + shift].id } -> {
                    prepended = shift
                    val range = if (order == ListOrder.OLDEST_FIRST) 0 until shift else before.size until next.size
                    fireTableRowsInserted(range.first, range.last)
                    before.indices.filter { before[it] != next[it + shift] }.forEach { val i = order.shown(it + shift, next.size); fireTableRowsUpdated(i, i) }
                }
                else -> fireTableDataChanged()
            }
            updating = false
            return added to prepended
        }

        override fun getRowCount() = rows.size
        override fun getColumnCount() = COLUMNS.size
        override fun getColumnName(column: Int) = COLUMNS[column].title
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = rowAt(rowIndex).let { r ->
            if (r.id.startsWith(SEPARATOR)) Separator((r.entry["separator"] as JsonPrimitive).content) else view(r)
        }
    }

    /** D-024: the Request cell draws itself (label, host muted, path cut before the scripted mark); the others are coloured text. */
    private inner class CellRenderer : javax.swing.table.TableCellRenderer {
        private val text = RowRenderer()
        private val request = RequestCell()

        override fun getTableCellRendererComponent(table: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, column: Int): java.awt.Component {
            val r = value as? EntryRow
            if (r == null || table.convertColumnIndexToModel(column) != 1) return text.getTableCellRendererComponent(table, value, selected, focus, row, column)
            request.background = when {
                selected -> com.intellij.ui.render.RenderingUtil.getBackground(table, true)
                last.applied.finds && last.hits.current?.id == r.id -> io.yamsergey.dta.studio.ui.SEARCH_MARK
                else -> table.background
            }
            request.font = table.font
            val fg = com.intellij.ui.render.RenderingUtil.getForeground(table, selected)
            // Narrow, the path alone (the host is in the tooltip and the detail).
            request.set(r.label, DtaColors.of(r.kind.token), r.host.takeUnless { narrow }, r.path, fg, r.scripted?.let { "${it.kind.glyph} ${it.word}" }, DtaColors.of("intervened"))
            // Every note (binary, redacted, decoded, where web traffic came from…) in the tooltip; the detail states them too.
            request.toolTipText = (listOf("${r.label} ${r.host.orEmpty()}${r.path}") + r.marks.shown.map { it.sentence }).joinToString("<br>", "<html>", "</html>") { it.replace("&", "&amp;").replace("<", "&lt;") }
            return request
        }
    }

    private inner class RowRenderer : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            // One renderer draws every cell: a tooltip set for one cell must not show on the next.
            toolTipText = null
            // Feature 071 (board 3): a separator's words where they fit — what, when, how many (a cell cannot span the row).
            if (value is Separator) {
                val parts = value.text.split(" · ")
                val shown = when (column) { 0 -> parts.getOrNull(0); 1 -> parts.getOrNull(1)?.takeIf { parts.size > 2 }; 3 -> parts.lastOrNull()?.takeIf { parts.size > 1 }; else -> null }
                shown?.let { append(it, SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES) }
                toolTipText = value.text
                return
            }
            val r = value as? EntryRow ?: return
            // Feature 040: rows holding the search words are tinted; the current hit more.
            // Feature 041: with only the matches listed every row is one: only the current hit stands out.
            if (!selected && last.applied.finds && last.hits.hitsOf(r.id) != null) {
                if (last.hits.current?.id == r.id) background = io.yamsergey.dta.studio.ui.SEARCH_MARK
            }
            val muted = SimpleTextAttributes.GRAYED_ATTRIBUTES
            when (table.convertColumnIndexToModel(column)) {
                0 -> append(r.time, muted)
                2 -> {
                    val st = r.outcome.statement
                    when {
                        st != null -> append("${st.kind.glyph} ${st.word}", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, DtaColors.text(st.kind)))
                        r.outcome.token != null -> append(r.outcome.text, SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, DtaColors.of(r.outcome.token!!)))
                        else -> append(r.outcome.text)
                    }
                }
                // D-024: narrow, the size alone (a failed call without one keeps its duration).
                3 -> append(if (narrow) r.size ?: r.timing else r.timing, muted)
            }
        }
    }

    /** D-024: whether the list is narrow (Time hidden, Size alone). */
    private var narrow = false
    private val timeColumn by lazy { table.columnModel.getColumn(0) }

    private fun narrowColumns(on: Boolean) {
        if (on == narrow) return
        narrow = on
        val columns = table.columnModel
        if (on) columns.removeColumn(timeColumn) else { columns.addColumn(timeColumn); columns.moveColumn(columns.columnCount - 1, 0) }
        val size = (0 until columns.columnCount).map { columns.getColumn(it) }.first { it.modelIndex == 3 }
        size.headerValue = if (on) "Size" else COLUMNS[3].title
        fitColumn(table, size, if (on) "18.4 KB" else COLUMNS[3].sample!!, size.headerValue as String)
        table.tableHeader.repaint()
        table.repaint()
    }

    /** D-024: the script in force, as a closed switch: "✎ Script: auth.js ▾", in the script's colour; a click lists what it does. */
    private inner class ScriptControl : JBLabel() {
        private var script: io.yamsergey.dta.protocol.network.ScriptStatus? = null

        init {
            foreground = DtaColors.of("intervened")
            icon = AllIcons.General.ChevronDown
            horizontalTextPosition = javax.swing.SwingConstants.LEADING
            iconTextGap = JBUI.scale(4)
            border = JBUI.Borders.compound(com.intellij.ui.RoundedLineBorder(com.intellij.ui.JBColor.border(), JBUI.scale(6)), JBUI.Borders.empty(3, 7))
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            toolTipText = "The network script in force: what it changes, Edit, Stop, the Script tab"
            isVisible = false
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) = popup()
            })
        }

        fun show(next: io.yamsergey.dta.protocol.network.ScriptStatus?) {
            if (next == script) return
            script = next
            isVisible = next != null
            text = next?.let { "✎ Script: ${io.yamsergey.dta.studio.network.ScriptNames.name(it.id)}" } ?: ""
        }

        private fun popup() {
            val st = script ?: return
            val view = io.yamsergey.dta.present.script.ScriptView.from(io.yamsergey.dta.protocol.rpc.DtaJson.encodeToJsonElement(io.yamsergey.dta.protocol.network.ScriptStatus.serializer(), st) as JsonObject)
            val path = io.yamsergey.dta.studio.network.ScriptNames.path(st.id)
            lateinit var popup: com.intellij.openapi.ui.popup.JBPopup
            val content = JPanel().apply {
                layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
                border = JBUI.Borders.empty(8, 12)
                fun line(c: javax.swing.JComponent) { c.alignmentX = LEFT_ALIGNMENT; add(c) }
                line(JBLabel("✎ ${io.yamsergey.dta.studio.network.ScriptNames.name(st.id)}").apply { foreground = DtaColors.of("intervened"); font = font.deriveFont(java.awt.Font.BOLD) })
                line(JBLabel(view.headline).apply { foreground = com.intellij.ui.JBColor.GRAY })
                view.handlers?.let { line(JBLabel("Handles: $it")) }
                view.counters?.let { line(JBLabel("This run: $it")) }
                view.statements.forEach { line(StatementMark(it)) }
                line(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    border = JBUI.Borders.emptyTop(8)
                    add(ActionLink("Edit") {
                        popup.cancel()
                        val file = path?.let { com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(it) }
                        if (file != null) com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(file, true) else openScript()
                    }.apply { toolTipText = if (path != null) "Open $path" else "Not installed from a file here: the Script tab" })
                    add(javax.swing.Box.createHorizontalStrut(JBUI.scale(16)))
                    add(ActionLink("Stop") { popup.cancel(); network.stopScript() }.apply { toolTipText = "Clear the app's network script: traffic goes unchanged" })
                    add(javax.swing.Box.createHorizontalStrut(JBUI.scale(16)))
                    add(ActionLink("Script tab") { popup.cancel(); openScript() })
                })
            }
            popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createComponentPopupBuilder(content, null)
                .setRequestFocus(true).setCancelOnClickOutside(true).createPopup()
            popup.showUnderneathOf(this)
        }
    }

    /** Feature 071: a separator row's cell. */
    private class Separator(val text: String)

    private companion object {
        /** Feature 071: ids of separator rows (never an entry's). */
        const val SEPARATOR = "sep:"
        const val ORDER_KEY = "dta.network.order"
        /** Below this the detail goes under the list; a 900 px tool window still has them side by side. */
        const val NARROW = 860
        const val LIVE = "Live"

        /** Below this the list drops Time and shows Size alone (D-024). */
        const val NARROW_LIST = 480

        /** [sample] is the widest content the column shows; null = flexible, takes what is left. D-024: kind and method join the URL. */
        val COLUMNS = listOf(
            Column("Time", "00:00:00.000"),
            Column("Request", null, "The method or socket direction, then host and path; ✎ scripted when the network script changed it (every note in the tooltip)"),
            Column("Status", "101 · open"), Column("Duration · Size", "1.3 s · 18.4 KB"),
        )

        /** Fixed columns as wide as their widest content (bold, as statuses are drawn); Request takes the rest. */
        fun sizeColumns(table: JTable) {
            COLUMNS.forEachIndexed { i, c ->
                val column = table.columnModel.getColumn(i)
                if (c.sample == null) column.preferredWidth = JBUI.scale(420) else fitColumn(table, column, c.sample, c.title)
            }
        }

        /** As wide as [sample] or [title], never less: sizes are never cut. */
        fun fitColumn(table: JTable, column: javax.swing.table.TableColumn, sample: String, title: String) {
            val bold = table.getFontMetrics(table.font.deriveFont(java.awt.Font.BOLD))
            val header = table.tableHeader.getFontMetrics(table.tableHeader.font)
            val width = maxOf(bold.stringWidth(sample), header.stringWidth(title) + JBUI.scale(16)) + JBUI.scale(16)
            // The maximum first: Swing clamps a minimum to the old maximum (narrow → wide).
            column.maxWidth = width + JBUI.scale(24)
            column.minWidth = width
            column.preferredWidth = width
        }
    }
}

/**
 * D-024: the Request cell — the method or socket direction coloured, the host muted, the path; the
 * URL is cut with … before the scripted mark at the cell's end, so the mark always shows.
 */
private class RequestCell : javax.swing.JComponent() {
    private var label = ""
    private var labelColor: java.awt.Color = com.intellij.ui.JBColor.foreground()
    private var host: String? = null
    private var path = ""
    private var fg: java.awt.Color = com.intellij.ui.JBColor.foreground()
    private var mark: String? = null
    private var markColor: java.awt.Color = com.intellij.ui.JBColor.foreground()

    init { isOpaque = true }

    fun set(label: String, labelColor: java.awt.Color, host: String?, path: String, fg: java.awt.Color, mark: String?, markColor: java.awt.Color) {
        this.label = label; this.labelColor = labelColor; this.host = host; this.path = path; this.fg = fg; this.mark = mark; this.markColor = markColor
    }

    override fun getPreferredSize(): java.awt.Dimension = java.awt.Dimension(JBUI.scale(200), getFontMetrics(font).height + JBUI.scale(2))

    override fun paintComponent(g: java.awt.Graphics) {
        val g2 = g.create() as java.awt.Graphics2D
        try {
            com.intellij.ide.ui.UISettings.setupAntialiasing(g2)
            g2.color = background
            g2.fillRect(0, 0, width, height)
            val gap = JBUI.scale(6)
            val pad = JBUI.scale(4)
            val bold = font.deriveFont(java.awt.Font.BOLD)
            val bm = g2.getFontMetrics(bold)
            val fm = g2.getFontMetrics(font)
            val base = (height - fm.height) / 2 + fm.ascent
            var x = pad
            g2.font = bold; g2.color = labelColor
            g2.drawString(label, x, base)
            x += bm.stringWidth(label) + gap
            // Too narrow for the mark beside the label: the tooltip says it.
            val m = mark?.takeIf { width - pad - x - fm.stringWidth(it) - gap >= fm.charWidth('m') * 3 }
            val markWidth = m?.let { fm.stringWidth(it) + gap } ?: 0
            val room = width - pad - markWidth - x
            // The host goes first, the path last: the path stays readable (the whole URL is in the tooltip).
            val (hostPart, pathPart) = io.yamsergey.dta.present.network.RequestFit.fit(host, path, room) { fm.stringWidth(it) }
            g2.font = font
            g2.color = com.intellij.ui.JBColor.GRAY
            g2.drawString(hostPart, x, base)
            g2.color = fg
            g2.drawString(pathPart, x + fm.stringWidth(hostPart), base)
            if (m != null) {
                g2.color = markColor
                g2.drawString(m, width - pad - fm.stringWidth(m), base)
            }
        } finally {
            g2.dispose()
        }
    }
}

/** The user's Basic Completion shortcut, as the placeholder names it. */
private fun completionShortcut(): String =
    com.intellij.openapi.keymap.KeymapUtil.getFirstKeyboardShortcutText(com.intellij.openapi.actionSystem.IdeActions.ACTION_CODE_COMPLETION).ifEmpty { "Ctrl+Space" }
