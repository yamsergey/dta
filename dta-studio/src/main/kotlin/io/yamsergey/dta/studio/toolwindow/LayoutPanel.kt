package io.yamsergey.dta.studio.toolwindow

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.present.context.Banner
import io.yamsergey.dta.studio.ui.textRenderer
import io.yamsergey.dta.present.layout.LayoutTree
import io.yamsergey.dta.present.layout.NodeDetail
import io.yamsergey.dta.present.layout.SearchResult
import io.yamsergey.dta.present.layout.TreeNode
import io.yamsergey.dta.present.layout.WindowRow
import io.yamsergey.dta.protocol.layout.DetailLevel
import io.yamsergey.dta.studio.layout.LayoutController
import io.yamsergey.dta.studio.layout.LayoutViewState
import io.yamsergey.dta.studio.layout.NodeDetailPane
import io.yamsergey.dta.studio.layout.SourceNavigator
import io.yamsergey.dta.studio.ui.BannerPanel
import io.yamsergey.dta.studio.ui.DtaColors
import io.yamsergey.dta.studio.ui.Keyboard
import io.yamsergey.dta.studio.ui.ViewKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.DocumentEvent
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * The Layout view (US2, contracts/tool-window.md): the capture's banners, the windows' Views and
 * composables in one tree with search, and the selected node's detail. Everything shown comes
 * from [LayoutController]'s state; this class only binds it.
 */
class LayoutPanel(
    private val project: Project,
    private val controller: LayoutController,
    private val scope: CoroutineScope,
) : JPanel(BorderLayout()) {
    private val model = DefaultTreeModel(DefaultMutableTreeNode())
    private val tree = Tree(model).apply {
        isRootVisible = false
        showsRootHandles = true
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        cellRenderer = NodeRenderer()
    }
    private val nodes = HashMap<List<Int>, DefaultMutableTreeNode>()
    private val banners = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val summary = JBLabel().apply { foreground = JBColor.GRAY }
    private val detailLevel = ComboBox(DetailLevel.entries.toTypedArray()).apply {
        renderer = io.yamsergey.dta.studio.ui.textRenderer { it.name.lowercase() }
    }
    private val search = SearchTextField(false).apply { textEditor.emptyText.text = "Search name, text, id, test tag" }
    private val position = JBLabel().apply { foreground = JBColor.GRAY }
    private val hide = JBCheckBox("Only matches")
    private val status = JBLabel("", javax.swing.SwingConstants.CENTER).apply { foreground = JBColor.GRAY }
    private val navigator = SourceNavigator(project)
    private val detail = NodeDetailPane(
        onAncestor = { depth -> last.selected?.let { controller.select(it.take(depth)) } },
        openSource = { source, anchor, onMissing -> navigator.open(source, last.app, anchor, onMissing) },
    )
    private val splitter = JBSplitter(false, 0.45f)
    /** Feature 014: the picture beside the tree, between the tree and the detail. */
    private val screen = io.yamsergey.dta.studio.layout.ScreenPane(controller)
    private val screenCaption = JBLabel("").apply { foreground = JBColor.GRAY; border = JBUI.Borders.empty(2, 8) }
    private val screenToggle = JBCheckBox("Screen", true)
    private val screenPanel = JPanel(BorderLayout()).apply {
        add(screen, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            add(screenCaption, BorderLayout.CENTER)
            add(ActionLink("Next under pointer (N)") { controller.nextUnderPointer() }, BorderLayout.EAST)
        }, BorderLayout.SOUTH)
    }
    private val inner = JBSplitter(false, 0.5f)
    private var last = LayoutViewState()
    private var updating = false

    init {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(2))).apply {
            add(ActionLink("Refresh") { controller.refresh() })
            add(JBLabel("Detail:"))
            add(detailLevel)
            add(search)
            add(ActionLink("↑") { controller.previousMatch() })
            add(ActionLink("↓") { controller.nextMatch() })
            add(position)
            add(hide)
            add(screenToggle)
        }
        val master = JPanel(BorderLayout()).apply {
            add(JPanel(BorderLayout()).apply {
                add(toolbar, BorderLayout.NORTH)
                add(banners, BorderLayout.CENTER)
                add(summary.apply { border = JBUI.Borders.empty(2, 8) }, BorderLayout.SOUTH)
            }, BorderLayout.NORTH)
            add(JBScrollPane(tree), BorderLayout.CENTER)
            add(status, BorderLayout.SOUTH)
        }
        splitter.firstComponent = master
        inner.firstComponent = screenPanel
        inner.secondComponent = detail
        splitter.secondComponent = inner
        add(splitter, BorderLayout.CENTER)
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) { splitter.orientation = width < JBUI.scale(900) }
        })

        detailLevel.addActionListener { if (!updating) (detailLevel.selectedItem as? DetailLevel)?.let { if (it != last.detail) controller.setDetail(it) } }
        search.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) { if (!updating) controller.search(search.text) }
        })
        search.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when {
                    e.keyCode == KeyEvent.VK_ENTER && e.isShiftDown -> controller.previousMatch()
                    e.keyCode == KeyEvent.VK_ENTER -> controller.nextMatch()
                    e.keyCode == KeyEvent.VK_ESCAPE -> tree.requestFocusInWindow()
                    else -> return
                }
                e.consume()
            }
        })
        hide.addActionListener { controller.setHideNonMatching(hide.isSelected) }
        screenToggle.addActionListener { controller.setScreenShown(screenToggle.isSelected) }
        Keyboard.bind(this, ViewKey.NEXT_UNDER_POINTER) { controller.nextUnderPointer() }
        tree.addTreeSelectionListener {
            if (updating) return@addTreeSelectionListener
            controller.select(((tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? TreeNode)?.path)
        }
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) = expansion(event, true)
            override fun treeCollapsed(event: TreeExpansionEvent) = expansion(event, false)
        })
        Keyboard.bind(this, ViewKey.SEARCH) { search.requestFocusInWindow() }
        Keyboard.bind(this, ViewKey.COPY) { copySelected() }
        Keyboard.bind(this, ViewKey.OPEN) { detail.requestFocusInWindow() }
        Keyboard.bind(this, ViewKey.BACK) { tree.requestFocusInWindow() }

        scope.launch { controller.state.collect { s -> withContext(Dispatchers.Main) { show(s) } } }
    }

    private fun expansion(event: TreeExpansionEvent, expanded: Boolean) {
        if (updating) return
        val node = (event.path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? TreeNode ?: return
        controller.setExpanded(node.path, expanded)
    }

    private fun show(s: LayoutViewState) {
        val previous = last
        last = s
        updating = true
        try {
            if (s.tree !== previous.tree || s.search != previous.search || s.hideNonMatching != previous.hideNonMatching || nodes.isEmpty()) rebuild(s)
            if (s.tree !== previous.tree) showHeader(s.tree)
            if (s.expanded != previous.expanded || s.tree !== previous.tree) applyExpansion(s.expanded)
            val selected = s.selected?.let { nodes[it] }
            if (selected == null) tree.clearSelection()
            else TreePath(selected.path).let { if (tree.selectionPath != it) { tree.selectionPath = it; tree.scrollPathToVisible(it) } }
            if (detailLevel.selectedItem != s.detail) detailLevel.selectedItem = s.detail
            if (s.search == null && search.text.isNotEmpty() && previous.search != null) search.text = ""
            hide.isSelected = s.hideNonMatching
            screenToggle.isSelected = s.screenShown
            if (screenPanel.isVisible != s.screenShown) { screenPanel.isVisible = s.screenShown; inner.revalidate() }
            screen.show(s)
            screenCaption.text = screen.caption(s)
            position.text = s.search?.position(s.selected) ?: ""
            status.text = when {
                s.device == null -> "Choose a device to see its screen."
                s.loading && s.tree == null -> "Capturing the screen…"
                s.error != null -> "Capture failed: ${s.error}"
                s.loading -> "Refreshing…"
                else -> ""
            }
            status.foreground = if (s.error != null) DtaColors.of("status-server") else JBColor.GRAY
            val tree = s.tree
            detail.show(if (tree != null && s.selected != null) NodeDetail.of(tree, s.selected) else null, s.note)
        } finally {
            updating = false
        }
    }

    private fun showHeader(tree: LayoutTree?) {
        banners.removeAll()
        tree?.statements?.forEach { st ->
            banners.add(BannerPanel().apply { show(Banner(st.kind, st.sentence + (st.code?.let { " ($it)" } ?: ""))) })
        }
        summary.text = tree?.summary ?: ""
        banners.revalidate()
    }

    /** One Swing node per tree node; with "only matches", non-matching branches are left out. */
    private fun rebuild(s: LayoutViewState) {
        nodes.clear()
        val root = DefaultMutableTreeNode()
        val search = s.search?.takeIf { s.hideNonMatching }
        fun add(parent: DefaultMutableTreeNode, n: TreeNode) {
            if (search != null && !search.visible(n.path)) return
            val node = DefaultMutableTreeNode(n)
            nodes[n.path] = node
            parent.add(node)
            n.children.forEach { add(node, it) }
        }
        s.tree?.windows?.forEach { w ->
            val window = DefaultMutableTreeNode(w)
            root.add(window)
            add(window, w.root)
        }
        model.setRoot(root)
        (0 until root.childCount).forEach { tree.expandPath(TreePath(arrayOf(root, root.getChildAt(it)))) }
    }

    private fun applyExpansion(expanded: Set<List<Int>>) {
        nodes.entries.sortedBy { it.key.size }.forEach { (path, node) ->
            val treePath = TreePath(node.path)
            if (path in expanded) tree.expandPath(treePath) else if (tree.isExpanded(treePath)) tree.collapsePath(treePath)
        }
    }

    private fun copySelected() {
        val node = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? TreeNode ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(node.row.line))
    }

    private inner class NodeRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            when (val o = (value as? DefaultMutableTreeNode)?.userObject) {
                is WindowRow -> append(o.label, SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
                is TreeNode -> node(o, last.search)
            }
        }

        private fun node(n: TreeNode, search: SearchResult?) {
            val r = n.row
            val dimmed = search?.dimmed(n.path) == true
            val matched = search?.matches?.contains(n.path) == true
            val muted = SimpleTextAttributes.GRAYED_ATTRIBUTES
            append(r.kind.tag + " ", if (dimmed) muted else SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, DtaColors.of(r.kind.token)))
            append(r.name, when {
                matched -> SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD or SimpleTextAttributes.STYLE_SEARCH_MATCH, null)
                dimmed -> muted
                else -> SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
            })
            if (r.ids.isNotEmpty()) append("  " + r.ids.joinToString(" "), muted)
            r.text?.let { append("  $it", if (dimmed) muted else SimpleTextAttributes.REGULAR_ATTRIBUTES) }
            if (r.interactions.isNotEmpty()) append("  " + r.interactions.joinToString(","), muted)
            r.source?.let { append("  $it", muted) }
            if (r.disabled) append("  disabled", muted)
            r.statements.forEach { append("  ${it.kind.glyph} ${it.word}", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, DtaColors.text(it.kind))) }
            toolTipText = r.statements.takeIf { it.isNotEmpty() }?.joinToString("<br>", "<html>", "</html>") { it.sentence + (it.code?.let { c -> " ($c)" } ?: "") }
        }
    }
}
