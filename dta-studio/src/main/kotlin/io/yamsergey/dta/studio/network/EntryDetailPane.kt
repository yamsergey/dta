package io.yamsergey.dta.studio.network

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.client.DaemonClient
import io.yamsergey.dta.present.StatementKind
import io.yamsergey.dta.present.network.EntryDetail
import io.yamsergey.dta.present.network.Section
import io.yamsergey.dta.present.network.Side
import io.yamsergey.dta.studio.ui.DtaColors
import io.yamsergey.dta.studio.ui.StatementMark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** The detail of one entry (information.md "Network entry"), with the wire / app-side switch for scripted entries. */
class EntryDetailPane(private val project: Project, private val scope: CoroutineScope) : JPanel(BorderLayout()) {
    private val content = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); border = JBUI.Borders.empty(8, 12) }
    private var loading: Job? = null
    private var shown: Pair<String, JsonObject>? = null
    private var raw: JsonObject? = null
    private var client: DaemonClient? = null
    private var target: JsonObject = JsonObject(emptyMap())
    private var side = Side.WIRE

    init {
        add(JBScrollPane(content), BorderLayout.CENTER)
        empty("Select an entry to see its request, response and initiator.")
    }

    /** Shows [id]; [gone] adds the caveat that it is no longer in the list (patterns.md). */
    fun show(client: DaemonClient?, target: JsonObject, id: String?, redact: Boolean, gone: Boolean) {
        if (id == null) { shown = null; return empty("Select an entry to see its request, response and initiator.") }
        if (shown?.first == id && this.client === client) { if (gone) render(gone = true); return }
        this.client = client
        this.target = target
        shown = id to target
        side = Side.WIRE
        loading?.cancel()
        val c = client ?: return empty("No DTA daemon connected.")
        loading = scope.launch {
            val params = JsonObject(target + mapOf("id" to JsonPrimitive(id)) + if (redact) mapOf("redact" to JsonPrimitive(true)) else emptyMap())
            val result = runCatching { c.call("network.entry", params).result.jsonObject }
            withContext(Dispatchers.Main) {
                raw = result.getOrNull()
                if (raw == null) empty("Entry $id is not available: ${result.exceptionOrNull()?.message}") else render(gone)
            }
        }
    }

    private fun render(gone: Boolean = false) {
        val detail = EntryDetail.of(raw ?: return, side)
        val id = shown?.first ?: return
        content.removeAll()
        if (gone) content.add(StatementMark(io.yamsergey.dta.present.Statement(StatementKind.EVICTED, word = "no longer in the list", detail = "no longer in the list")))
        detail.summary.forEach { content.add(field(it.label, it.value, strong = it.label == "Request")) }
        if (detail.statements.isNotEmpty()) content.add(row(detail.statements.map { StatementMark(it) }))
        detail.intervention?.let { i ->
            content.add(title("Script"))
            content.add(JBLabel(i.summary).apply { foreground = DtaColors.of("intervened") })
            i.error?.let { content.add(JBLabel("Script error: $it").apply { foreground = DtaColors.of("status-server") }) }
            if (i.hasAppSide) {
                val switch = ComboBox(arrayOf("On the wire", "What the app saw")).apply {
                    selectedIndex = if (side == Side.WIRE) 0 else 1
                    addActionListener { side = if (selectedIndex == 0) Side.WIRE else Side.APP; render() }
                }
                content.add(row(listOf(switch)))
            }
        }
        detail.request?.let { section(it, id) }
        detail.response?.let { section(it, id) }
        detail.initiator?.let { init ->
            content.add(title("Initiator"))
            content.add(JBLabel(init.header).apply { foreground = JBColor.GRAY })
            init.missing?.let { content.add(JBLabel("No stack: $it").apply { foreground = DtaColors.of("caveat") }) }
            init.frames.forEach { f ->
                content.add(JBLabel(f.text).apply {
                    font = font.deriveFont(if (f.first) Font.BOLD else Font.PLAIN)
                    foreground = if (f.app) JBColor.foreground() else JBColor.GRAY
                })
            }
            init.truncated?.let { content.add(JBLabel("$it more frames not captured").apply { foreground = JBColor.GRAY }) }
        }
        if (detail.messages.isNotEmpty() || detail.messagesMore != null) {
            content.add(title("Messages"))
            detail.messages.forEach { m -> content.add(JBLabel("${m.time}  ${m.kind.glyph} ${m.verb}  ${m.path}  ${m.timing}")) }
            detail.messagesMore?.let { content.add(JBLabel(it).apply { foreground = JBColor.GRAY }) }
        }
        content.revalidate()
        content.repaint()
    }

    private fun section(section: Section, id: String) {
        content.add(title(section.title))
        section.headers.forEach { (name, value) -> content.add(field(name, value)) }
        val c = client ?: return
        section.body?.let { content.add(BodyPane(project, c, target, id, it, scope)) }
    }

    private fun empty(text: String) {
        content.removeAll()
        content.add(JBLabel(text).apply { foreground = JBColor.GRAY })
        content.revalidate()
        content.repaint()
    }

    private fun title(text: String) = JBLabel(text.uppercase()).apply {
        font = font.deriveFont(Font.BOLD)
        border = JBUI.Borders.emptyTop(12)
    }

    private fun field(label: String, value: String, strong: Boolean = false) = JBLabel("<html><span style='color:gray'>$label</span>&nbsp; ${escape(value)}</html>").apply {
        if (strong) font = font.deriveFont(Font.BOLD)
    }

    private fun row(components: List<JComponent>) = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(6), 0)).apply { components.forEach(::add) }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
}
