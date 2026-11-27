package io.yamsergey.dta.studio.data

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.ColoredTreeCellRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/** Truncation rules, rule 4 (mockup 4b): a ViewModel row keeps its class name whole; the owner ends in …, the tooltip has class, id and owner. */
class RuntimeViewModelRowTest : BasePlatformTestCase() {
    private val owner = "activity io.yamsergey.shop.checkout.CheckoutActivity (resumed, task 12)"

    private fun answer(op: String): JsonObject = Json.parseToJsonElement(
        when (op) {
            "runtime.navigation" -> """{"hosts":[]}"""
            "runtime.viewmodels" -> """{"viewModels":[{"id":"v7","className":"androidx.lifecycle.SavedStateHandlesVM","owner":"$owner","properties":[]}]}"""
            "runtime.functions" -> """{"functions":[]}"""
            else -> "{}"
        },
    ).jsonObject

    private fun row(width: Int): Pair<RuntimePanel, String> {
        val p = RuntimePanel(project, CoroutineScope(Dispatchers.Unconfined), ui = { it.run() }) { op, _ -> answer(op) }
        p.load()
        val root = p.tree.model.root as DefaultMutableTreeNode
        val node = root.depthFirstEnumeration().toList().map { it as DefaultMutableTreeNode }.first { it.userObject.toString().contains("SavedStateHandlesVM") }
        p.tree.expandPath(TreePath((node.parent as DefaultMutableTreeNode).path))
        p.tree.setSize(width, 300)
        val r = p.tree.cellRenderer.getTreeCellRendererComponent(p.tree, node, false, false, true, p.tree.getRowForPath(TreePath(node.path)), false) as ColoredTreeCellRenderer
        val tip = p.tree.getToolTipText(java.awt.event.MouseEvent(p.tree, 0, 0, 0, 40, p.tree.getRowBounds(p.tree.getRowForPath(TreePath(node.path)))?.y?.plus(2) ?: 2, 0, false))
        return p to (r.getCharSequence(false).toString() + "\u0000" + tip)
    }

    fun testTheClassNameIsWholeAndTheOwnerEndsInAnEllipsisWithATooltipAtANarrowWidth() {
        val (_, shownAndTip) = row(220)
        val (shown, tip) = shownAndTip.split("\u0000")
        assertTrue(shown, shown.startsWith("SavedStateHandlesVM"))
        assertTrue(shown, shown.endsWith("…"))
        assertTrue(tip, "SavedStateHandlesVM" in tip && "v7" in tip && owner in tip)
    }

    fun testAWideTreeShowsTheOwnerWhole() {
        val (_, shownAndTip) = row(1200)
        assertTrue(shownAndTip, owner in shownAndTip.substringBefore("\u0000"))
    }

    /** The renderer must not ask the tree for row geometry: the tree asks the renderer for it (StackOverflowError otherwise). */
    fun testTheTreeLaysOutAndSwitchesLookWithAViewModelRow() {
        val p = RuntimePanel(project, CoroutineScope(Dispatchers.Unconfined), ui = { it.run() }) { op, _ -> answer(op) }
        p.load()
        p.tree.rowHeight = 0 // variable row heights: the tree measures rows with the renderer
        p.tree.setSize(220, 300)
        p.tree.invalidate()
        val image = java.awt.image.BufferedImage(220, 300, java.awt.image.BufferedImage.TYPE_INT_RGB)
        p.tree.paint(image.createGraphics())
        for (i in 0 until p.tree.rowCount) assertNotNull(p.tree.getRowBounds(i))
        assertTrue(p.tree.preferredSize.width > 0)
        p.tree.updateUI()
        for (i in 0 until p.tree.rowCount) assertNotNull(p.tree.getRowBounds(i))
        // A cold layout cache: asking the renderer first must not send it back to the tree's geometry.
        p.tree.updateUI()
        p.tree.rowHeight = 0
        val node = (p.tree.model.root as DefaultMutableTreeNode).depthFirstEnumeration().toList().map { it as DefaultMutableTreeNode }.first { it.userObject.toString().contains("SavedStateHandlesVM") }
        p.tree.cellRenderer.getTreeCellRendererComponent(p.tree, node, false, false, true, p.tree.getRowForPath(TreePath(node.path)), false)
    }
}
