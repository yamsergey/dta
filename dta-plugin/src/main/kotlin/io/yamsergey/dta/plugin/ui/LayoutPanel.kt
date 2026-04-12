package io.yamsergey.dta.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.plugin.DtaService
import io.yamsergey.dta.plugin.DtaService.DtaServiceListener
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.Enumeration
import javax.imageio.ImageIO
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Layout inspection panel with a split view:
 * - Left: device screenshot with highlight overlay
 * - Right: layout tree hierarchy
 *
 * Tree state (expansion, selection, scroll position) is preserved across
 * data refreshes. Identical JSON responses are skipped entirely. Parsing
 * uses Jackson (available via dta-daemon transitive dep) instead of the
 * old hand-rolled regex parser that couldn't handle the Chrome Custom Tab
 * enriched response format.
 */
class LayoutPanel : JPanel(BorderLayout()), DtaServiceListener {

    private val mapper = ObjectMapper()
    private val screenshotPanel = ScreenshotPanel()
    private val rootNode = DefaultMutableTreeNode("No data")
    private val treeModel = DefaultTreeModel(rootNode)
    private val layoutTree = Tree(treeModel)

    private val splitter = JBSplitter(false, 0.5f).apply {
        firstComponent = JBScrollPane(screenshotPanel)
        secondComponent = JBScrollPane(layoutTree)
    }

    /** Last JSON string received — skip rebuild when unchanged. */
    private var lastTreeJson: String? = null

    init {
        add(splitter, BorderLayout.CENTER)

        layoutTree.isRootVisible = true
        layoutTree.showsRootHandles = true
        layoutTree.addTreeSelectionListener { e ->
            val node = e.path?.lastPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            val data = node.userObject as? LayoutNodeData ?: return@addTreeSelectionListener
            screenshotPanel.highlightBounds = data.bounds
            screenshotPanel.repaint()

            // Sync selection to daemon so MCP/CLI/inspector-web can see it
            if (data.bounds != null) {
                val service = DtaService.getInstance()
                val pkg = service.selectedApp?.packageName() ?: return@addTreeSelectionListener
                val device = service.selectedDevice?.serial()
                val json = """{"className":"${data.type}","text":${if (data.text != null) "\"${data.text}\"" else "null"},"bounds":{"left":${data.bounds.x},"top":${data.bounds.y},"right":${data.bounds.x + data.bounds.width},"bottom":${data.bounds.y + data.bounds.height}}}"""
                ApplicationManager.getApplication().executeOnPooledThread {
                    service.syncElementSelection(pkg, device, json)
                }
            }
        }

        DtaService.getInstance().addListener(this)
    }

    override fun onLayoutDataChanged(treeJson: String?, screenshotBytes: ByteArray?) {
        if (screenshotBytes != null) {
            try {
                val image = ImageIO.read(ByteArrayInputStream(screenshotBytes))
                screenshotPanel.setScreenshot(image)
            } catch (_: Exception) {
                screenshotPanel.setScreenshot(null)
            }
        }

        if (treeJson != null) {
            rebuildTree(treeJson)
        } else {
            lastTreeJson = null
            rootNode.removeAllChildren()
            rootNode.userObject = "No data"
            treeModel.reload()
        }
    }

    // ========================================================================
    // Tree rebuild with state preservation
    // ========================================================================

    private fun rebuildTree(json: String) {
        // Skip identical refreshes — the daemon returns the same tree on
        // each poll when nothing changed. This is the single biggest win
        // for stopping the "constant redraw + collapse" problem.
        if (json == lastTreeJson) return
        lastTreeJson = json

        // Capture expansion/selection/scroll state BEFORE rebuilding
        val expandedKeys = captureExpandedKeys()
        val selectedKey = captureSelectedKey()
        val scrollPane = (layoutTree.parent?.parent as? JBScrollPane)
        val scrollPos = scrollPane?.viewport?.viewPosition

        // Rebuild tree from JSON
        rootNode.removeAllChildren()
        try {
            val tree = mapper.readTree(json)
            val rootJsonNode = findRootNode(tree)
            if (rootJsonNode != null) {
                rootNode.userObject = "Layout"
                val child = parseJsonNode(rootJsonNode)
                rootNode.add(child)
            } else {
                rootNode.userObject = "Empty layout"
            }
        } catch (e: Exception) {
            rootNode.userObject = "Parse error: ${e.message}"
        }
        treeModel.reload()

        // Restore state — match by stable key (className + childIndex path)
        if (expandedKeys.isNotEmpty()) {
            restoreExpandedKeys(rootNode, expandedKeys)
        } else {
            // First load: expand the first two levels
            for (i in 0 until layoutTree.rowCount.coerceAtMost(20)) {
                layoutTree.expandRow(i)
            }
        }
        if (selectedKey != null) {
            restoreSelectedKey(rootNode, selectedKey)
        }
        if (scrollPos != null) {
            SwingUtilities.invokeLater {
                scrollPane?.viewport?.viewPosition = scrollPos
            }
        }
    }

    /**
     * Finds the root layout node in the daemon's response. Handles:
     * - `{"root": {...}}` — standard enriched response (including ChromeCustomTab)
     * - `{"windows": [{"tree": {...}}, ...]}` — multi-window response
     * - Direct object — single node
     */
    private fun findRootNode(tree: JsonNode): JsonNode? {
        // Enriched response with "root" (normal case + Chrome Custom Tab)
        val root = tree.get("root")
        if (root != null && root.isObject) return root

        // Multi-window: extract first window's tree
        val windows = tree.get("windows")
        if (windows != null && windows.isArray) {
            for (w in windows) {
                val windowTree = w.get("tree")
                if (windowTree != null && windowTree.isObject) return windowTree
            }
        }

        // Direct node
        if (tree.isObject && tree.has("className")) return tree

        return null
    }

    // ========================================================================
    // Jackson-based node parsing
    // ========================================================================

    private fun parseJsonNode(node: JsonNode): DefaultMutableTreeNode {
        val nodeType = node.path("nodeType").asText(null)
        val role = node.path("role").asText(null)
        // For web nodes (Chrome Custom Tab / WebView DOM), use role as the
        // primary name since they don't have className/composable. For native
        // nodes, prefer className → composable → role → "Unknown".
        val className = if (nodeType == "web" && !role.isNullOrEmpty()) {
            role
        } else {
            node.path("className").asText(
                node.path("composable").asText(
                    role ?: "Unknown"
                )
            )
        }
        val text = node.path("text").asText(null)
        val recompCount = if (node.has("recompositionCount")) node.get("recompositionCount").asInt() else null
        val webViewUrl = node.path("webViewUrl").asText(null)

        // Parse bounds
        val boundsNode = node.get("bounds")
        val bounds = if (boundsNode != null && boundsNode.isObject) {
            val left = boundsNode.path("left").asInt(boundsNode.path("x").asInt(0))
            val top = boundsNode.path("top").asInt(boundsNode.path("y").asInt(0))
            val right = boundsNode.path("right").asInt(left)
            val bottom = boundsNode.path("bottom").asInt(top)
            val w = boundsNode.path("width").asInt(right - left)
            val h = boundsNode.path("height").asInt(bottom - top)
            if (w > 0 || h > 0) Rectangle(left, top, w, h) else null
        } else null

        val label = buildString {
            append(className.substringAfterLast('.'))
            if (nodeType == "web") append(" ⌂")
            if (!role.isNullOrEmpty()) append(" ($role)")
            if (!text.isNullOrEmpty()) append(" \"${text.take(40)}\"")
            if (!webViewUrl.isNullOrEmpty()) append(" → ${webViewUrl.take(50)}")
            if (recompCount != null && recompCount > 0) append(" [$recompCount]")
            if (bounds != null) append(" [${bounds.x},${bounds.y} ${bounds.width}×${bounds.height}]")
        }

        val data = LayoutNodeData(label, className, text, bounds, recompCount)
        val treeNode = DefaultMutableTreeNode(data)

        // Recurse children
        val children = node.get("children")
        if (children != null && children.isArray) {
            for (child in children) {
                treeNode.add(parseJsonNode(child))
            }
        }

        return treeNode
    }

    // ========================================================================
    // Expansion state capture / restore
    // ========================================================================

    /**
     * A "key" is the path of child-indices from the root to a node,
     * e.g. [0, 2, 1]. This survives tree rebuilds because the tree
     * structure is stable between polls (same app, same layout).
     */
    private fun captureExpandedKeys(): Set<List<Int>> {
        val keys = mutableSetOf<List<Int>>()
        val rows = layoutTree.rowCount
        for (i in 0 until rows) {
            if (layoutTree.isExpanded(i)) {
                val path = layoutTree.getPathForRow(i) ?: continue
                keys.add(pathToKey(path))
            }
        }
        return keys
    }

    private fun captureSelectedKey(): List<Int>? {
        val sel = layoutTree.selectionPath ?: return null
        return pathToKey(sel)
    }

    private fun pathToKey(path: TreePath): List<Int> {
        val key = mutableListOf<Int>()
        for (i in 1 until path.pathCount) {
            val node = path.getPathComponent(i) as DefaultMutableTreeNode
            val parent = node.parent as? DefaultMutableTreeNode ?: break
            key.add(parent.getIndex(node))
        }
        return key
    }

    private fun restoreExpandedKeys(root: DefaultMutableTreeNode, keys: Set<List<Int>>) {
        for (key in keys) {
            val node = resolveKey(root, key) ?: continue
            val path = TreePath((treeModel.getPathToRoot(node)))
            layoutTree.expandPath(path)
        }
    }

    private fun restoreSelectedKey(root: DefaultMutableTreeNode, key: List<Int>) {
        val node = resolveKey(root, key) ?: return
        val path = TreePath((treeModel.getPathToRoot(node)))
        layoutTree.selectionPath = path
        layoutTree.scrollPathToVisible(path)
    }

    private fun resolveKey(root: DefaultMutableTreeNode, key: List<Int>): DefaultMutableTreeNode? {
        var current = root
        for (idx in key) {
            if (idx < 0 || idx >= current.childCount) return null
            current = current.getChildAt(idx) as? DefaultMutableTreeNode ?: return null
        }
        return current
    }

    // ========================================================================
    // Data classes
    // ========================================================================

    data class LayoutNodeData(
        val label: String,
        val type: String,
        val text: String?,
        val bounds: Rectangle?,
        val recompositionCount: Int?
    ) {
        override fun toString(): String = label
    }

    // ========================================================================
    // Screenshot panel with highlight overlay
    // ========================================================================

    private class ScreenshotPanel : JPanel() {
        private var image: BufferedImage? = null
        var highlightBounds: Rectangle? = null

        init {
            preferredSize = Dimension(400, 700)
        }

        fun setScreenshot(img: BufferedImage?) {
            image = img
            highlightBounds = null
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)

            val img = image ?: run {
                g2.color = background
                g2.fillRect(0, 0, width, height)
                g2.color = Color.GRAY
                g2.drawString("No screenshot", width / 2 - 40, height / 2)
                return
            }

            // Scale image to fit panel while maintaining aspect ratio
            val scale = minOf(width.toDouble() / img.width, height.toDouble() / img.height)
            val scaledW = (img.width * scale).toInt()
            val scaledH = (img.height * scale).toInt()
            val offsetX = (width - scaledW) / 2
            val offsetY = (height - scaledH) / 2

            g2.drawImage(img, offsetX, offsetY, scaledW, scaledH, null)

            // Draw highlight overlay
            val hl = highlightBounds
            if (hl != null) {
                val hx = (hl.x * scale).toInt() + offsetX
                val hy = (hl.y * scale).toInt() + offsetY
                val hw = (hl.width * scale).toInt()
                val hh = (hl.height * scale).toInt()

                g2.color = Color(66, 133, 244, 50)
                g2.fillRect(hx, hy, hw, hh)
                g2.color = Color(66, 133, 244, 200)
                g2.drawRect(hx, hy, hw, hh)
            }
        }
    }
}
