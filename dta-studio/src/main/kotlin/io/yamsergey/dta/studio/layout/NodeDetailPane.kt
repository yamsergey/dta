package io.yamsergey.dta.studio.layout

import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.present.layout.NodeDetail
import io.yamsergey.dta.present.layout.Source
import io.yamsergey.dta.studio.ui.StatementMark
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The selected node (information.md "Layout node"): breadcrumb, statements, properties, source
 * line, parameters and call chain. Breadcrumb items select that ancestor; source lines open in
 * the editor, or say that the file is not in this project.
 */
class NodeDetailPane(
    private val onAncestor: (depth: Int) -> Unit,
    private val openSource: (Source, JComponent, onMissing: () -> Unit) -> Unit,
) : JPanel(BorderLayout()) {
    private val content = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); border = JBUI.Borders.empty(8, 12) }

    init {
        // At the top of the viewport: short content keeps its rows together instead of BoxLayout
        // spreading them over the pane's height.
        // The view follows the viewport's width: a long value ends in an ellipsis in its row, it does not widen the pane (D-144).
        val view = object : JPanel(BorderLayout()), javax.swing.Scrollable {
            override fun getPreferredScrollableViewportSize() = preferredSize
            override fun getScrollableUnitIncrement(r: java.awt.Rectangle?, o: Int, d: Int) = JBUI.scale(16)
            override fun getScrollableBlockIncrement(r: java.awt.Rectangle?, o: Int, d: Int) = JBUI.scale(64)
            override fun getScrollableTracksViewportWidth() = true
            override fun getScrollableTracksViewportHeight() = false
        }.apply { add(content, BorderLayout.NORTH) }
        add(JBScrollPane(view).apply { horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER }, BorderLayout.CENTER)
        empty("Select a node to see its properties.")
    }

    fun empty(text: String) = rebuild { add(io.yamsergey.dta.studio.ui.WrappingText(text, JBColor.GRAY)) }

    fun show(detail: NodeDetail?, note: String?) {
        lastShown = detail to note
        if (detail == null) return empty(note ?: "Select a node to see its properties.")
        rebuild {
            add(io.yamsergey.dta.studio.ui.BreadcrumbRow(detail.breadcrumb) { depth -> onAncestor(depth) }.left())
            if (detail.statements.isNotEmpty()) add(row(*detail.statements.map { StatementMark(it) }.toTypedArray()))
            val form = FormBuilder.createFormBuilder()
            detail.fields.forEach { (label, value) -> form.addLabeledComponent("$label:", valueLabel(label, value)) }
            detail.source?.let { form.addLabeledComponent("Source:", chainView.sourceLink(it, callers = detail.callers)) }
            add(form.panel.left())
            if (detail.parameters.isNotEmpty()) {
                add(heading("Parameters"))
                val p = FormBuilder.createFormBuilder()
                detail.parameters.forEach { p.addLabeledComponent("${it.name}:", JBLabel("${it.value}  ${it.type}" + (it.note?.let { n -> "  ($n)" } ?: "")).apply { setCopyable(true); toolTipText = it.type }) }
                add(p.panel.left())
            }
            if (detail.callChain.isNotEmpty()) {
                add(heading("Call chain"))
                // Feature 059: runs of one file fold into a row; each call opens like the Source link (054).
                chainView.addTo(this, detail.callGroups, detail.breadcrumb to detail.callChain)
            }
        }
    }

    private var lastShown: Pair<NodeDetail?, String?>? = null
    private val chainView = io.yamsergey.dta.studio.ui.CallChainView(openSource) { lastShown?.let { (d, n) -> show(d, n) } }

    /** A property's value on one line: ends in an ellipsis at the pane's edge, the whole in the tooltip, copied from a right-click. */
    private fun valueLabel(label: String, value: String) = io.yamsergey.dta.studio.ui.FitLabel(asClass = label == "Class").apply {
        show(value)
        componentPopupMenu = javax.swing.JPopupMenu().apply {
            add(javax.swing.JMenuItem("Copy").apply {
                addActionListener { com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(value)) }
            })
        }
    }

    private fun heading(text: String) = JBLabel(text).apply { font = font.deriveFont(Font.BOLD); border = JBUI.Borders.emptyTop(10) }.left()

    private fun row(vararg items: JComponent) = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2))).apply { items.forEach { add(it) } }.left()

    private fun <T : JComponent> T.left(): T = apply { alignmentX = LEFT_ALIGNMENT }

    private fun rebuild(fill: JPanel.() -> Unit) {
        content.removeAll()
        content.fill()
        content.revalidate()
        content.repaint()
    }
}
