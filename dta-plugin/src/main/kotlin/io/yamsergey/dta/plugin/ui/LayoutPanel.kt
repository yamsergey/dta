package io.yamsergey.dta.plugin.ui

import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.plugin.DtaService
import io.yamsergey.dta.plugin.DtaService.DtaServiceListener
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Image
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.JPanel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Layout inspection panel with a split view:
 * - Left: device screenshot with highlight overlay
 * - Right: layout tree hierarchy
 */
class LayoutPanel : JPanel(BorderLayout()), DtaServiceListener {

    private val screenshotPanel = ScreenshotPanel()
    private val rootNode = DefaultMutableTreeNode("No data")
    private val treeModel = DefaultTreeModel(rootNode)
    private val layoutTree = Tree(treeModel)

    private val splitter = JBSplitter(false, 0.5f).apply {
        firstComponent = JBScrollPane(screenshotPanel)
        secondComponent = JBScrollPane(layoutTree)
    }

    init {
        add(splitter, BorderLayout.CENTER)

        layoutTree.isRootVisible = true
        layoutTree.showsRootHandles = true
        layoutTree.addTreeSelectionListener { e ->
            val node = e.path?.lastPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            val data = node.userObject as? LayoutNodeData ?: return@addTreeSelectionListener
            screenshotPanel.highlightBounds = data.bounds
            screenshotPanel.repaint()
        }

        DtaService.getInstance().addListener(this)
    }

    override fun onLayoutDataChanged(treeJson: String?, screenshotBytes: ByteArray?) {
        if (screenshotBytes != null) {
            try {
                val image = ImageIO.read(ByteArrayInputStream(screenshotBytes))
                screenshotPanel.setScreenshot(image)
            } catch (e: Exception) {
                screenshotPanel.setScreenshot(null)
            }
        }

        if (treeJson != null) {
            rebuildTree(treeJson)
        } else {
            rootNode.removeAllChildren()
            rootNode.userObject = "No data"
            treeModel.reload()
        }
    }

    /**
     * Parses the layout tree JSON and rebuilds the tree model.
     * Uses simple string parsing to avoid depending on a JSON library.
     */
    private fun rebuildTree(json: String) {
        rootNode.removeAllChildren()
        try {
            val nodes = parseLayoutNodes(json)
            if (nodes.isNotEmpty()) {
                rootNode.userObject = "Layout"
                for (node in nodes) {
                    rootNode.add(node)
                }
            } else {
                rootNode.userObject = "Empty layout"
            }
        } catch (e: Exception) {
            rootNode.userObject = "Parse error: ${e.message}"
        }
        treeModel.reload()
        // Expand the first two levels
        for (i in 0 until layoutTree.rowCount.coerceAtMost(20)) {
            layoutTree.expandRow(i)
        }
    }

    /**
     * Minimal JSON array/object parser for layout nodes.
     * Expects the sidekick /layout/tree response format:
     * { "nodes": [ { "type": "...", "bounds": {...}, "children": [...], ... } ] }
     * or a direct array of nodes.
     */
    private fun parseLayoutNodes(json: String): List<DefaultMutableTreeNode> {
        val trimmed = json.trim()

        // Response format: {"windows": [{"tree": {...}}, ...], "screen": {...}}
        // Extract each window's "tree" object as a root node
        val windowsArr = extractArrayField(trimmed, "windows")
        if (windowsArr != null) {
            val windowObjects = parseObjectsFromArray(windowsArr)
            return windowObjects.mapNotNull { windowObj ->
                val treeObj = extractObjectField(windowObj, "tree")
                if (treeObj != null) parseNode(treeObj) else null
            }
        }

        // Fallback: try "nodes" array or direct array
        val nodesArr = extractArrayField(trimmed, "nodes")
        if (nodesArr != null) return parseNodeArray(nodesArr)

        if (trimmed.startsWith("[")) return parseNodeArray(trimmed)

        // Single tree object
        if (trimmed.startsWith("{")) return listOf(parseNode(trimmed))

        return emptyList()
    }

    private fun parseObjectsFromArray(arrayJson: String): List<String> {
        val inner = arrayJson.trim().removeSurrounding("[", "]").trim()
        if (inner.isEmpty()) return emptyList()
        val objects = mutableListOf<String>()
        var i = 0
        while (i < inner.length) {
            if (inner[i] == '{') {
                val objStr = extractBalanced(inner, i, '{', '}')
                objects.add(objStr)
                i += objStr.length
            } else {
                i++
            }
        }
        return objects
    }

    private fun parseNodeArray(arrayJson: String): List<DefaultMutableTreeNode> {
        val inner = arrayJson.trim().removeSurrounding("[", "]").trim()
        if (inner.isEmpty()) return emptyList()

        val nodes = mutableListOf<DefaultMutableTreeNode>()
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c == '{') {
                val objStr = extractBalanced(inner, i, '{', '}')
                nodes.add(parseNode(objStr))
                i += objStr.length
            } else {
                i++
            }
        }
        return nodes
    }

    private fun parseNode(objJson: String): DefaultMutableTreeNode {
        val type = extractStringField(objJson, "type")
            ?: extractStringField(objJson, "className")
            ?: "Unknown"
        val text = extractStringField(objJson, "text")
        val recompCount = extractIntField(objJson, "recompositionCount")

        // Parse bounds
        val boundsObj = extractObjectField(objJson, "bounds")
        val bounds = if (boundsObj != null) {
            val left = extractIntField(boundsObj, "left") ?: extractIntField(boundsObj, "x") ?: 0
            val top = extractIntField(boundsObj, "top") ?: extractIntField(boundsObj, "y") ?: 0
            val width = extractIntField(boundsObj, "width")
                ?: ((extractIntField(boundsObj, "right") ?: left) - left)
            val height = extractIntField(boundsObj, "height")
                ?: ((extractIntField(boundsObj, "bottom") ?: top) - top)
            Rectangle(left, top, width, height)
        } else null

        val label = buildString {
            append(type.substringAfterLast('.'))
            if (!text.isNullOrEmpty()) append(" \"${text.take(30)}\"")
            if (recompCount != null && recompCount > 0) append(" [$recompCount]")
            if (bounds != null) append(" [${bounds.x},${bounds.y} ${bounds.width}x${bounds.height}]")
        }

        val data = LayoutNodeData(label, type, text, bounds, recompCount)
        val treeNode = DefaultMutableTreeNode(data)

        // Parse children
        val childrenArr = extractArrayField(objJson, "children")
        if (childrenArr != null) {
            val children = parseNodeArray(childrenArr)
            children.forEach { treeNode.add(it) }
        }

        return treeNode
    }

    // ========================================================================
    // Simple JSON field extraction helpers
    // ========================================================================

    private fun extractStringField(json: String, field: String): String? {
        val pattern = "\"$field\"\\s*:\\s*\"([^\"]*)\""
        val match = Regex(pattern).find(json) ?: return null
        return match.groupValues[1]
    }

    private fun extractIntField(json: String, field: String): Int? {
        val pattern = "\"$field\"\\s*:\\s*(-?\\d+)"
        val match = Regex(pattern).find(json) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private fun extractObjectField(json: String, field: String): String? {
        val key = "\"$field\""
        val keyIdx = json.indexOf(key)
        if (keyIdx < 0) return null
        val colonIdx = json.indexOf(':', keyIdx + key.length)
        if (colonIdx < 0) return null
        val objStart = json.indexOf('{', colonIdx)
        if (objStart < 0) return null
        return extractBalanced(json, objStart, '{', '}')
    }

    private fun extractArrayField(json: String, field: String): String? {
        val key = "\"$field\""
        val keyIdx = json.indexOf(key)
        if (keyIdx < 0) return null
        val colonIdx = json.indexOf(':', keyIdx + key.length)
        if (colonIdx < 0) return null
        val arrStart = json.indexOf('[', colonIdx)
        if (arrStart < 0 || arrStart > colonIdx + 10) return null
        return extractBalanced(json, arrStart, '[', ']')
    }

    private fun extractBalanced(json: String, start: Int, open: Char, close: Char): String {
        var depth = 0
        var inString = false
        var escape = false
        var i = start
        while (i < json.length) {
            val c = json[i]
            if (escape) {
                escape = false
            } else if (c == '\\') {
                escape = true
            } else if (c == '"') {
                inString = !inString
            } else if (!inString) {
                if (c == open) depth++
                else if (c == close) {
                    depth--
                    if (depth == 0) return json.substring(start, i + 1)
                }
            }
            i++
        }
        return json.substring(start)
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
