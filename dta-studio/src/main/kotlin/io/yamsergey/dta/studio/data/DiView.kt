package io.yamsergey.dta.studio.data

import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.present.data.DiBrowser
import io.yamsergey.dta.protocol.data.DiReport
import io.yamsergey.dta.studio.ui.adaptiveSplitter
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Runtime › DI (feature 037): the graphs and their bindings in a tree; the chosen binding on the
 * right — Details (type, instance, what it depends on, what uses it, each a link), Neighbours (a
 * graph two levels around it) or Whole graph (every binding, layered by dependency depth).
 */
class DiView(private val refresh: () -> Unit) : JPanel(BorderLayout()) {
    enum class Mode(val label: String) { DETAILS("Details"), NEIGHBOURS("Neighbours"), WHOLE("Whole graph") }

    /** One line about the report; the Runtime tab's status line shows it. */
    var summary: String = ""
        private set
    private lateinit var switch: com.intellij.ui.dsl.builder.SegmentedButton<Mode>
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    val tree = Tree(model)
    val header = JBLabel(" ").apply { setCopyable(true) }
    private val modes = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
    private val cards = CardLayout()
    private val detail = JPanel(cards)
    val dependsOn = DefaultListModel<DiBrowser.Node>()
    val usedBy = DefaultListModel<DiBrowser.Node>()
    private var di = DiBrowser(DiReport(emptyList()))
    private var canvas = DiGraphCanvas(di) { select(it) }
    private val canvasHolder = JPanel(BorderLayout())
    private var mode = Mode.DETAILS
    var selectedId: String? = null
        private set
    private val nodesById = HashMap<String, DefaultMutableTreeNode>()

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(t: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
                when (val o = (value as? DefaultMutableTreeNode)?.userObject) {
                    is GraphRow -> { append(o.framework, SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES); append("  " + o.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES); append("  ${o.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                    is DiBrowser.Node -> {
                        val created = o.binding.runtimeType != null
                        append(o.title, if (created) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        if (o.binding.name != o.title) append("  " + o.binding.name, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        o.binding.qualifier?.let { append("  @$it", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES) }
                        append("  " + o.state, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    }
                }
            }
        }
        TreeUIHelper.getInstance().installTreeSpeedSearch(tree)
        tree.addTreeSelectionListener { ((tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? DiBrowser.Node)?.let { show(it.id) } }

        val details = JPanel(GridLayout(1, 2, JBUI.scale(8), 0)).apply {
            add(listPanel("Depends on", dependsOn))
            add(listPanel("Used by", usedBy))
        }
        detail.add(details, Mode.DETAILS.name)
        detail.add(JBScrollPane(canvasHolder), "graph")
        val right = JPanel(BorderLayout()).apply {
            add(JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(4, 8)
                add(header, BorderLayout.CENTER)
                add(modes, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(detail, BorderLayout.CENTER)
        }
        add(adaptiveSplitter("dta.runtime.di.split", 0.42f, JBScrollPane(tree), right), BorderLayout.CENTER)
        modes.add(ActionLink("Refresh") { refresh() }.apply { border = JBUI.Borders.empty(0, 12) })
        modes.add(com.intellij.ui.dsl.builder.panel {
            row { switch = segmentedButton(Mode.entries) { text = it.label }.whenItemSelected { if (it != mode) { mode = it; render() } } }
        }.apply { isOpaque = false })
        switch.selectedItem = mode
        render()
    }

    private data class GraphRow(val framework: String, val name: String, val count: Int)

    private fun listPanel(title: String, items: DefaultListModel<DiBrowser.Node>) = JPanel(BorderLayout()).apply {
        add(JBLabel(title).apply { border = JBUI.Borders.empty(2, 8); foreground = JBColor.GRAY }, BorderLayout.NORTH)
        val list = JBList(items).apply {
            cellRenderer = object : ColoredListCellRenderer<DiBrowser.Node>() {
                override fun customizeCellRenderer(l: JList<out DiBrowser.Node>, v: DiBrowser.Node?, i: Int, s: Boolean, f: Boolean) {
                    v ?: return
                    append(v.title, SimpleTextAttributes.LINK_ATTRIBUTES)
                    append("  " + v.binding.name + "  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append(v.graph, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
            }
            emptyText.text = "None"
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) { locationToIndex(e.point).takeIf { it >= 0 }?.let { select(items.getElementAt(it).id) } }
            })
        }
        add(JBScrollPane(list), BorderLayout.CENTER)
    }

    /** Shows [report]; the chosen binding stays chosen when it is still there. */
    fun show(report: DiReport) {
        di = DiBrowser(report)
        canvas = DiGraphCanvas(di) { select(it) }
        canvasHolder.removeAll(); canvasHolder.add(canvas, BorderLayout.CENTER)
        val keep = selectedId
        root.removeAllChildren(); nodesById.clear()
        report.graphs.forEachIndexed { gi, g ->
            val gn = DefaultMutableTreeNode(GraphRow(g.framework, g.name, g.bindings.size))
            di.nodes.filter { it.graph == g.name && it.id.startsWith("$gi.") }.forEach { n -> DefaultMutableTreeNode(n).also { gn.add(it); nodesById[n.id] = it } }
            root.add(gn)
        }
        model.reload()
        for (i in 0 until tree.rowCount) tree.expandRow(i)
        summary = if (report.graphs.isEmpty()) report.note ?: "No DI graph." else
            "${report.graphs.size} graphs, ${di.nodes.size} bindings, ${di.nodes.count { it.binding.runtimeType != null }} created" + (report.note?.let { " · $it" } ?: "")
        (keep?.takeIf { it in nodesById } ?: di.nodes.firstOrNull()?.id)?.let { select(it) } ?: run { selectedId = null; render() }
    }

    /** Chooses [id] in the tree (it scrolls there) and on the right. */
    fun select(id: String) {
        val node = nodesById[id] ?: return
        val path = TreePath(node.path)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
        show(id)
    }

    private fun show(id: String) {
        selectedId = id
        render()
    }

    /** Shows a mode (what the switch does). */
    fun mode(m: Mode) { mode = m; switch.selectedItem = m; render() }

    private fun render() {
        val id = selectedId
        val n = id?.let(di::node)
        header.text = n?.let { listOfNotNull(it.title, it.binding.name.takeIf { x -> x != it.title }, it.binding.qualifier?.let { q -> "@$q" }, it.state).joinToString(" · ") } ?: " "
        header.toolTipText = n?.let { listOfNotNull(it.binding.type, it.binding.runtimeType?.let { r -> "instance: $r" }, "graph: ${it.graph} (${it.framework})").joinToString("<br>", "<html>", "</html>") }
        dependsOn.clear(); usedBy.clear()
        if (id != null) { di.dependsOn(id).forEach(dependsOn::addElement); di.usedBy(id).forEach(usedBy::addElement) }
        when (mode) {
            Mode.DETAILS -> cards.show(detail, Mode.DETAILS.name)
            Mode.NEIGHBOURS -> { canvas.show(id?.let { di.neighbourhood(it) } ?: DiBrowser.Layout(emptyList(), emptyList()), id); cards.show(detail, "graph") }
            Mode.WHOLE -> { canvas.show(di.whole(), id); cards.show(detail, "graph") }
        }
    }
}
