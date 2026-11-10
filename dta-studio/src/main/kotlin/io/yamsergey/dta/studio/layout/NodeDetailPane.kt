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
        add(JBScrollPane(content), BorderLayout.CENTER)
        empty("Select a node to see its properties.")
    }

    fun empty(text: String) = rebuild { add(JBLabel(text).apply { foreground = JBColor.GRAY }) }

    fun show(detail: NodeDetail?, note: String?) {
        if (detail == null) return empty(note ?: "Select a node to see its properties.")
        rebuild {
            add(row(*detail.breadcrumb.mapIndexed { i, name ->
                if (i == detail.breadcrumb.lastIndex) JBLabel(name).apply { font = font.deriveFont(Font.BOLD) }
                else ActionLink(name) { onAncestor(i + 1) }
            }.flatMap { listOf(it, JBLabel("›").apply { foreground = JBColor.GRAY }) }.dropLast(1).toTypedArray()))
            if (detail.statements.isNotEmpty()) add(row(*detail.statements.map { StatementMark(it) }.toTypedArray()))
            val form = FormBuilder.createFormBuilder()
            detail.fields.forEach { (label, value) -> form.addLabeledComponent("$label:", JBLabel(value).apply { setCopyable(true) }) }
            detail.source?.let { form.addLabeledComponent("Source:", sourceLink(it, callers = detail.callers)) }
            add(form.panel.left())
            if (detail.parameters.isNotEmpty()) {
                add(heading("Parameters"))
                val p = FormBuilder.createFormBuilder()
                detail.parameters.forEach { p.addLabeledComponent("${it.name}:", JBLabel("${it.value}  ${it.type}" + (it.note?.let { n -> "  ($n)" } ?: "")).apply { setCopyable(true); toolTipText = it.type }) }
                add(p.panel.left())
            }
            if (detail.callChain.isNotEmpty()) {
                add(heading("Call chain"))
                // Feature 054: a call with a source opens like the node's own source link.
                detail.callChain.forEach { c ->
                    add((c.source?.let { sourceLink(it, c.text, emptyList()) } ?: JBLabel(c.text).apply { setCopyable(true) }).left())
                }
            }
        }
    }

    /**
     * A link opening [source]; when it is not found it says so and offers the nearest app call
     * above the node ([callers], feature 054) instead of opening an unrelated file.
     */
    private fun sourceLink(source: Source, text: String = "${source.file}:${source.line}", callers: List<NodeDetail.Call>): JComponent {
        val holder = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        val link = ActionLink(text)
        link.addActionListener {
            openSource(source, link) {
                holder.removeAll()
                holder.add(JBLabel("$text — not in this project").apply { foreground = JBColor.GRAY })
                callers.firstOrNull { it.source != null }?.let { c ->
                    holder.add(JBLabel(" · ").apply { foreground = JBColor.GRAY })
                    holder.add(sourceLink(c.source!!, "open ${c.text}", emptyList()))
                }
                holder.revalidate()
            }
        }
        holder.add(link)
        return holder
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
