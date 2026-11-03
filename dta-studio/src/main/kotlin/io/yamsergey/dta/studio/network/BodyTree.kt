package io.yamsergey.dta.studio.network

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import io.yamsergey.dta.present.network.DataNode
import io.yamsergey.dta.present.network.NodeKind
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * The body as an expandable tree (feature 022, US2): key, value coloured as the editor colours
 * it, counts on objects, arrays and elements; copy a value or its path; type to search.
 * [onSelect] hears the selected node's path (the graph follows it).
 */
class BodyTree(root: DataNode, private val onSelect: (String) -> Unit) : JPanel(BorderLayout()) {
    private val nodes = HashMap<String, DefaultMutableTreeNode>()
    private val tree = Tree(DefaultTreeModel(build(root))).apply {
        isRootVisible = true
        showsRootHandles = true
        cellRenderer = Renderer()
    }
    private var quiet = false

    init {
        add(JBScrollPane(tree), BorderLayout.CENTER)
        // The root and its members open; the rest on demand.
        tree.expandPath(TreePath((tree.model.root as DefaultMutableTreeNode).path))
        TreeUIHelper.getInstance().installTreeSpeedSearch(tree)
        tree.addTreeSelectionListener { if (!quiet) selected()?.let { onSelect(it.path) } }
        val group = DefaultActionGroup(
            action("Copy Value") { it.copyValue },
            action("Copy Path") { it.path },
        )
        PopupHandler.installPopupMenu(tree, group, "DtaBodyTree")
        tree.registerKeyboardAction({ copy { it.copyValue } }, KeyStroke.getKeyStroke(KeyEvent.VK_C, java.awt.Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx), JComponent.WHEN_FOCUSED)
    }

    /** Selects and reveals the node at [path] without telling [onSelect]. */
    fun select(path: String) {
        val node = nodes[path] ?: return
        quiet = true
        try {
            val tp = TreePath(node.path)
            tree.selectionPath = tp
            tree.scrollPathToVisible(tp)
        } finally {
            quiet = false
        }
    }

    fun expandAll(expand: Boolean) {
        if (expand) TreeUtil.expandAll(tree) else { TreeUtil.collapseAll(tree, 1) }
    }

    private fun selected(): DataNode? = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? DataNode

    private fun copy(what: (DataNode) -> String) {
        selected()?.let { CopyPasteManager.getInstance().setContents(StringSelection(what(it))) }
    }

    private fun action(name: String, what: (DataNode) -> String) = object : DumbAwareAction(name) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = selected() != null }
        override fun actionPerformed(e: AnActionEvent) = copy(what)
    }

    private fun build(node: DataNode): DefaultMutableTreeNode = DefaultMutableTreeNode(node).also { t ->
        nodes[node.path] = t
        node.children.forEach { t.add(build(it)) }
    }

    private class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            val node = (value as? DefaultMutableTreeNode)?.userObject as? DataNode ?: return
            append(node.title, attributes(if (node.kind == NodeKind.ELEMENT) DefaultLanguageHighlighterColors.MARKUP_TAG else DefaultLanguageHighlighterColors.INSTANCE_FIELD))
            if (node.container) {
                append("  ${node.summary}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                node.value?.let { append("  $it", valueAttributes(NodeKind.STRING)) }
            } else {
                append(": ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append(node.value ?: "", valueAttributes(node.kind))
            }
        }
    }

    companion object {
        fun valueKey(kind: NodeKind): TextAttributesKey = when (kind) {
            NodeKind.STRING, NodeKind.ATTRIBUTE -> DefaultLanguageHighlighterColors.STRING
            NodeKind.NUMBER -> DefaultLanguageHighlighterColors.NUMBER
            NodeKind.BOOLEAN, NodeKind.NULL -> DefaultLanguageHighlighterColors.KEYWORD
            NodeKind.ELEMENT -> DefaultLanguageHighlighterColors.MARKUP_TAG
            NodeKind.OBJECT, NodeKind.ARRAY -> DefaultLanguageHighlighterColors.IDENTIFIER
        }

        fun valueAttributes(kind: NodeKind) = attributes(valueKey(kind))

        fun attributes(key: TextAttributesKey): SimpleTextAttributes =
            EditorColorsManager.getInstance().globalScheme.getAttributes(key)?.let { SimpleTextAttributes.fromTextAttributes(it) } ?: SimpleTextAttributes.REGULAR_ATTRIBUTES
    }
}
