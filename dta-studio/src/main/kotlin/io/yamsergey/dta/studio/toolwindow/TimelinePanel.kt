package io.yamsergey.dta.studio.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.SegmentedButton
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.present.timeline.SequenceAction
import io.yamsergey.dta.present.timeline.SequenceView
import io.yamsergey.dta.present.timeline.TimelineChange
import io.yamsergey.dta.present.timeline.TimelineEventText
import io.yamsergey.dta.present.timeline.TimelineFilter
import io.yamsergey.dta.protocol.network.QueryLine
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import io.yamsergey.dta.protocol.ui.SourceState
import io.yamsergey.dta.studio.timeline.SequenceComponent
import io.yamsergey.dta.studio.timeline.LanesComponent
import io.yamsergey.dta.studio.timeline.TimelineController
import io.yamsergey.dta.studio.timeline.TimelineDetailPane
import io.yamsergey.dta.studio.timeline.TimelineTabState
import io.yamsergey.dta.studio.ui.DtaColors
import io.yamsergey.dta.studio.ui.Keyboard
import io.yamsergey.dta.studio.ui.ViewKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JButton
import javax.swing.JCheckBoxMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel

/**
 * The Timeline tab (feature 017, contracts/timeline-tab.md): the toolbar, the lanes, the dense
 * list and the selected event's detail. Everything shown comes from [TimelineController]'s
 * state and the present models; this class only binds them.
 */
class TimelinePanel(
    private val controller: TimelineController,
    scope: CoroutineScope,
    openNetwork: (String) -> Unit,
    openLayout: () -> Unit,
    openViewModels: () -> Unit,
) : JPanel(BorderLayout()) {
    private var state = TimelineTabState()
    private val lanes = LanesComponent(controller)
    private val rows = Rows()
    private val table = JBTable(rows).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        setShowGrid(false)
        autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        setDefaultRenderer(Any::class.java, RowRenderer())
    }
    private var binding = false
    private var sequenceShown = false
    private var programmaticScroll = false
    private val scrollWatch = io.yamsergey.dta.present.timeline.ScrollWatch()
    private val search = SearchTextField(false).apply { textEditor.emptyText.text = "Filter: text, URL, id" }
    private val unexplained = JBCheckBox("No known cause")
    private val inferred = JBCheckBox("Inferred links").apply { toolTipText = "Draw links inferred from timing and matching data (dashed); off by default" }
    private val follow = JBCheckBox("Follow live", true)
    private val sources = JButton("Sources: all")
    private val chosen = LinkedHashSet<String>()
    private val status = JBLabel().apply { foreground = JBColor.GRAY }
    private lateinit var viewSwitch: SegmentedButton<ViewChoice>
    private val cards = JPanel(CardLayout())
    private val sequence = SequenceComponent(onOpen = openNetwork, onFlowMenu = { f, e -> flowLineMenu(f).show(e.component, e.x, e.y) })
    private val sequenceTitles = sequence.Titles()
    private val sequenceScroll = JBScrollPane(sequence).apply { horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER; border = JBUI.Borders.empty() }
    private val sequenceNote = JBLabel().apply {
        foreground = DtaColors.of("caveat")
        border = JBUI.Borders.empty(2, 8)
        isVisible = false
    }
    /** Feature 048: Network's query line over the sequence. */
    private val query = com.intellij.ui.components.JBTextField().apply {
        emptyText.text = "Filter and search: status:5xx host:api.example.test -method:GET token"
        toolTipText = "<html>key:value words keep matching rows, a response with its request (method: status: host: url: kind: since: until:, GET/POST…, http, ws; -key:value leaves matches out);<br>" +
            "other words mark the rows holding them: Enter / Shift+Enter (F3 / Shift+F3) go to the next / previous.</html>"
    }
    private val hitLabel = JBLabel().apply { foreground = JBColor.GRAY }
    private val typing = javax.swing.Timer(250) { controller.sequence { it.withQuery(query.text) } }.apply { isRepeats = false }
    private val queryRow = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
        isOpaque = false
        add(query, BorderLayout.CENTER)
        add(hitLabel, BorderLayout.EAST)
    }
    private val caption = JBLabel().apply { foreground = JBColor.GRAY }
    private val captionAction = com.intellij.ui.components.ActionLink("") { _ ->
        when (state.sequence.action) {
            SequenceAction.RESUME -> controller.sequence { it.pause(false) }
            SequenceAction.SHOW_ALL -> controller.sequence { it.showAll() }
            SequenceAction.END_FLOW -> controller.endFlow()
            null -> Unit
        }
    }.apply { isVisible = false }

    private inner class Toggle(text: String, icon: javax.swing.Icon, private val on: () -> Boolean, private val set: (Boolean) -> Unit) : ToggleAction(text, null, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = on()
        override fun setSelected(e: AnActionEvent, state: Boolean) = set(state)
    }

    private inner class Act(text: String, icon: javax.swing.Icon, private val enabled: () -> Boolean = { true }, private val run: () -> Unit) : DumbAwareAction(text, null, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) = run()
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = enabled() }
    }

    /** Feature 049: Start Flow, or End Flow while one is recording. */
    private inner class FlowToggle : DumbAwareAction() {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            if (state.sequence.recording != null) controller.endFlow() else askToStart()
        }

        override fun update(e: AnActionEvent) {
            val recording = state.sequence.recording
            e.presentation.text = recording?.let { "End Flow: ${it.flow.name}" } ?: "Start Flow…"
            e.presentation.icon = if (recording != null) AllIcons.Actions.Suspend else AllIcons.Debugger.Db_set_breakpoint
            e.presentation.isEnabled = state.app != null
        }
    }

    /** Feature 048: the list's actions in a vertical strip at its left, in the Network list's order. */
    private val strip = ActionManager.getInstance().createActionToolbar("DtaTimelineSequence", DefaultActionGroup(
        FlowToggle(),
        Act("Flows", AllIcons.Actions.ListFiles, enabled = { state.sequence.flows.isNotEmpty() }) { flowsMenu().show(sequenceScroll, JBUI.scale(4), JBUI.scale(4)) },
        Separator.getInstance(),
        Act("Clear View… (Nothing Is Deleted)", AllIcons.Actions.GC) { clear() },
        Toggle("Pause", AllIcons.Actions.Pause, on = { state.sequence.paused }) { on -> controller.sequence { it.pause(on) } },
        Toggle("Scroll to the Newest", AllIcons.RunConfigurations.Scroll_down, on = { state.sequence.following }) { on ->
            controller.sequence { it.copy(following = on) }
            if (on) scrollToEnd()
        },
        Act("Previous Hit (Shift+Enter, Shift+F3)", AllIcons.Actions.PreviousOccurence, enabled = { state.sequence.hitRows.isNotEmpty() }) { controller.sequence { it.previousHit() } },
        Act("Next Hit (Enter, F3)", AllIcons.Actions.NextOccurence, enabled = { state.sequence.hitRows.isNotEmpty() }) { controller.sequence { it.nextHit() } },
        Separator.getInstance(),
        Act("ViewModels Now: Runtime › ViewModels (Alive Now, Not at the Time of a Row)", AllIcons.Nodes.Class) { openViewModels() },
    ), false).apply {
        targetComponent = this@TimelinePanel
        component.isOpaque = false
        component.border = JBUI.Borders.emptyRight(2)
    }
    private val detail = TimelineDetailPane(
        onSelect = { controller.select(it) },
        onOpenNetwork = openNetwork,
        onOpenLayout = openLayout,
        onCopy = { CopyPasteManager.getInstance().setContents(StringSelection(it)) },
    )

    init {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply {
            add(sources)
            add(search)
            add(unexplained)
            add(inferred)
            add(follow)
            add(JButton("−").apply { toolTipText = "Zoom out (-)"; addActionListener { zoom(1.5) } })
            add(JButton("+").apply { toolTipText = "Zoom in (+)"; addActionListener { zoom(1 / 1.5) } })
            add(status)
        }
        sources.addActionListener { sourcesMenu().show(sources, 0, sources.height) }
        search.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = applyFilter()
        })
        unexplained.addActionListener { applyFilter() }
        inferred.addActionListener { controller.setInferred(inferred.isSelected) }
        follow.addActionListener { controller.change(TimelineChange.Follow(follow.isSelected)) }
        table.selectionModel.addListSelectionListener { e ->
            if (e.valueIsAdjusting || binding) return@addListSelectionListener
            rows.at(table.selectedRow)?.let { controller.select(it.id) }
        }
        rows.sizeColumns(table)

        val left = JBSplitter(true, 0.45f).apply {
            firstComponent = JBScrollPane(lanes).apply { horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER }
            secondComponent = JBScrollPane(table)
        }
        val split = JBSplitter(false, 0.68f).apply {
            firstComponent = left
            secondComponent = detail
        }
        val list = JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(split, BorderLayout.CENTER)
        }
        val diagram = JPanel(BorderLayout()).apply {
            add(strip.component, BorderLayout.WEST)
            add(JPanel(BorderLayout()).apply {
                add(JPanel(BorderLayout()).apply {
                    add(sequenceNote, BorderLayout.NORTH)
                    add(sequenceTitles, BorderLayout.SOUTH)
                }, BorderLayout.NORTH)
                add(sequenceScroll, BorderLayout.CENTER)
                add(JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
                    border = JBUI.Borders.empty(2, 6, 0, 4)
                    add(caption, BorderLayout.CENTER)
                    add(captionAction, BorderLayout.EAST)
                }, BorderLayout.SOUTH)
            }, BorderLayout.CENTER)
        }
        cards.add(diagram, SEQUENCE)
        cards.add(list, LIST)
        val switch = panel {
            row {
                viewSwitch = segmentedButton(ViewChoice.entries) { text = it.label }
                    .whenItemSelectedFromUi { controller.setShowSequence(it == ViewChoice.SEQUENCE) }
            }
        }.apply { isOpaque = false }
        viewSwitch.selectedItem = ViewChoice.SEQUENCE
        add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.emptyRight(8)
            add(switch, BorderLayout.WEST)
            add(queryRow, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        add(cards, BorderLayout.CENTER)

        bindSequence()
        Keyboard.bind(this, ViewKey.END) {
            if (state.showSequence) { controller.sequence { it.copy(following = true) }; scrollToEnd() } else controller.change(TimelineChange.Follow(true))
        }
        Keyboard.bind(this, ViewKey.ZOOM_IN) { zoom(1 / 1.5) }
        Keyboard.bind(this, ViewKey.ZOOM_OUT) { zoom(1.5) }
        Keyboard.bind(this, ViewKey.OPEN) {
            val e = state.view.selected ?: return@bind
            e.network?.let { openNetwork(it.entryId) } ?: if (e.source == Sources.UI) openLayout() else Unit
        }
        Keyboard.bind(this, ViewKey.COPY) { state.view.selectedId?.let { CopyPasteManager.getInstance().setContents(StringSelection(it)) } }

        scope.launch {
            controller.state.collect { s -> withContext(Dispatchers.Main) { show(s) } }
        }
    }

    private fun bindSequence() {
        query.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                val problems = QueryLine.parse(query.text).problems
                query.putClientProperty("JComponent.outline", if (problems.isEmpty()) null else "error")
                query.repaint()
                typing.restart()
            }
        })
        fun key(stroke: String, action: () -> Unit) = registerKeyboardAction({ action() }, javax.swing.KeyStroke.getKeyStroke(stroke), WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        fun inLine(stroke: String, action: () -> Unit) = query.registerKeyboardAction({ action() }, javax.swing.KeyStroke.getKeyStroke(stroke), WHEN_FOCUSED)
        key("F3") { if (state.showSequence) controller.sequence { it.nextHit() } }
        key("shift F3") { if (state.showSequence) controller.sequence { it.previousHit() } }
        inLine("ENTER") { controller.sequence { it.nextHit() } }
        inLine("shift ENTER") { controller.sequence { it.previousHit() } }
        sequenceScroll.verticalScrollBar.addAdjustmentListener {
            if (!scrollWatch.userMoved(it.value, it.valueIsAdjusting, programmaticScroll)) return@addAdjustmentListener
            val atEnd = atEnd()
            val view = state.sequence
            if (view.following != atEnd && !view.paused && view.steps.isNotEmpty()) controller.sequence { v -> v.copy(following = atEnd) }
        }
    }

    private fun atEnd(): Boolean {
        val bar = sequenceScroll.verticalScrollBar
        return bar.value + bar.visibleAmount >= bar.maximum - JBUI.scale(8)
    }

    private fun scrollToItem(i: Int) {
        javax.swing.SwingUtilities.invokeLater {
            sequence.validate()
            programmaticScroll = true
            sequence.scrollRectToVisible(sequence.rowBounds(i))
            programmaticScroll = false
        }
    }

    private fun scrollToEnd() {
        javax.swing.SwingUtilities.invokeLater {
            sequence.validate()
            sequenceScroll.validate()
            programmaticScroll = true
            val bar = sequenceScroll.verticalScrollBar
            bar.value = bar.maximum
            programmaticScroll = false
        }
    }

    private fun clear() {
        val yes = Messages.showYesNoDialog(this, "The view will start from now. Nothing is deleted: the app's journal and the Network history stay, and Show all brings the earlier rows back.", "Clear the Timeline View", null)
        if (yes == Messages.YES) controller.sequence { it.clear() }
    }

    private val flowName = object : com.intellij.openapi.ui.InputValidator {
        override fun checkInput(text: String) = text.trim().length <= io.yamsergey.dta.protocol.timeline.Flow.MAX_NAME
        override fun canClose(text: String) = checkInput(text)
    }

    private fun askToStart() {
        val name = Messages.showInputDialog(
            this,
            "Name (optional). What happens from now until you end it is this flow, background traffic included.",
            "Start a Flow", null, "", flowName,
        ) ?: return
        controller.startFlow(name)
    }

    /** The app's flows, newest first; choosing one goes to its line. */
    private fun flowsMenu() = JPopupMenu().apply {
        state.sequence.flowMenu().forEach { item ->
            add(javax.swing.JMenuItem("${item.name}  ·  ${item.meta}").apply {
                isEnabled = item.enabled
                addActionListener { controller.sequence { it.goToFlow(item.id) } }
            })
        }
    }

    private fun flowLineMenu(f: io.yamsergey.dta.protocol.timeline.SequenceFlow) = JPopupMenu().apply {
        add(javax.swing.JMenuItem("Rename…").apply {
            addActionListener {
                val name = Messages.showInputDialog(this@TimelinePanel, "Name", "Rename the Flow", null, f.flow.name, object : com.intellij.openapi.ui.InputValidator {
                    override fun checkInput(text: String) = text.isNotBlank() && flowName.checkInput(text)
                    override fun canClose(text: String) = checkInput(text)
                }) ?: return@addActionListener
                controller.renameFlow(f.flow.id, name)
            }
        })
        add(javax.swing.JMenuItem("Remove (nothing recorded is deleted)").apply { addActionListener { controller.removeFlow(f.flow.id) } })
    }

    private fun zoom(factor: Double) {
        val w = state.view.window
        val anchor = if (w.follow) w.endMs else w.endMs - (lanes.width / 2 * w.msPerPx).toLong()
        controller.change(TimelineChange.Zoom(factor, anchor))
    }

    private fun applyFilter() {
        controller.setFilter(TimelineFilter(sources = chosen.toSet(), text = search.text, unexplainedOnly = unexplained.isSelected))
    }

    private fun sourcesMenu(): JPopupMenu = JPopupMenu().apply {
        val names = (state.view.sources.map { it.name } + state.view.events.map { it.source }).filter { it != Sources.TIMELINE }.distinct()
        names.forEach { name ->
            add(JCheckBoxMenuItem(name, name in chosen).apply {
                addActionListener {
                    if (isSelected) chosen += name else chosen -= name
                    sources.text = if (chosen.isEmpty()) "Sources: all" else "Sources: ${chosen.joinToString(", ")}"
                    applyFilter()
                }
            })
        }
    }

    private fun show(s: TimelineTabState) {
        val previous = state
        state = s
        showSequence(previous, s)
        lanes.show(s)
        if (follow.isSelected != s.view.window.follow) follow.isSelected = s.view.window.follow
        if (inferred.isSelected != s.inferred) inferred.isSelected = s.inferred
        status.text = when {
            s.app == null -> "Choose an app with DTA inside."
            s.error != null -> "The timeline could not be read: ${s.error}"
            s.loading -> "Loading…"
            else -> "${s.view.shown.size} of ${s.view.events.size} events" + if (s.view.dropped > 0) " · ${s.view.dropped} older dropped here" else ""
        }
        if (previous.view.events !== s.view.events || previous.view.filter != s.view.filter) {
            binding = true
            try {
                rows.update(s.view.shown)
                select(s.view.selectedId)
                if (s.view.window.follow && rows.rowCount > 0) table.scrollRectToVisible(table.getCellRect(rows.rowCount - 1, 0, true))
            } finally {
                binding = false
            }
        } else if (previous.view.selectedId != s.view.selectedId) {
            binding = true
            try { select(s.view.selectedId) } finally { binding = false }
        }
        detail.show(s)
    }

    private fun showSequence(previous: TimelineTabState, s: TimelineTabState) {
        (cards.layout as CardLayout).show(cards, if (s.showSequence) SEQUENCE else LIST)
        val choice = if (s.showSequence) ViewChoice.SEQUENCE else ViewChoice.LIST
        if (viewSwitch.selectedItem != choice) viewSwitch.selectedItem = choice
        queryRow.isVisible = s.showSequence
        val v: SequenceView = s.sequence
        // Another app starts a new view: the line follows, unless someone is typing in it.
        if (v.query != query.text && !typing.isRunning) query.text = v.query
        val page = v.page
        val input = page?.sources?.firstOrNull { it.name == Sources.INPUT && it.state != SourceState.ON }
        val note = listOfNotNull(
            s.sequenceError?.let { "The sequence could not be read: $it" },
            input?.let { "Taps may be missing: ${it.reason ?: it.state.name.lowercase()}" },
            page?.socketsNote,
            page?.flowsNote,
            s.flowError?.let { "The flow: $it" },
        ).joinToString(" · ")
        sequenceNote.text = note
        sequenceNote.isVisible = note.isNotEmpty()
        caption.text = v.caption
        captionAction.text = v.action?.label.orEmpty()
        captionAction.isVisible = v.action != null
        hitLabel.text = v.hitLabel(QueryLine.parse(v.query).terms.isNotEmpty()).orEmpty()
        if (previous.sequence === v && previous.app == s.app && sequenceShown) return
        sequenceShown = true
        // The strip follows the state at once, not at the toolbar's next look.
        if (v.paused != previous.sequence.paused || v.following != previous.sequence.following || v.hitRows.isEmpty() != previous.sequence.hitRows.isEmpty() ||
            v.recording?.flow != previous.sequence.recording?.flow || v.flows.isEmpty() != previous.sequence.flows.isEmpty() || s.app != previous.app
        ) strip.updateActionsAsync()
        val empty = when {
            s.app == null -> "Choose an app with DTA inside."
            page != null && page.total > 0 -> "Nothing matches this filter."
            v.startMs != null -> "Nothing since the view started."
            else -> "Nothing recorded yet: use the app."
        }
        sequence.show(v, empty)
        sequenceTitles.repaint()
        val target = v.flowTarget?.let(v::itemOfFlow) ?: -1
        when {
            target >= 0 -> {
                scrollToItem(target)
                controller.sequence { it.copy(flowTarget = null) }
            }
            v.currentRow >= 0 && v.currentRow != previous.sequence.currentRow -> scrollToItem(v.itemOfStep(v.currentRow))
            v.following -> scrollToEnd()
        }
    }

    private enum class ViewChoice(val label: String) { SEQUENCE("Sequence"), LIST("List") }

    private fun select(id: String?) {
        val i = id?.let(rows::indexOf)
        if (i == null) table.clearSelection() else {
            table.setRowSelectionInterval(i, i)
            table.scrollRectToVisible(table.getCellRect(i, 0, true))
        }
    }

    private companion object {
        const val SEQUENCE = "sequence"
        const val LIST = "list"
    }

    private class Rows : AbstractTableModel() {
        private var rows: List<TimelineEvent> = emptyList()
        private var index: Map<String, Int> = emptyMap()

        fun update(next: List<TimelineEvent>) {
            rows = next
            index = next.withIndex().associate { (i, e) -> e.id to i }
            fireTableDataChanged()
        }

        fun at(i: Int): TimelineEvent? = rows.getOrNull(i)

        fun indexOf(id: String): Int? = index[id]

        override fun getRowCount() = rows.size
        override fun getColumnCount() = COLUMNS.size
        override fun getColumnName(column: Int) = COLUMNS[column]
        override fun getValueAt(row: Int, column: Int): Any = rows[row]

        fun sizeColumns(table: JTable) {
            listOf(96, 64, 0, 28).forEachIndexed { i, w -> if (w > 0) table.columnModel.getColumn(i).apply { preferredWidth = JBUI.scale(w); maxWidth = JBUI.scale(w * 2) } }
        }

        companion object {
            val COLUMNS = listOf("Time", "Source", "What happened", "Cause")
        }
    }

    private class RowRenderer : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            val e = value as? TimelineEvent ?: return
            when (column) {
                0 -> append(TIME.format(Instant.ofEpochMilli(e.wallMs)), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                1 -> append(e.source, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                2 -> {
                    val text = TimelineEventText.describe(e)
                    if (e.source == Sources.TIMELINE) append(text, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, DtaColors.of("caveat"))) else append(text)
                    toolTipText = "${e.id}  ${TimelineEventText.line(e)}"
                }
                3 -> when {
                    e.causes.isNotEmpty() -> append("←", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    TimelineFilter.unexplained(e) -> append("·", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, DtaColors.of("caveat"))).also { toolTipText = "no known cause" }
                    else -> Unit
                }
            }
        }

        private companion object {
            val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
        }
    }
}
