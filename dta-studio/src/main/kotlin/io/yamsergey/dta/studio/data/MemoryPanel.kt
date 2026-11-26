package io.yamsergey.dta.studio.data

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.present.data.HeapText
import io.yamsergey.dta.present.data.MemoryRows
import io.yamsergey.dta.present.data.RuntimeText
import io.yamsergey.dta.protocol.data.HeapCompareAnswer
import io.yamsergey.dta.protocol.data.HeapFieldsAnswer
import io.yamsergey.dta.protocol.data.HeapInstancesAnswer
import io.yamsergey.dta.protocol.data.HeapListAnswer
import io.yamsergey.dta.protocol.data.HeapSnapshotAnswer
import io.yamsergey.dta.protocol.data.MemoryState
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.studio.ui.WrappingText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Runtime › Memory (feature 074 D1, boards Memory / MemoryNarrow / MemoryEmpty): the memory numbers, heap snapshots by
 * class on request, the histogram of one snapshot or the comparison of two (growing classes first), a class's live
 * instances with links into the Runtime views, and one instance's fields. Opening the view reads the numbers and the
 * list of snapshots; it never takes a snapshot (FR-000). Every answer shows its pause; refusals show their reason.
 * The plugin is an IDE client: values are as they are in the app.
 */
class MemoryPanel(
    private val project: Project,
    private val scope: CoroutineScope,
    private val ui: (Runnable) -> Unit = { SwingUtilities.invokeLater(it) },
    /** A link from an instance: the Runtime view and what to show in it. */
    private val openView: (MemoryRows.Target, String?) -> Unit = { _, _ -> },
    private val call: suspend (String, JsonObject) -> JsonObject,
) : JPanel(BorderLayout()) {
    private data class Pick(val key: String, val label: String) { override fun toString() = label }

    private val mono = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(12f))
    private val stats = WrappingText(" ", JBColor.foreground()).apply { font = mono; name = "memory.stats" }
    private val status = WrappingText("", JBColor.GRAY).apply { name = "memory.status" }
    private val statement = WrappingText("", JBColor.GRAY).apply { name = "memory.statement" }
    private val leftOut = WrappingText("", JBColor.GRAY).apply { name = "memory.leftOut" }
    private val notKnown = WrappingText(MemoryRows.NOT_KNOWN, JBColor.GRAY)
    private val cards = CardLayout()
    private val content = JPanel(cards)

    private val comboA = JComboBox<Pick>().apply { name = "memory.a" }
    private val comboB = JComboBox<Pick>().apply { name = "memory.b" }
    private var originAll = false
    private val appOnly = javax.swing.JToggleButton(APP_CLASSES, true).apply { name = "memory.origin.app" }
    private val allOrigins = javax.swing.JToggleButton(ALL).apply { name = "memory.origin.all" }
    /**
     * App classes | All as one joined pair of toggles. (The platform's segmented button is an action button that did not
     * take a mouse click here — in the robot IDE a real click left the origin unchanged — so the pair is plain toggles,
     * the chosen one bold and tinted; a test clicks "All" with the mouse.)
     */
    private val originControl: JPanel = JPanel(java.awt.GridLayout(1, 2, 0, 0)).apply { name = "memory.origin"; isOpaque = false; add(appOnly); add(allOrigins) }
    private val nameField = JBTextField(16).apply { name = "memory.name"; emptyText.text = "snapshot name (optional)" }
    private val packageField = JBTextField(14).apply { name = "memory.package"; emptyText.text = "package prefix" }

    /** Which snapshots: A → B in a row; stacked, A over B, when narrow (the board's MemoryNarrow). */
    private val snapshotRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(2)))
    private val arrow = JBLabel("→").apply { foreground = JBColor.GRAY }

    private fun layoutPickers() {
        snapshotRow.removeAll()
        snapshotRow.layout = if (narrow) java.awt.GridLayout(0, 1, 0, JBUI.scale(2)) else FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(2))
        snapshotRow.add(comboA)
        if (!narrow) snapshotRow.add(arrow)
        snapshotRow.add(comboB)
        snapshotRow.revalidate(); snapshotRow.repaint()
    }

    private var list: HeapListAnswer? = null
    private var comparison: HeapCompareAnswer? = null
    private var histogram = true
    private var narrow = false
    private var instances: HeapInstancesAnswer? = null
    private var updating = false
    /** The next comparison keeps the message of the snapshot just taken (its pause is the point). */
    private var keepStatus = false

    private val tableModel = object : AbstractTableModel() {
        var rows = emptyList<MemoryRows.Row>()
        override fun getRowCount() = rows.size
        override fun getColumnCount() = MemoryRows.columns(narrow, histogram).size
        override fun getColumnName(column: Int) = MemoryRows.columns(narrow, histogram)[column]
        override fun getValueAt(row: Int, column: Int): Any = rows[row].cells[column]
    }
    internal val table = object : JBTable(tableModel) {
        /** The class's whole name, whatever the column shows of it. */
        override fun getToolTipText(e: java.awt.event.MouseEvent): String? {
            val row = rowAtPoint(e.point); val col = columnAtPoint(e.point)
            return if (row >= 0 && col == 0) tableModel.rows.getOrNull(row)?.className else super.getToolTipText(e)
        }
    }.apply {
        name = "memory.table"
        autoResizeMode = javax.swing.JTable.AUTO_RESIZE_ALL_COLUMNS
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        font = mono
        setDefaultRenderer(Any::class.java, object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(t: javax.swing.JTable, v: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int): Component {
                val c = super.getTableCellRendererComponent(t, v, sel, focus, row, col)
                val r = tableModel.rows.getOrNull(row)
                c.font = if (r?.grew == true && !histogram && col >= (if (narrow) 1 else 3)) mono.deriveFont(Font.BOLD) else mono
                horizontalAlignment = if (col == 0) LEFT else RIGHT
                if (col == 0 && r != null) {
                    // The simple name stays: the middle of a long name is elided to the room the column has.
                    val chars = ((t.columnModel.getColumn(0).width - JBUI.scale(10)) / t.getFontMetrics(mono).charWidth('m')).coerceAtLeast(6)
                    text = MemoryRows.elide(r.className, chars)
                }
                return c
            }
        })
    }

    private val instanceModel = DefaultListModel<MemoryRows.InstanceRow>()
    internal val instanceList = JBList(instanceModel).apply {
        name = "memory.instances"
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 6
        cellRenderer = javax.swing.ListCellRenderer { _, row, _, selected, _ ->
            JBLabel("${row.label}   ${row.state}${row.linkText?.let { "   $it" } ?: ""}").apply {
                font = mono
                isOpaque = true
                background = if (selected) JBUI.CurrentTheme.List.background(true, true) else JBUI.CurrentTheme.List.background(false, false)
            }
        }
    }
    private val instanceHeader = JBLabel(" ").apply { font = mono.deriveFont(Font.BOLD); name = "memory.instances.header" }
    private val instanceSummary = WrappingText("", JBColor.GRAY)
    private val instanceLink = ActionLink("") { instanceList.selectedValue?.let { r -> r.target?.let { openView(it, r.ref) } } }.apply { isVisible = false; name = "memory.instances.link" }
    private val readFields = JButton("Read fields").apply { name = "memory.fields"; isEnabled = false; addActionListener { readSelectedFields() } }
    private val fieldsText = JBTextArea().apply { isEditable = false; font = mono; lineWrap = false; name = "memory.fields.text" }
    private val fieldsScroll = JBScrollPane(fieldsText).apply { isVisible = false; preferredSize = java.awt.Dimension(JBUI.scale(200), JBUI.scale(160)) }

    /** The last message shown (what tests read). */
    internal val statusLine: String get() = status.text
    internal val statementLine: String get() = statement.text
    internal val hasSnapshots: Boolean get() = list?.snapshots?.isNotEmpty() == true
    internal val rowCount: Int get() = tableModel.rowCount
    internal fun cell(row: Int, col: Int): String = tableModel.rows[row].cells[col]

    private val splitter = JBSplitter(false, "dta.memory.split", 0.62f)

    init {
        add(vbar(), BorderLayout.WEST)
        val main = JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(6, 10) }
        val top = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(stats)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, JBUI.scale(2))).apply { add(nameField); alignmentX = Component.LEFT_ALIGNMENT })
            add(status)
            stats.alignmentX = Component.LEFT_ALIGNMENT
        }
        main.add(top, BorderLayout.NORTH)
        main.add(content, BorderLayout.CENTER)
        add(main, BorderLayout.CENTER)

        content.add(JPanel(BorderLayout()).apply { add(JBLabel("<html><div style='width:320px;text-align:center'>${MemoryRows.EMPTY}</div></html>", JBLabel.CENTER).apply { foreground = JBColor.GRAY; name = "memory.empty" }, BorderLayout.CENTER) }, EMPTY)
        // Two rows so nothing is cut at a narrow width: which snapshots, then which classes.
        val pickers = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            add(snapshotRow)
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(2))).apply { add(originControl); add(packageField) })
        }
        val tablePane = JBScrollPane(table)
        val instances = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4, 10)
            add(instanceHeader); add(instanceSummary); add(JBScrollPane(instanceList)); add(instanceLink); add(readFields); add(fieldsScroll)
            instanceHeader.alignmentX = Component.LEFT_ALIGNMENT; readFields.alignmentX = Component.LEFT_ALIGNMENT; instanceLink.alignmentX = Component.LEFT_ALIGNMENT
            isVisible = false
        }
        splitter.firstComponent = tablePane
        splitter.secondComponent = instances
        content.add(JPanel(BorderLayout()).apply {
            add(JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); add(pickers); add(statement); statement.alignmentX = Component.LEFT_ALIGNMENT }, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
            add(JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); add(leftOut); add(notKnown) }, BorderLayout.SOUTH)
        }, CONTENT)
        cards.show(content, EMPTY)
        layoutPickers()

        listOf(comboA, comboB).forEach { c -> c.addActionListener { if (!updating) compare() } }
        // The package filter applies as it is typed (a short pause after the last key), or at Enter.
        val debounce = javax.swing.Timer(350) { compare() }.apply { isRepeats = false }
        packageField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = debounce.restart()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = debounce.restart()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = debounce.restart()
        })
        packageField.addActionListener { debounce.stop(); compare() }
        javax.swing.ButtonGroup().apply { add(appOnly); add(allOrigins) }
        listOf(appOnly, allOrigins).forEach { b -> b.addActionListener { originAll = allOrigins.isSelected; markOrigin(); compare() } }
        markOrigin()
        table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) tableModel.rows.getOrNull(table.selectedRow)?.let { r -> showInstances(r.className) } }
        instanceList.addListSelectionListener {
            val r = instanceList.selectedValue
            readFields.isEnabled = r != null
            readFields.text = r?.let { "Read fields of ${it.label}" } ?: "Read fields"
            instanceLink.isVisible = r?.linkText != null
            instanceLink.text = r?.let { "Open in ${it.linkText?.removeSuffix(" ›")}" } ?: ""
        }
    }

    /** The actions in a vertical strip at the left, as the Network and Timeline lists have theirs. */
    /** Narrow follows the width at every layout (so it holds before the panel is shown, too). */
    override fun doLayout() {
        val n = width in 1 until NARROW
        if (n != narrow) { narrow = n; splitter.orientation = n; showRows(); layoutPickers() }
        super.doLayout()
    }

    private fun vbar(): javax.swing.JComponent {
        fun act(text: String, icon: javax.swing.Icon, run: () -> Unit) = object : com.intellij.openapi.project.DumbAwareAction(text, null, icon) {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) = run()
            override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
        }
        val group = com.intellij.openapi.actionSystem.DefaultActionGroup(
            act("Take Snapshot", AllIcons.Actions.Dump) { takeSnapshot(gc = false) },
            act("Take Snapshot After a Garbage Collection", AllIcons.Actions.GC) { takeSnapshot(gc = true) },
            com.intellij.openapi.actionSystem.Separator.getInstance(),
            act("Compare the Two Snapshots Shown", AllIcons.Actions.Diff) { compare() },
            act("Copy the Comparison as Text", AllIcons.Actions.Copy) {
                comparison?.let { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(HeapText.compare(it)), null) }
            },
        )
        return com.intellij.openapi.actionSystem.ActionManager.getInstance().createActionToolbar("DtaMemory", group, false).apply {
            targetComponent = this@MemoryPanel
            component.isOpaque = false
            component.border = JBUI.Borders.emptyRight(2)
        }.component
    }

    /** Reads the memory numbers and the list of snapshots (never takes one: FR-000). */
    fun load() {
        scope.launch {
            val mem = runCatching { decode(MemoryState.serializer(), call("runtime.memory", EMPTY_PARAMS)) }
            val snaps = runCatching { decode(HeapListAnswer.serializer(), call("runtime.heap.snapshots", EMPTY_PARAMS)) }
            ui.invoke(Runnable {
                mem.fold({ stats.text = RuntimeText.memory(it) }, { stats.text = " "; say(MemoryRows.refusal(it)) })
                snaps.fold({ applyList(it, selectNewest = list == null || it.run != list?.run || it.snapshots.size != list?.snapshots?.size) }, { say(MemoryRows.refusal(it)) })
            })
        }
    }

    private fun applyList(l: HeapListAnswer, selectNewest: Boolean) {
        list = l
        val snaps = l.snapshots
        if (snaps.isEmpty()) {
            comparison = null; tableModel.rows = emptyList(); tableModel.fireTableDataChanged()
            cards.show(content, EMPTY)
            return
        }
        val picks = snaps.map { Pick(it.id, MemoryRows.pickerLabel(it, ::clock)) }
        updating = true
        val previousA = (comboA.selectedItem as? Pick)?.key
        val previousB = (comboB.selectedItem as? Pick)?.key
        comboA.model = DefaultComboBoxModel((listOf(Pick(HISTOGRAM, "no earlier snapshot · histogram of B")) + picks).toTypedArray())
        comboB.model = DefaultComboBoxModel(picks.toTypedArray())
        val b = picks.firstOrNull { it.key == previousB && !selectNewest } ?: picks.last()
        comboB.selectedItem = b
        val a = if (selectNewest || previousA == null) (picks.getOrNull(picks.size - 2) ?: Pick(HISTOGRAM, "")) else Pick(previousA, "")
        (0 until comboA.itemCount).map { comboA.getItemAt(it) }.firstOrNull { it.key == a.key }?.let { comboA.selectedItem = it }
        updating = false
        cards.show(content, CONTENT)
        compare()
    }

    private fun clock(ms: Long): String = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))

    /** On request: one snapshot, optionally named; the answer's pause is shown, with the warning above 100 ms. */
    fun takeSnapshot(gc: Boolean, name: String? = nameField.text.trim().takeIf { it.isNotEmpty() }) {
        say("Taking a snapshot… the app is stopped while its heap is walked.")
        val params = buildMap<String, kotlinx.serialization.json.JsonElement> {
            name?.takeIf { it.isNotBlank() }?.let { put("name", JsonPrimitive(it)) }
            if (gc) put("gc", JsonPrimitive(true))
        }
        scope.launch {
            val result = runCatching { decode(HeapSnapshotAnswer.serializer(), call("runtime.heap.snapshot", JsonObject(params))) }
            ui.invoke(Runnable {
                result.fold({ a ->
                    say(listOfNotNull("Snapshot ${a.snapshot.id}${a.snapshot.name?.let { " ($it)" } ?: ""} taken: paused the app ${a.pauseMs} ms" + (if (a.snapshot.gcBefore) " after a garbage collection" else ""), a.warning, a.dropped).joinToString(" · "))
                    keepStatus = true
                    nameField.text = ""
                    load()
                }, { say(MemoryRows.refusal(it)) })
            })
        }
    }

    private fun compare() {
        val l = list ?: return
        val b = (comboB.selectedItem as? Pick)?.key ?: return
        val a = (comboA.selectedItem as? Pick)?.key ?: HISTOGRAM
        val hist = a == HISTOGRAM || a == b
        val params = buildMap<String, kotlinx.serialization.json.JsonElement> {
            put("a", JsonPrimitive(if (hist) "empty" else a)); put("b", JsonPrimitive(b))
            put("origin", JsonPrimitive(if (originAll) "all" else "app"))
            packageField.text.trim().takeIf { it.isNotEmpty() }?.let { put("package", JsonPrimitive(it)) }
        }
        scope.launch {
            val result = runCatching { decode(HeapCompareAnswer.serializer(), call("runtime.heap.compare", JsonObject(params))) }
            ui.invoke(Runnable {
                result.fold({ c ->
                    comparison = c; histogram = hist
                    showRows()
                    val info = l.snapshots.firstOrNull { it.id == b }
                    statement.text = info?.let { MemoryRows.statement("B", it, originAll) } ?: ""
                    leftOut.text = MemoryRows.leftOut(c.comparison)?.let { "$it." } ?: ""
                    if (keepStatus) keepStatus = false else say("")
                    instances = null; instancesPanel().isVisible = false
                }, { say(MemoryRows.refusal(it)) })
            })
        }
    }

    /** The table for the comparison held, in the columns the width and the mode ask for. */
    private fun showRows() {
        tableModel.rows = comparison?.let { MemoryRows.rows(it.comparison, narrow, histogram) } ?: emptyList()
        tableModel.fireTableStructureChanged()
        sizeColumns()
    }

    /** The chosen origin is the bold, tinted one (a toggle in the IDE's theme does not show it on its own). */
    private fun markOrigin() {
        listOf(appOnly, allOrigins).forEach { b ->
            b.font = if (b.isSelected) b.font.deriveFont(Font.BOLD) else b.font.deriveFont(Font.PLAIN)
            b.isOpaque = b.isSelected
            b.background = if (b.isSelected) JBColor(java.awt.Color(0xD5E4FF), java.awt.Color(0x2E436E)) else null
            b.repaint()
        }
    }

    /** The origin of the classes shown (the segmented control follows). */
    internal fun setOrigin(all: Boolean) {
        originAll = all
        (if (all) allOrigins else appOnly).isSelected = true
        markOrigin()
        compare()
    }

    /** The class name gets the room; the numbers stay narrow. */
    private fun sizeColumns() {
        val cm = table.columnModel
        // Class takes what is left; the numbers keep room for "+283.4 KB" even at 420 px (the board drops A and B instead).
        for (i in 0 until cm.columnCount) cm.getColumn(i).apply {
            if (i == 0) { minWidth = JBUI.scale(60); preferredWidth = JBUI.scale(if (narrow) 160 else 300) }
            else { minWidth = JBUI.scale(58); preferredWidth = JBUI.scale(if (narrow) 84 else 72) }
        }
    }

    private fun instancesPanel(): Component = splitter.secondComponent

    private fun showInstances(className: String) {
        scope.launch {
            val result = runCatching { decode(HeapInstancesAnswer.serializer(), call("runtime.heap.instances", JsonObject(mapOf("class" to JsonPrimitive(className))))) }
            ui.invoke(Runnable {
                result.fold({ a ->
                    instances = a
                    instanceHeader.text = MemoryRows.instanceHeader(a)
                    instanceSummary.text = MemoryRows.instanceSummary(a)
                    instanceModel.clear(); MemoryRows.instanceRows(a).forEach(instanceModel::addElement)
                    fieldsScroll.isVisible = false
                    instancesPanel().isVisible = true
                    splitter.revalidate()
                }, { say(MemoryRows.refusal(it)) })
            })
        }
    }

    private fun readSelectedFields() {
        val r = instanceList.selectedValue ?: return
        scope.launch {
            val result = runCatching { decode(HeapFieldsAnswer.serializer(), call("runtime.heap.fields", JsonObject(mapOf("instance" to JsonPrimitive(r.id), "values" to JsonPrimitive(true))))) }
            ui.invoke(Runnable {
                result.fold({ a -> fieldsText.text = HeapText.fields(a); fieldsText.caretPosition = 0; fieldsScroll.isVisible = true; splitter.revalidate() }, { say(MemoryRows.refusal(it)) })
            })
        }
    }

    private fun say(text: String) { status.text = text }

    private fun <T> decode(s: KSerializer<T>, json: JsonObject): T = DtaJson.decodeFromJsonElement(s, json)

    companion object {
        /** The view itself (the tab minus its section list) is narrow below this; the tab goes to a combo below 700. */
        private const val NARROW = 560
        private const val EMPTY = "empty"
        private const val CONTENT = "content"
        private const val APP_CLASSES = "App classes"
        private const val ALL = "All"
        private const val HISTOGRAM = "histogram"
        private val EMPTY_PARAMS = JsonObject(emptyMap())
    }
}
