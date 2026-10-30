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
            detail.source?.let { form.addLabeledComponent("Source:", sourceLink(it)) }
            add(form.panel.left())
            if (detail.parameters.isNotEmpty()) {
                add(heading("Parameters"))
                val p = FormBuilder.createFormBuilder()
                detail.parameters.forEach { p.addLabeledComponent("${it.name}:", JBLabel("${it.value}  ${it.type}").apply { setCopyable(true); toolTipText = it.type }) }
                add(p.panel.left())
            }
            if (detail.callChain.isNotEmpty()) {
                add(heading("Call chain"))
                detail.callChain.forEach { add(JBLabel(it).apply { setCopyable(true) }.left()) }
            }
        }
    }

    private fun sourceLink(source: Source): JComponent {
        val holder = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        val link = ActionLink("${source.file}:${source.line}")
        link.addActionListener {
            openSource(source, link) {
                holder.removeAll()
                holder.add(JBLabel("${source.file}:${source.line} — not in this project").apply { foreground = JBColor.GRAY })
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
