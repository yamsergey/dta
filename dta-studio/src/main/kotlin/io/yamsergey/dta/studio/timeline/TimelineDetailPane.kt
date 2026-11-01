package io.yamsergey.dta.studio.timeline

import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.present.timeline.ChainView
import io.yamsergey.dta.present.timeline.EventDetail
import io.yamsergey.dta.present.timeline.FacetsAt
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.studio.ui.DtaColors
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Feature 017 (US2, US4): the selected event — what it was, CAUSED BY and LED TO (each row
 * selects its event), the state at that moment, and the jumps (Network entry, Layout, copy id).
 */
class TimelineDetailPane(
    private val onSelect: (String) -> Unit,
    private val onOpenNetwork: (String) -> Unit,
    private val onOpenLayout: () -> Unit,
    private val onCopy: (String) -> Unit,
) : JPanel(BorderLayout()) {
    private val content = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); border = JBUI.Borders.empty(8, 12) }

    init {
        add(JBScrollPane(content), BorderLayout.CENTER)
        empty("Select an event to see what caused it and what it led to.")
    }

    fun empty(text: String) = rebuild { add(JBLabel(text).apply { foreground = JBColor.GRAY }.left()) }

    private var shownFor: Any? = null

    fun show(s: TimelineTabState) {
        // Live events arrive all the time; the pane changes only with what it shows.
        val key = listOf(s.view.selectedId, s.view.selectedGone, s.chain, s.chainError, s.view.current, s.view.selected)
        if (key == shownFor) return
        shownFor = key
        val selected = s.view.selected
        if (selected == null) {
            return empty(if (s.view.selectedId != null && s.view.selectedGone) "${s.view.selectedId} is no longer kept." else "Select an event to see what caused it and what it led to.")
        }
        val detail = EventDetail.of(selected)
        rebuild {
            add(JBLabel(detail.title).apply { font = font.deriveFont(Font.BOLD) }.left())
            detail.notes.forEach { add(JBLabel(it).apply { foreground = DtaColors.of("caveat") }.left()) }
            val form = FormBuilder.createFormBuilder()
            detail.rows.forEach { form.addLabeledComponent("${it.label}:", JBLabel(it.value).apply { setCopyable(true) }) }
            add(form.panel.left())
            add(row(*listOfNotNull(
                detail.networkEntry?.let { id -> ActionLink("Open in Network") { onOpenNetwork(id) } },
                if (selected.source == Sources.UI) ActionLink("Open in Layout (now)") { onOpenLayout() } else null,
                ActionLink("Copy id") { onCopy(selected.id) },
            ).toTypedArray()))

            add(heading("CAUSED BY"))
            s.chain.noKnownCause?.let { add(JBLabel(it).apply { foreground = DtaColors.of("caveat") }.left()) }
            chainRows(s.chain.causedBy)
            if (s.chain.causedBy.isEmpty() && s.chain.noKnownCause == null && s.chainError == null) {
                add(JBLabel(if (selected.source == Sources.INPUT) "the user (a root)" else "—").apply { foreground = JBColor.GRAY }.left())
            }
            s.chainError?.let { add(JBLabel(it).apply { foreground = DtaColors.of("caveat") }.left()) }

            add(heading("LED TO"))
            chainRows(s.chain.ledTo)
            if (s.chain.ledTo.isEmpty()) add(JBLabel("nothing known").apply { foreground = JBColor.GRAY }.left())
            s.chain.cuts.forEach { add(JBLabel(it).apply { foreground = JBColor.GRAY }.left()) }

            add(heading("STATE"))
            val at = FacetsAt.of(s.view.events, s.view.current, selected.id)
            if (at.values.isEmpty()) add(JBLabel("no facets reported").apply { foreground = JBColor.GRAY }.left())
            at.values.forEach { add(JBLabel("${it.name} = ${it.value}").apply { setCopyable(true) }.left()) }
            if (at.partial) add(JBLabel("partial: the start of this run is no longer kept").apply { foreground = DtaColors.of("caveat") }.left())
        }
    }

    private fun JPanel.chainRows(rows: List<ChainView.Row>) {
        rows.forEach { r ->
            val link = ActionLink(r.text) { onSelect(r.eventId) }.apply {
                toolTipText = r.via
                border = JBUI.Borders.emptyLeft(JBUI.scale(12) * (r.depth - 1))
            }
            add(row(link, JBLabel(r.via).apply { foreground = JBColor.GRAY }))
        }
    }

    private fun heading(text: String) = JBLabel(text).apply {
        font = font.deriveFont(Font.BOLD)
        foreground = JBColor.GRAY
        border = JBUI.Borders.emptyTop(10)
    }.left()

    private fun row(vararg items: JComponent) = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2))).apply { items.forEach { add(it) } }.left()

    private fun <T : JComponent> T.left(): T = apply { alignmentX = LEFT_ALIGNMENT }

    private fun rebuild(fill: JPanel.() -> Unit) {
        content.removeAll()
        content.fill()
        content.revalidate()
        content.repaint()
    }
}
