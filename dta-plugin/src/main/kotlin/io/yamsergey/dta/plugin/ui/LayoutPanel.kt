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

    private val selectToggle = javax.swing.JButton("Select").apply {
        toolTipText = "Highlight this element on device"
        addActionListener { toggleDeviceSelection() }
    }
    private val focusButton = javax.swing.JButton("Focus (0)").apply {
        isEnabled = false
        addActionListener { showFocusDropdown() }
    }
    private var deviceSelectedBounds: Rectangle? = null
    private var deviceSelectedElements: List<JsonNode> = emptyList()
    private var isCurrentNodeSelectedOnDevice = false

    private val treeToolbar = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 2)).apply {
        add(selectToggle)
        add(focusButton)
    }
    private val treePanel = JPanel(BorderLayout()).apply {
        add(treeToolbar, BorderLayout.NORTH)
        add(JBScrollPane(layoutTree), BorderLayout.CENTER)
    }

    private val splitter = JBSplitter(false, 0.5f).apply {
        firstComponent = JBScrollPane(screenshotPanel)
        secondComponent = treePanel
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
            updateSelectToggle()
        }

        // Screenshot hover: highlight the smallest enclosing element
        screenshotPanel.addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseMoved(e: java.awt.event.MouseEvent) {
                val devicePt = screenshotPanel.screenToDevice(e.point) ?: return
                val node = findSmallestNode(rootNode, devicePt)
                screenshotPanel.hoverBounds = (node?.userObject as? LayoutNodeData)?.bounds
                screenshotPanel.repaint()
            }
        })

        // Screenshot click: select the element in the tree
        screenshotPanel.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                val devicePt = screenshotPanel.screenToDevice(e.point) ?: return
                val node = findSmallestNode(rootNode, devicePt) ?: return
                val path = TreePath(treeModel.getPathToRoot(node))
                layoutTree.selectionPath = path
                layoutTree.scrollPathToVisible(path)
            }

            override fun mouseExited(e: java.awt.event.MouseEvent) {
                screenshotPanel.hoverBounds = null
                screenshotPanel.repaint()
            }
        })

        DtaService.getInstance().addListener(this)
    }

    /**
     * Finds the smallest (deepest, tightest-bounds) tree node whose bounds
     * contain the given device-coordinate point. Walks the tree depth-first
     * and picks the smallest area match.
     */
    private fun findSmallestNode(root: DefaultMutableTreeNode, pt: Point): DefaultMutableTreeNode? {
        var best: DefaultMutableTreeNode? = null
        var bestArea = Int.MAX_VALUE

        fun walk(node: DefaultMutableTreeNode) {
            val data = node.userObject as? LayoutNodeData
            val b = data?.bounds
            if (b != null && b.contains(pt)) {
                val area = b.width * b.height
                if (area < bestArea) {
                    bestArea = area
                    best = node
                }
            }
            for (i in 0 until node.childCount) {
                walk(node.getChildAt(i) as DefaultMutableTreeNode)
            }
        }
        walk(root)
        return best
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

    override fun onDeviceSelectionsChanged(elements: String?, networkRequests: String?, wsMessages: String?) {
        try {
            if (elements != null) {
                val root = mapper.readTree(elements)
                // Response format: {"elements": [...], "count": N}
                val arr = root.get("elements") ?: root
                if (arr.isArray && arr.size() > 0) {
                    deviceSelectedElements = (0 until arr.size()).map { arr.get(it) }
                    focusButton.text = "Focus (${deviceSelectedElements.size}) ▾"
                    focusButton.isEnabled = true
                    updateSelectToggle()
                    return
                }
            }
        } catch (_: Exception) {}
        deviceSelectedElements = emptyList()
        deviceSelectedBounds = null
        focusButton.text = "Focus (0)"
        focusButton.isEnabled = false
        updateSelectToggle()
    }

    private fun updateSelectToggle() {
        val node = layoutTree.lastSelectedPathComponent as? DefaultMutableTreeNode
        val data = node?.userObject as? LayoutNodeData
        val b = data?.bounds
        isCurrentNodeSelectedOnDevice = if (b != null) {
            deviceSelectedElements.any { el ->
                val eb = el.get("bounds")
                eb != null && boundsMatch(eb, b)
            }
        } else false
        selectToggle.text = if (isCurrentNodeSelectedOnDevice) "Deselect" else "Select"
    }

    /** Compare daemon bounds (may be floats like 456.0) with Rectangle ints. */
    private fun boundsMatch(eb: JsonNode, b: Rectangle): Boolean {
        val l = eb.path("left").asDouble().toInt()
        val t = eb.path("top").asDouble().toInt()
        val r = eb.path("right").asDouble().toInt()
        val bt = eb.path("bottom").asDouble().toInt()
        return l == b.x && t == b.y && r == b.x + b.width && bt == b.y + b.height
    }

    private fun toggleDeviceSelection() {
        val service = DtaService.getInstance()
        val pkg = service.selectedApp?.packageName() ?: return
        val device = service.selectedDevice?.serial()

        val node = layoutTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val data = node.userObject as? LayoutNodeData ?: return
        val b = data.bounds ?: return
        val elementJson = mapper.writeValueAsString(mapper.createObjectNode().apply {
            put("className", data.type)
            if (data.text != null) put("text", data.text)
            putObject("bounds").apply {
                put("left", b.x); put("top", b.y)
                put("right", b.x + b.width); put("bottom", b.y + b.height)
            }
        })

        if (isCurrentNodeSelectedOnDevice) {
            // Remove from local list first, then daemon
            deviceSelectedElements = deviceSelectedElements.filter { el ->
                val eb = el.get("bounds")
                !(eb != null && eb.path("left").asInt() == b.x && eb.path("top").asInt() == b.y &&
                    eb.path("right").asInt() == b.x + b.width && eb.path("bottom").asInt() == b.y + b.height)
            }
            isCurrentNodeSelectedOnDevice = false
            selectToggle.text = "Select"
            focusButton.text = "Focus (${deviceSelectedElements.size})" + if (deviceSelectedElements.isNotEmpty()) " ▾" else ""
            focusButton.isEnabled = deviceSelectedElements.isNotEmpty()
            ApplicationManager.getApplication().executeOnPooledThread {
                service.removeElementSelection(pkg, device, elementJson)
            }
        } else {
            // Add to local list first, then daemon
            val parsed = mapper.readTree(elementJson)
            deviceSelectedElements = deviceSelectedElements + parsed
            isCurrentNodeSelectedOnDevice = true
            selectToggle.text = "Deselect"
            focusButton.text = "Focus (${deviceSelectedElements.size}) ▾"
            focusButton.isEnabled = true
            ApplicationManager.getApplication().executeOnPooledThread {
                service.addElementSelection(pkg, device, elementJson)
            }
        }
    }

    private fun showFocusDropdown() {
        if (deviceSelectedElements.isEmpty()) return
        val popup = javax.swing.JPopupMenu()
        for ((idx, el) in deviceSelectedElements.withIndex()) {
            val label = el.path("className").asText(el.path("composable").asText("Element $idx"))
            popup.add(javax.swing.JMenuItem(label).apply {
                addActionListener {
                    val b = el.get("bounds") ?: return@addActionListener
                    val cx = (b.path("left").asInt() + b.path("right").asInt()) / 2
                    val cy = (b.path("top").asInt() + b.path("bottom").asInt()) / 2
                    val node = findSmallestNode(rootNode, Point(cx, cy)) ?: return@addActionListener
                    val path = TreePath(treeModel.getPathToRoot(node))
                    layoutTree.selectionPath = path
                    layoutTree.scrollPathToVisible(path)
                }
            })
        }
        popup.show(focusButton, 0, focusButton.height)
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
        /** Blue: selected element (from tree selection). */
        var highlightBounds: Rectangle? = null
        /** Orange: hovered element (from mouse over screenshot). */
        var hoverBounds: Rectangle? = null

        // Cached scaling — recalculated on each paint, used by screenToDevice
        private var currentScale = 1.0
        private var currentOffsetX = 0
        private var currentOffsetY = 0

        init {
            preferredSize = Dimension(400, 700)
        }

        fun setScreenshot(img: BufferedImage?) {
            image = img
            highlightBounds = null
            hoverBounds = null
            repaint()
        }

        /**
         * Converts a panel-coordinate point to device (screenshot) coordinates.
         * Returns null if the point is outside the rendered image area.
         */
        fun screenToDevice(panelPoint: Point): Point? {
            val img = image ?: return null
            val scale = currentScale
            if (scale <= 0) return null
            val dx = ((panelPoint.x - currentOffsetX) / scale).toInt()
            val dy = ((panelPoint.y - currentOffsetY) / scale).toInt()
            if (dx < 0 || dy < 0 || dx >= img.width || dy >= img.height) return null
            return Point(dx, dy)
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

            // Cache for screenToDevice
            currentScale = scale
            currentOffsetX = offsetX
            currentOffsetY = offsetY

            g2.drawImage(img, offsetX, offsetY, scaledW, scaledH, null)

            // Draw hover overlay (orange, behind selection)
            drawBoundsOverlay(g2, hoverBounds, scale, offsetX, offsetY,
                Color(255, 152, 0, 40), Color(255, 152, 0, 180))

            // Draw selection overlay (blue, on top)
            drawBoundsOverlay(g2, highlightBounds, scale, offsetX, offsetY,
                Color(66, 133, 244, 50), Color(66, 133, 244, 200))
        }

        private fun drawBoundsOverlay(g2: Graphics2D, bounds: Rectangle?,
                                      scale: Double, offsetX: Int, offsetY: Int,
                                      fill: Color, stroke: Color) {
            val b = bounds ?: return
            val hx = (b.x * scale).toInt() + offsetX
            val hy = (b.y * scale).toInt() + offsetY
            val hw = (b.width * scale).toInt()
            val hh = (b.height * scale).toInt()
            g2.color = fill
            g2.fillRect(hx, hy, hw, hh)
            g2.color = stroke
            g2.drawRect(hx, hy, hw, hh)
        }
    }
}
