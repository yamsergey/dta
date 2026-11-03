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
import io.yamsergey.dta.protocol.data.HiltBindings
import io.yamsergey.dta.protocol.data.InvokeResult
import io.yamsergey.dta.protocol.data.MemoryState
import io.yamsergey.dta.protocol.data.NavHostInfo
import io.yamsergey.dta.protocol.data.NavigationState
import io.yamsergey.dta.protocol.data.RuntimeActivities
import io.yamsergey.dta.protocol.data.ThreadList
import io.yamsergey.dta.protocol.data.ViewModelInfo
import io.yamsergey.dta.protocol.data.ViewModelList
import io.yamsergey.dta.protocol.rpc.DtaJson
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
 * threads and Hilt in a tree; the selected part on the right — a ViewModel's properties edited in
 * place as JSON, a back stack with Navigate… and Back, functions with Invoke…, memory with Run GC.
 */
class RuntimePanel(
    private val project: Project,
    private val scope: CoroutineScope,
    private val ui: (Runnable) -> Unit = { SwingUtilities.invokeLater(it) },
    private val call: suspend (String, JsonObject) -> JsonObject,
) : JPanel(BorderLayout()) {
    private enum class Section(val label: String) { ACTIVITIES("Activities"), NAVIGATION("Navigation"), VIEWMODELS("ViewModels"), FUNCTIONS("Functions"), MEMORY("Memory"), THREADS("Threads"), HILT("Hilt") }
    private data class Item(val section: Section, val label: String, val detail: String = "", val host: NavHostInfo? = null, val vm: ViewModelInfo? = null)

    private val status = JBLabel(" ").apply { foreground = JBColor.GRAY; setCopyable(true) }
    private val root = DefaultMutableTreeNode()
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
        override fun getColumnName(column: Int) = listOf("Property", "Kind", "Value (JSON)")[column]
        override fun getValueAt(row: Int, column: Int): Any = rows!!.rows[row].let { listOf((if (it.editable) "✎ " else "") + it.label, it.kind, it.value)[column] }
        override fun isCellEditable(row: Int, column: Int) = column == 2 && rows!!.rows[row].editable
        override fun setValueAt(value: Any?, row: Int, column: Int) {
            val (op, params) = runCatching { rows!!.edit(row, value?.toString() ?: "") }.getOrElse { status.text = it.message; return }
            write(op, JsonObject(params.mapValues { JsonPrimitive(it.value) })) { reloadViewModels(rows?.let { currentVm?.id }) }
        }
    }
    val propsTable = JBTable(propsModel).apply { setSelectionMode(ListSelectionModel.SINGLE_SELECTION) }
    private var currentVm: ViewModelInfo? = null
    private var currentHost: NavHostInfo? = null
    private var functions: FunctionList? = null

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
        right.add(JPanel(BorderLayout()).apply {
            add(JBScrollPane(propsTable), BorderLayout.CENTER)
            add(JBLabel("Double-click a ✎ value to set it (JSON: \"text\", 3, true, null, {…}). A field inside a data class: run dta runtime vm set with a path like state.coupon.").apply {
                foreground = JBColor.GRAY; border = JBUI.Borders.empty(4, 8)
            }, BorderLayout.SOUTH)
        }, PROPS)
        add(JBSplitter(false, "dta.runtime.split", 0.28f).apply {
            firstComponent = JBScrollPane(tree)
            secondComponent = right
        }, BorderLayout.CENTER)
    }

    /** Reads navigation hosts, ViewModels and functions again and rebuilds the tree. */
    fun load() {
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
                            node.add(DefaultMutableTreeNode(Item(section, h.backStack.lastOrNull()?.let { it.route ?: it.label } ?: h.id, "${h.kind} · ${h.backStack.size} entries", host = h)))
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
                    ?: "${nav.getOrNull()?.hosts?.size ?: 0} navigation hosts, ${vms.getOrNull()?.viewModels?.size ?: 0} ViewModels."
            })
        }
    }

    private fun open(o: Any) {
        currentHost = null
        when (o) {
            Section.ACTIVITIES -> showText("runtime.activities", RuntimeActivities.serializer(), RuntimeText::activities)
            Section.MEMORY -> showText("runtime.memory", MemoryState.serializer(), RuntimeText::memory, listOf("Run GC" to { showText("runtime.memory", MemoryState.serializer(), RuntimeText::memory, params = JsonObject(mapOf("gc" to JsonPrimitive(true)))) }))
            Section.THREADS -> showText("runtime.threads", ThreadList.serializer(), RuntimeText::threads)
            Section.HILT -> showText("runtime.hilt", HiltBindings.serializer(), RuntimeText::hilt)
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

    private fun showViewModel(vm: ViewModelInfo) {
        currentVm = vm
        rows = PropertyRows(vm)
        propsModel.fireTableDataChanged()
        cards.show(right, PROPS)
        status.text = "${vm.className} · ${vm.id}" + (vm.owner?.let { " · $it" } ?: "")
    }

    private fun reloadViewModels(selectId: String?) {
        scope.launch {
            val list = runCatching { decode(ViewModelList.serializer(), call("runtime.viewmodels", EMPTY_PARAMS)) }.getOrNull()
            ui.invoke(Runnable { list?.viewModels?.firstOrNull { it.id == selectId }?.let { v -> val s = status.text; showViewModel(v); status.text = s } })
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
        private val EMPTY_PARAMS = JsonObject(emptyMap())
    }
}
