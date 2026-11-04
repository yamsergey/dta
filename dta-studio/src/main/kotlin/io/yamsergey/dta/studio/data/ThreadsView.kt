package io.yamsergey.dta.studio.data

import com.intellij.openapi.ide.CopyPasteManager
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
import io.yamsergey.dta.present.data.ThreadRows
import io.yamsergey.dta.studio.ui.adaptiveSplitter
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * The Runtime tab's threads (feature 032): a health line, threads grouped App / System / DTA with
 * their state in words and the frame that matters, the chosen thread's stack beside it with the
 * app's own frames standing out; Copy stack, Copy all (the text dump), Refresh.
 */
class ThreadsView(private val refresh: () -> Unit) : JPanel(BorderLayout()) {
    val health = JBLabel(" ").apply { setCopyable(true) }
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    val tree = Tree(model)
    val stackHeader = JBLabel(" ").apply { setCopyable(true) }
    private val frames = DefaultListModel<ThreadRows.Frame>()
    val stack = JBList(frames)
    private var dump = ""

    init {
        add(JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(4, 8))
            add(health, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
                add(ActionLink("Refresh") { refresh() })
                add(ActionLink("Copy all") { CopyPasteManager.getInstance().setContents(StringSelection(dump)) })
            }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(t: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
                when (val o = (value as? DefaultMutableTreeNode)?.userObject) {
                    is ThreadRows.Group -> { append(o.label, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES); append("  ${o.threads.size}", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                    is ThreadRows.Row -> {
                        append(o.name)
                        append("  " + o.stateText, if (o.info.state == "blocked") SimpleTextAttributes.ERROR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        // An idle looper's frame says nothing the state does not.
                        if (o.topFrame.isNotEmpty() && !o.stateText.startsWith("idle")) append("  " + o.topFrame.substringBefore('('), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    }
                }
            }
        }
        TreeUIHelper.getInstance().installTreeSpeedSearch(tree)
        tree.addTreeSelectionListener { showStack((tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? ThreadRows.Row) }
        stack.cellRenderer = object : ColoredListCellRenderer<ThreadRows.Frame>() {
            override fun customizeCellRenderer(list: JList<out ThreadRows.Frame>, value: ThreadRows.Frame?, index: Int, selected: Boolean, hasFocus: Boolean) {
                value ?: return
                append("at ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append(value.text, if (value.app) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        stack.emptyText.text = "Choose a thread to see its stack."
        val detail = JPanel(BorderLayout()).apply {
            add(JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(4, 8)
                add(stackHeader, BorderLayout.CENTER)
                add(ActionLink("Copy stack") {
                    val row = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? ThreadRows.Row ?: return@ActionLink
                    CopyPasteManager.getInstance().setContents(StringSelection(row.header + row.frames.joinToString("") { "\n    at ${it.text}" }))
                }, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(JBScrollPane(stack), BorderLayout.CENTER)
        }
        add(adaptiveSplitter("dta.runtime.threads.split", 0.45f, JBScrollPane(tree), detail), BorderLayout.CENTER)
    }

    /** Shows [rows]; [text] is the dump Copy all copies. The chosen thread stays chosen when it is still there. */
    fun show(rows: ThreadRows, text: String) {
        val keep = ((tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? ThreadRows.Row)?.name
        dump = text
        health.text = rows.health
        health.foreground = if ("blocked:" in rows.health || "Main thread blocked" in rows.health) JBColor.RED else JBColor.foreground()
        root.removeAllChildren()
        var select: DefaultMutableTreeNode? = null
        rows.groups.forEach { g ->
            val node = DefaultMutableTreeNode(g)
            g.threads.forEach { r -> DefaultMutableTreeNode(r).also { node.add(it); if (r.name == keep) select = it } }
            root.add(node)
        }
        model.reload()
        root.children().toList().forEach { n -> val g = (n as DefaultMutableTreeNode).userObject as ThreadRows.Group; if (g.expanded || select?.parent === n) tree.expandPath(TreePath(n.path)) }
        select?.let { tree.selectionPath = TreePath(it.path) } ?: showStack(null)
    }

    private fun showStack(row: ThreadRows.Row?) {
        frames.clear()
        stackHeader.text = row?.header ?: " "
        row?.frames?.forEach(frames::addElement)
        stack.emptyText.text = if (row == null) "Choose a thread to see its stack." else "No Java frames: this thread is in native code."
    }
}
