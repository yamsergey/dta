package io.yamsergey.dta.studio.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.present.layout.CallGroup
import io.yamsergey.dta.present.layout.NodeDetail
import io.yamsergey.dta.present.layout.Source
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * A call chain as Layout shows it (feature 059), shared with the Timeline's taps (064): runs of
 * one file fold into a row that opens on click; each call, and a node's Source, is a link that
 * opens its line or says that the file is not in this project (054).
 *
 * [refresh] redraws the owner after a group is opened or folded.
 */
class CallChainView(
    private val openSource: (Source, JComponent, onMissing: () -> Unit) -> Unit,
    private val refresh: () -> Unit,
) {
    /** Keys of the opened groups, kept while the same chain ([identity]) is shown. */
    private val opened = mutableSetOf<String>()
    private var shownFor: Any? = null

    /** Adds [groups] to [panel], one row per group (and per call of an opened group). */
    fun addTo(panel: JPanel, groups: List<CallGroup>, identity: Any) {
        if (identity != shownFor) { shownFor = identity; opened.clear() }
        groups.forEach { panel.group(it) }
    }

    private fun JPanel.group(g: CallGroup) {
        val number = { text: String -> JBLabel(text).apply { foreground = JBColor.GRAY; preferredSize = JBUI.size(36, preferredSize.height) } }
        val file = g.file ?: run {
            val c = g.calls.single()
            add(row(number(g.range), JBLabel(c.text).apply { setCopyable(true) }))
            return
        }
        val open = g.key in opened
        val toggle = ActionLink(file) {
            if (!opened.remove(g.key)) opened += g.key
            refresh()
        }.apply {
            icon = if (open) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
            toolTipText = "${g.packageName?.let { "$it · " } ?: ""}${g.count}: ${g.names}"
            accessibleContext.accessibleName = "$file, ${g.count}, ${if (open) "open" else "folded"}"
        }
        add(row(number(g.range), toggle, JBLabel(if (open) g.count else "${g.count} · ${g.names}").apply { foreground = JBColor.GRAY }))
        if (open) g.calls.forEachIndexed { i, c ->
            val text = c.source?.let { "${c.text.substringBefore(" (")}  :${it.line}" } ?: c.text
            add(row(number("${g.first + i}"), JBLabel("").apply { preferredSize = JBUI.size(12, 1) },
                c.source?.let { sourceLink(it, text, emptyList()) } ?: JBLabel(text)))
        }
    }

    /**
     * A link opening [source]; when it is not found it says so and offers the nearest app call
     * above ([callers], feature 054) instead of opening an unrelated file.
     */
    fun sourceLink(source: Source, text: String = "${source.file}:${source.line}", callers: List<NodeDetail.Call>): JComponent {
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

    private fun row(vararg items: JComponent) =
        JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2))).apply { items.forEach { add(it) }; alignmentX = JComponent.LEFT_ALIGNMENT }
}
