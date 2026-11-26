package io.yamsergey.dta.studio.data

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.present.data.PropertyRows
import io.yamsergey.dta.present.data.RuntimeText
import io.yamsergey.dta.protocol.data.FunctionList
import io.yamsergey.dta.present.Units
import io.yamsergey.dta.protocol.data.DiReport
import io.yamsergey.dta.protocol.data.InvokeResult
import io.yamsergey.dta.protocol.data.MemoryState
import io.yamsergey.dta.protocol.data.NavHostInfo
import io.yamsergey.dta.protocol.data.NavigationState
import io.yamsergey.dta.protocol.data.RuntimeActivities
import io.yamsergey.dta.protocol.data.ThreadList
import io.yamsergey.dta.protocol.data.ViewModelInfo
import io.yamsergey.dta.protocol.data.ViewModelList
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.studio.ui.JsonValueView
import io.yamsergey.dta.studio.ui.adaptiveSplitter
import io.yamsergey.dta.present.data.ThreadRows
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * The Runtime tab (feature 028 US5): activities, navigation hosts, ViewModels, functions, memory,
 * threads and DI graphs (035) in a tree; the selected part on the right — a ViewModel's properties edited in
 * place as JSON (feature 031: a preview in the table, the selected value formatted below), a back stack with Navigate… and Back, functions with Invoke…, memory with Run GC.
 */
class RuntimePanel(
    private val project: Project,
    private val scope: CoroutineScope,
    private val ui: (Runnable) -> Unit = { SwingUtilities.invokeLater(it) },
    private val call: suspend (String, JsonObject) -> JsonObject,
) : JPanel(BorderLayout()) {
    private enum class Section(val label: String) { ACTIVITIES("Activities"), NAVIGATION("Navigation"), VIEWMODELS("ViewModels"), FUNCTIONS("Functions"), MEMORY("Memory"), THREADS("Threads"), DI("DI") }
    private data class Item(val section: Section, val label: String, val detail: String = "", val host: NavHostInfo? = null, val vm: ViewModelInfo? = null)

    /** The status line's text (what the tests read). */
    internal val statusLine: String get() = status.text
    private val status = JBLabel(" ").apply { foreground = JBColor.GRAY; setCopyable(true) }
    private val root = DefaultMutableTreeNode()
    private var viewModelsWanted = false
    private val treeModel = DefaultTreeModel(root)
    val tree = Tree(treeModel)
    private val cards = CardLayout()
    private val right = JPanel(cards)
    private val text = JBTextArea().apply { isEditable = false; font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(12f)) }
    private val textActions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0))

    private var rows: PropertyRows? = null
    private val propsModel = object : AbstractTableModel() {
        override fun getRowCount() = rows?.rows?.size ?: 0
        override fun getColumnCount() = 3
        override fun getColumnName(column: Int) = listOf("Property", "Kind", "Value")[column]
        override fun getValueAt(row: Int, column: Int): Any = rows!!.rows[row].let { listOf((if (it.editable) "✎ " else "") + it.label, it.kind, it.preview)[column] }
        // In the cell only a short scalar (its preview is the whole value); the rest in the viewer below (031).
        override fun isCellEditable(row: Int, column: Int) = column == 2 && rows!!.rows[row].let { it.editable && !rows!!.structured(row) && it.preview == it.value }
        override fun setValueAt(value: Any?, row: Int, column: Int) {
            val (op, params) = runCatching { rows!!.edit(row, value?.toString() ?: "") }.getOrElse { status.text = it.message; return }
            val keep = rows!!.rows[row].label
            write(op, JsonObject(params.mapValues { JsonPrimitive(it.value) })) { reloadViewModels(currentVm?.id, keep) }
        }
    }
    val propsTable = JBTable(propsModel).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        // The value preview gets the room; name and kind stay narrow.
        listOf(30, 12, 58).forEachIndexed { i, w -> columnModel.getColumn(i).preferredWidth = w * 10 }
    }
    /** The selected property's value, readable and editable (feature 031); the whole ViewModel when none is chosen. */
    val valueView = JsonValueView(project)
    /** Threads grouped, in words, with the chosen one's stack (feature 032). */
    val threadsView = ThreadsView { showThreads() }
    /** DI graphs as a tree, the chosen binding's links, its neighbourhood or the whole graph drawn (feature 037). */
    val diView = DiView { showDi() }
    /** Heap snapshots by class, their comparison, instances and fields (feature 074); opening it takes no snapshot. */
    val memoryPanel = MemoryPanel(project, scope, ui, openView = { target, ref -> openLinked(target, ref) }, call = call)
    private var currentVm: ViewModelInfo? = null
    private var currentHost: NavHostInfo? = null
    private var functions: FunctionList? = null
    private val body = JPanel(BorderLayout())
    private val splitter = JBSplitter(false, "dta.runtime.split", 0.28f)
    private val sectionCombo = javax.swing.JComboBox(Section.entries.toTypedArray())
    private var syncingCombo = false
    private var narrow = false

    init {
        add(JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6, 12)
            add(JButton("Refresh").apply { addActionListener { load() } }, BorderLayout.WEST)
            add(status.apply { border = JBUI.Borders.emptyLeft(8) }, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(t: javax.swing.JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
                when (val o = (value as? DefaultMutableTreeNode)?.userObject) {
                    is Section -> append(o.label, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    is Item -> { append(o.label); if (o.detail.isNotEmpty()) append("  " + o.detail, SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                }
            }
        }
        tree.addTreeSelectionListener { (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject?.let { open(it) } }
        right.add(JBLabel("Choose a part of the running app on the left.").apply { border = JBUI.Borders.empty(12); foreground = JBColor.GRAY }, EMPTY)
        right.add(JPanel(BorderLayout()).apply {
            add(textActions.apply { border = JBUI.Borders.empty(4, 8) }, BorderLayout.NORTH)
            add(JBScrollPane(text), BorderLayout.CENTER)
        }, TEXT)
        propsTable.toolTipText = "Choose a property to see its value. ✎ values: double-click or Edit (JSON: \"text\", 3, true, null, {…})."
        right.add(adaptiveSplitter("dta.runtime.vm.split", 0.4f, JBScrollPane(propsTable), valueView), PROPS)
        right.add(threadsView, THREADS)
        right.add(diView, DI)
        right.add(memoryPanel, MEMORY)
        propsTable.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) showValue() }
        propsTable.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                val row = propsTable.rowAtPoint(e.point)
                if (e.clickCount == 2 && row >= 0 && !propsModel.isCellEditable(row, propsTable.columnAtPoint(e.point).coerceAtLeast(0))) valueView.beginEdit()
            }
        })
        body.add(splitter, BorderLayout.CENTER)
        add(body, BorderLayout.CENTER)
        splitter.firstComponent = JBScrollPane(tree)
        splitter.secondComponent = right
        sectionCombo.name = "runtime.sections"
        sectionCombo.renderer = javax.swing.ListCellRenderer { list, value, _, selected, _ ->
            JBLabel((value as? Section)?.label ?: "").apply {
                isOpaque = true
                border = JBUI.Borders.empty(2, 6)
                background = if (selected) list.selectionBackground else list.background
                foreground = if (selected) list.selectionForeground else list.foreground
            }
        }
        sectionCombo.addActionListener { if (!syncingCombo) (sectionCombo.selectedItem as? Section)?.let { selectSection(it) } }
        tree.addTreeSelectionListener {
            val o = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject
            val section = (o as? Section) ?: (o as? Item)?.section
            if (section != null && sectionCombo.selectedItem != section) { syncingCombo = true; sectionCombo.selectedItem = section; syncingCombo = false }
        }
        addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) = layoutFor(width in 1 until NARROW)
        })
    }

    /** Wide: the sections beside the view. Narrow (< 700 px): one combo above the view, the connection state stays (round-2 tab rules). */
    private fun layoutFor(isNarrow: Boolean) {
        if (isNarrow == narrow) return
        narrow = isNarrow
        body.removeAll()
        if (isNarrow) {
            splitter.secondComponent = null
            body.add(JPanel(BorderLayout()).apply {
                add(JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(2, 8); add(sectionCombo, BorderLayout.CENTER) }, BorderLayout.NORTH)
                add(right, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
        } else {
            splitter.secondComponent = right
            body.add(splitter, BorderLayout.CENTER)
        }
        body.revalidate(); body.repaint()
    }

    private fun selectSection(section: Section) {
        val node = root.children().asSequence().map { it as DefaultMutableTreeNode }.firstOrNull { it.userObject == section } ?: return
        tree.selectionPath = javax.swing.tree.TreePath(arrayOf<Any>(root, node))
    }

    /** A link from a heap instance: the Runtime view of that kind. */
    private fun openLinked(target: io.yamsergey.dta.present.data.MemoryRows.Target, @Suppress("UNUSED_PARAMETER") ref: String?) {
        selectSection(Section.entries.first { it.label == target.section })
    }

    /** Feature 048: the ViewModels alive now, asked for from the Timeline; read only for this. */
    fun openViewModels() {
        if (root.childCount == 0) {
            viewModelsWanted = true
            load()
        } else {
            selectViewModels()
        }
    }

    private fun selectViewModels() {
        val node = root.children().asSequence().map { it as DefaultMutableTreeNode }.firstOrNull { it.userObject == Section.VIEWMODELS } ?: return
        tree.clearSelection()
        tree.selectionPath = javax.swing.tree.TreePath(arrayOf<Any>(root, node))
    }

    /** Reads navigation hosts, ViewModels and functions again and rebuilds the tree. */
    fun load() {
        // D-103: nothing of the previous app stays; DI, when it was open, is read again for this one.
        val reopenDi = diOpen
        diOpen = false
        diView.clear()
        cards.show(right, EMPTY)
        scope.launch {
            val nav = runCatching { decode(NavigationState.serializer(), call("runtime.navigation", EMPTY_PARAMS)) }
            val vms = runCatching { decode(ViewModelList.serializer(), call("runtime.viewmodels", EMPTY_PARAMS)) }
            val fns = runCatching { decode(FunctionList.serializer(), call("runtime.functions", EMPTY_PARAMS)) }
            ui.invoke(Runnable {
                root.removeAllChildren()
                for (section in Section.entries) {
                    val node = DefaultMutableTreeNode(section)
                    when (section) {
                        Section.NAVIGATION -> nav.getOrNull()?.hosts?.forEach { h ->
                            node.add(DefaultMutableTreeNode(Item(section, h.backStack.lastOrNull()?.let { it.route ?: it.label } ?: h.id, "${h.kind} · ${Units.count(h.backStack.size, "entry", "entries")}", host = h)))
                        }
                        Section.VIEWMODELS -> vms.getOrNull()?.viewModels?.forEach { v ->
                            node.add(DefaultMutableTreeNode(Item(section, v.className.substringAfterLast('.'), v.owner ?: "", vm = v)))
                        }
                        else -> Unit
                    }
                    root.add(node)
                }
                functions = fns.getOrNull()
                treeModel.reload()
                for (i in 0 until tree.rowCount) tree.expandRow(i)
                status.text = listOfNotNull(nav.exceptionOrNull(), vms.exceptionOrNull()).firstOrNull()?.message
                    ?: (nav.getOrNull()?.completeness.orEmpty() + vms.getOrNull()?.completeness.orEmpty()).firstOrNull()?.reason
                    ?: "${Units.count(nav.getOrNull()?.hosts?.size ?: 0, "navigation host")}, ${Units.count(vms.getOrNull()?.viewModels?.size ?: 0, "ViewModel")}."
                if (viewModelsWanted) {
                    viewModelsWanted = false
                    selectViewModels()
                } else if (reopenDi) {
                    root.children().asSequence().map { it as DefaultMutableTreeNode }.firstOrNull { it.userObject == Section.DI }?.let { tree.selectionPath = javax.swing.tree.TreePath(arrayOf<Any>(root, it)) }
                }
            })
        }
    }

    /** Whether the DI section is the one open (so a new app's DI is read again). */
    private var diOpen = false

    private fun open(o: Any) {
        currentHost = null
        diOpen = o == Section.DI
        when (o) {
            Section.ACTIVITIES -> showText("runtime.activities", RuntimeActivities.serializer(), RuntimeText::activities)
            Section.MEMORY -> { cards.show(right, MEMORY); memoryPanel.load() }
            Section.THREADS -> showThreads()
            Section.DI -> showDi()
            Section.FUNCTIONS -> showFunctions()
            Section.NAVIGATION -> showText("runtime.navigation", NavigationState.serializer(), RuntimeText::navigation, navActions())
            Section.VIEWMODELS -> showText("runtime.viewmodels", ViewModelList.serializer(), RuntimeText::viewModels)
            is Item -> when {
                o.vm != null -> showViewModel(o.vm)
                o.host != null -> { currentHost = o.host; text(RuntimeText.navigation(NavigationState(listOf(o.host))), navActions()) }
            }
        }
    }

    private fun navActions(): List<Pair<String, () -> Unit>> = listOf(
        "Navigate…" to {
            val target = Messages.showInputDialog(project, "A route (product/42) or a deep link (scheme://…):", "Navigate", null)?.trim()?.takeIf { it.isNotEmpty() }
            if (target != null) {
                val params = mutableMapOf<String, JsonElement>(if ("://" in target) "uri" to JsonPrimitive(target) else "route" to JsonPrimitive(target))
                currentHost?.let { params["host"] = JsonPrimitive(it.id) }
                write("runtime.navigate", JsonObject(params)) { load() }
            }
        },
        "Back" to { write("runtime.back", JsonObject(currentHost?.let { mapOf("host" to JsonPrimitive(it.id)) } ?: emptyMap())) { load() } },
    )

    private fun showViewModel(vm: ViewModelInfo, select: String? = null) {
        currentVm = vm
        rows = PropertyRows(vm)
        propsModel.fireTableDataChanged()
        val row = rows!!.rows.indexOfFirst { it.label == select }
        if (row >= 0) propsTable.setRowSelectionInterval(row, row) else showValue()
        cards.show(right, PROPS)
        status.text = "${vm.className} · ${vm.id}" + (vm.owner?.let { " · $it" } ?: "")
    }

    /** The viewer follows the table: the selected property, or the whole ViewModel. */
    private fun showValue() {
        val r = rows ?: return
        val vm = currentVm ?: return
        val i = propsTable.selectedRow
        if (i < 0 || i >= r.rows.size) return valueView.show("All properties of ${vm.className.substringAfterLast('.')}", r.whole(), structured = true)
        val label = r.rows[i].label
        valueView.show(r.header(i), r.pretty(i), r.structured(i), onApply = if (!r.rows[i].editable) null else { text ->
            runCatching { r.edit(i, text) }.fold(
                { (op, params) -> write(op, JsonObject(params.mapValues { JsonPrimitive(it.value) })) { reloadViewModels(vm.id, label) } },
                { valueView.refuse(it.message ?: "not JSON") },
            )
        })
    }

    private fun reloadViewModels(selectId: String?, selectRow: String? = null) {
        scope.launch {
            val list = runCatching { decode(ViewModelList.serializer(), call("runtime.viewmodels", EMPTY_PARAMS)) }.getOrNull()
            ui.invoke(Runnable { list?.viewModels?.firstOrNull { it.id == selectId }?.let { v -> val s = status.text; showViewModel(v, selectRow); status.text = s } })
        }
    }

    private fun showDi() {
        cards.show(right, DI)
        scope.launch {
            val result = runCatching { decode(DiReport.serializer(), call("runtime.di", EMPTY_PARAMS)) }
            ui.invoke(Runnable { result.fold({ r -> diView.show(r); status.text = diView.summary }, { status.text = it.message ?: it.javaClass.simpleName }) })
        }
    }

    private fun showThreads() {
        cards.show(right, THREADS)
        scope.launch {
            val result = runCatching { decode(ThreadList.serializer(), call("runtime.threads", EMPTY_PARAMS)) }
            ui.invoke(Runnable {
                result.fold({ list -> threadsView.show(ThreadRows(list), RuntimeText.threads(list)); status.text = "${Units.count(list.threads.size, "thread")}." }, { status.text = it.message ?: it.javaClass.simpleName })
            })
        }
    }

    private fun showFunctions() {
        val f = functions
        text(f?.let(RuntimeText::functions) ?: "Functions not read.", listOf("Invoke…" to { invoke() }))
    }

    private fun invoke() {
        val invocable = functions?.functions?.filter { it.invocable }.orEmpty()
        if (invocable.isEmpty()) return run { status.text = functions?.note ?: "No function to invoke." }
        val id = Messages.showEditableChooseDialog("Function:", "Invoke", null, invocable.map { it.id }.toTypedArray(), invocable.first().id, null) ?: return
        val args = Messages.showMultilineInputDialog(project, "Arguments, one name=value per line (optional):", "Invoke $id", "", null, null) ?: return
        val map = args.lines().filter { '=' in it }.associate { it.substringBefore('=').trim() to JsonPrimitive(it.substringAfter('=').trim()) }
        scope.launch {
            val result = runCatching { decode(InvokeResult.serializer(), call("runtime.invoke", JsonObject(mapOf("id" to JsonPrimitive(id), "args" to JsonObject(map))))) }
            ui.invoke(Runnable {
                status.text = result.fold({ r -> r.error?.let { "$id threw $it" } ?: "$id → ${r.result}" }, { it.message ?: it.javaClass.simpleName })
            })
        }
    }

    private fun <T> showText(op: String, s: KSerializer<T>, render: (T) -> String, actions: List<Pair<String, () -> Unit>> = emptyList(), params: JsonObject = EMPTY_PARAMS) {
        scope.launch {
            val result = runCatching { render(decode(s, call(op, params))) }
            ui.invoke(Runnable { text(result.getOrElse { it.message ?: it.javaClass.simpleName }, actions) })
        }
    }

    private fun text(content: String, actions: List<Pair<String, () -> Unit>>) {
        text.text = content
        text.caretPosition = 0
        textActions.removeAll()
        actions.forEach { (label, action) -> textActions.add(ActionLink(label) { action() }) }
        textActions.isVisible = actions.isNotEmpty()
        textActions.revalidate()
        cards.show(right, TEXT)
    }

    private fun write(op: String, params: JsonObject, then: () -> Unit) {
        status.text = "Working…"
        scope.launch {
            val result = runCatching { call(op, params) }
            ui.invoke(Runnable {
                result.fold({ r -> status.text = (r["change"] as? JsonPrimitive)?.content ?: "Done."; then() }, { status.text = it.message ?: it.javaClass.simpleName })
            })
        }
    }

    private fun <T> decode(s: KSerializer<T>, json: JsonObject): T = DtaJson.decodeFromJsonElement(s, json)

    companion object {
        private const val EMPTY = "empty"
        private const val TEXT = "text"
        private const val PROPS = "props"
        private const val THREADS = "threads"
        private const val DI = "di"
        private const val MEMORY = "memory"
        /** Below this width the sections are a combo above the view, not a list beside it (round-2 tab rules). */
        private const val NARROW = 700
        private val EMPTY_PARAMS = JsonObject(emptyMap())
    }
}
