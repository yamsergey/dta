package io.yamsergey.dta.studio.toolwindow

import com.intellij.openapi.ide.CopyPasteManager
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
import io.yamsergey.dta.present.timeline.TimelineChange
import io.yamsergey.dta.present.timeline.TimelineEventText
import io.yamsergey.dta.present.timeline.TimelineFilter
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import io.yamsergey.dta.protocol.ui.SourceState
import io.yamsergey.dta.studio.timeline.EpisodesComponent
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
    private var episodesShown = false
    private val search = SearchTextField(false).apply { textEditor.emptyText.text = "Filter: text, URL, id" }
    private val unexplained = JBCheckBox("No known cause")
    private val inferred = JBCheckBox("Inferred links").apply { toolTipText = "Draw links inferred from timing and matching data (dashed); off by default" }
    private val follow = JBCheckBox("Follow live", true)
    private val sources = JButton("Sources: all")
    private val chosen = LinkedHashSet<String>()
    private val status = JBLabel().apply { foreground = JBColor.GRAY }
    private lateinit var viewSwitch: SegmentedButton<ViewChoice>
    private val cards = JPanel(CardLayout())
    private val episodes = EpisodesComponent(onToggle = { controller.toggleEpisode(it) }, onOpen = openNetwork)
    private val episodesScroll = JBScrollPane(episodes).apply { horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER; border = JBUI.Borders.empty() }
    private val episodeSummary = JBLabel().apply { foreground = JBColor.GRAY }
    private val episodeNote = JBLabel().apply {
        foreground = DtaColors.of("caveat")
        border = JBUI.Borders.empty(2, 8)
        isVisible = false
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
            add(JPanel(BorderLayout()).apply {
                add(episodeNote, BorderLayout.NORTH)
                add(episodes.Titles(), BorderLayout.SOUTH)
            }, BorderLayout.NORTH)
            add(episodesScroll, BorderLayout.CENTER)
        }
        cards.add(diagram, EPISODES)
        cards.add(list, LIST)
        val switch = panel {
            row {
                viewSwitch = segmentedButton(ViewChoice.entries) { text = it.label }
                    .whenItemSelectedFromUi { controller.setShowEpisodes(it == ViewChoice.EPISODES) }
            }
        }.apply { isOpaque = false }
        viewSwitch.selectedItem = ViewChoice.EPISODES
        add(JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyRight(8)
            add(switch, BorderLayout.WEST)
            add(episodeSummary, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(cards, BorderLayout.CENTER)

        Keyboard.bind(this, ViewKey.END) { controller.change(TimelineChange.Follow(true)) }
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
        showEpisodes(previous, s)
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

    private fun showEpisodes(previous: TimelineTabState, s: TimelineTabState) {
        (cards.layout as CardLayout).show(cards, if (s.showEpisodes) EPISODES else LIST)
        val choice = if (s.showEpisodes) ViewChoice.EPISODES else ViewChoice.LIST
        if (viewSwitch.selectedItem != choice) viewSwitch.selectedItem = choice
        episodeSummary.text = if (s.showEpisodes) s.episodes.summary else ""
        val page = s.episodes.page
        val input = page?.sources?.firstOrNull { it.name == Sources.INPUT && it.state != SourceState.ON }
        val note = listOfNotNull(
            s.episodesError?.let { "The episodes could not be read: $it" },
            input?.let { "Taps may be missing: ${it.reason ?: it.state.name.lowercase()}" },
            page?.socketsNote,
        ).joinToString(" · ")
        episodeNote.text = note
        episodeNote.isVisible = note.isNotEmpty()
        if (previous.episodes === s.episodes && previous.app == s.app && episodesShown) return
        episodesShown = true
        val bar = episodesScroll.verticalScrollBar
        val atEnd = bar.value + bar.visibleAmount >= bar.maximum - JBUI.scale(8)
        val grew = previous.episodes.page?.total != page?.total
        val empty = if (s.app == null) "Choose an app with DTA inside." else "No episodes yet: use the app."
        episodes.show(s.episodes, empty)
        if (atEnd || grew) javax.swing.SwingUtilities.invokeLater {
            episodes.validate()
            episodesScroll.validate()
            bar.value = bar.maximum
        }
    }

    private enum class ViewChoice(val label: String) { EPISODES("Episodes"), LIST("List") }

    private fun select(id: String?) {
        val i = id?.let(rows::indexOf)
        if (i == null) table.clearSelection() else {
            table.setRowSelectionInterval(i, i)
            table.scrollRectToVisible(table.getCellRect(i, 0, true))
        }
    }

    private companion object {
        const val EPISODES = "episodes"
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
