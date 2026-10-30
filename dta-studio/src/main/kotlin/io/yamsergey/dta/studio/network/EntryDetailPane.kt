package io.yamsergey.dta.studio.network

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.client.DaemonClient
import io.yamsergey.dta.present.Statement
import io.yamsergey.dta.present.StatementKind
import io.yamsergey.dta.present.network.EntryDetail
import io.yamsergey.dta.present.network.FrameLine
import io.yamsergey.dta.protocol.network.FrameClassifier
import io.yamsergey.dta.present.network.Section
import io.yamsergey.dta.present.network.Side
import io.yamsergey.dta.studio.source.FrameNavigator
import io.yamsergey.dta.studio.ui.DtaColors
import io.yamsergey.dta.studio.ui.Page
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
import javax.swing.SwingConstants

/**
 * The detail of one entry (information.md "Network entry"). A fixed header — the request line,
 * statements, the script and its wire / app-side switch — over tabs: Headers, Payload, Response,
 * Messages, Initiator (last). Each tab scrolls on its own; nothing scrolls inside another scroll.
 */
class EntryDetailPane(private val project: Project, private val scope: CoroutineScope) : JPanel(BorderLayout()) {
    private val header = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); border = JBUI.Borders.empty(8, 12, 4, 12) }
    private val tabs = JBTabbedPane()
    private val placeholder = JBLabel("", SwingConstants.CENTER).apply { foreground = JBColor.GRAY; border = JBUI.Borders.empty(12) }
    private var loading: Job? = null
    private var shown: String? = null
    private var shownGone = false
    private var raw: JsonObject? = null
    private var client: DaemonClient? = null
    private var target: JsonObject = JsonObject(emptyMap())
    private var side = Side.WIRE
    private val navigator = FrameNavigator(project)

    init {
        empty(SELECT)
    }

    /** Shows [id]; [gone] adds the caveat that it is no longer in the list (patterns.md). */
    fun show(client: DaemonClient?, target: JsonObject, id: String?, redact: Boolean, gone: Boolean) {
        if (id == null) { shown = null; return empty(SELECT) }
        if (shown == id && this.client === client) { if (gone != shownGone) render(gone); return }
        this.client = client
        this.target = target
        shown = id
        side = Side.WIRE
        loading?.cancel()
        val c = client ?: return empty("No DTA daemon connected.")
        empty("Loading…")
        loading = scope.launch {
            val params = JsonObject(target + mapOf("id" to JsonPrimitive(id)) + if (redact) mapOf("redact" to JsonPrimitive(true)) else emptyMap())
            val result = runCatching { c.call("network.entry", params).result.jsonObject }
            withContext(Dispatchers.Main) {
                raw = result.getOrNull()
                if (raw == null) unavailable(result.exceptionOrNull()) else render(gone)
            }
        }
    }

    private fun render(gone: Boolean) {
        shownGone = gone
        val detail = EntryDetail.of(raw ?: return, side)
        val id = shown ?: return
        renderHeader(detail, gone)

        val selected = tabs.selectedIndex.takeIf { it >= 0 }?.let { tabs.getTitleAt(it) }
        tabs.removeAll()
        tabs.addTab("Headers", scroll(headersPage(detail)))
        val c = client
        if (c != null) {
            detail.request?.body?.let { tabs.addTab("Payload", BodyPane(project, c, target, id, it, scope)) }
            detail.response?.body?.let { tabs.addTab("Response", BodyPane(project, c, target, id, it, scope)) }
        }
        if (detail.messages.isNotEmpty() || detail.messagesMore != null) tabs.addTab("Messages", scroll(messagesPage(detail)))
        detail.initiator?.let { tabs.addTab("Initiator", scroll(initiatorPage(detail))) }
        // The same tab stays open from entry to entry when the new one has it.
        (0 until tabs.tabCount).firstOrNull { tabs.getTitleAt(it) == selected }?.let { tabs.selectedIndex = it }

        removeAll()
        add(header, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun renderHeader(detail: EntryDetail, gone: Boolean) {
        header.removeAll()
        detail.summary.firstOrNull()?.let { first ->
            header.add(JBLabel(first.value).apply { font = font.deriveFont(Font.BOLD); setCopyable(true) }.left())
        }
        val statements = listOfNotNull(
            if (gone) Statement(StatementKind.EVICTED, word = "no longer in the list", detail = "no longer in the list") else null,
        ) + detail.statements
        if (statements.isNotEmpty()) header.add(row(statements.map { StatementMark(it) }))
        detail.intervention?.let { i ->
            val items = mutableListOf<JComponent>(JBLabel("✎ ${listOfNotNull(i.description, i.summary).joinToString(" — ")}").apply { foreground = DtaColors.of("intervened") })
            if (i.hasAppSide) {
                items += ComboBox(arrayOf("On the wire", "What the app saw")).apply {
                    selectedIndex = if (side == Side.WIRE) 0 else 1
                    addActionListener { side = if (selectedIndex == 0) Side.WIRE else Side.APP; render(shownGone) }
                }
            }
            header.add(row(items))
            i.error?.let { header.add(JBLabel("Script error: $it").apply { foreground = DtaColors.of("status-server") }.left()) }
        }
    }

    private fun headersPage(detail: EntryDetail) = Page().apply {
        title("General")
        detail.summary.drop(1).forEach { field(it.label, it.value) }
        detail.request?.let { headers(it) }
        detail.response?.let { headers(it) }
        end()
    }

    private fun Page.headers(section: Section) {
        if (section.headers.isEmpty()) return
        title(if (section.title == "Handshake") "Handshake headers" else "${section.title} headers")
        section.headers.forEach { (name, value) -> field(name, value, mono = true) }
    }

    private fun initiatorPage(detail: EntryDetail) = Page().apply {
        val init = detail.initiator!!
        line(JBLabel(init.header).apply { foreground = JBColor.GRAY })
        init.missing?.let { line(JBLabel("No stack: $it").apply { foreground = DtaColors.of("caveat") }, top = 4) }
        init.frames.forEach { f -> line(frame(f), top = 2) }
        init.truncated?.let { line(JBLabel("$it more frames not captured").apply { foreground = JBColor.GRAY }, top = 4) }
        end()
    }

    /**
     * A frame that opens its source on click or Enter; compiler-generated glue has none and
     * stays plain text. A frame in neither the project nor its libraries says so in place.
     */
    private fun frame(f: FrameLine): JComponent {
        fun JComponent.styled() = apply {
            font = com.intellij.util.ui.UIUtil.getFontWithFallback(editorFont(), if (f.first) Font.BOLD else Font.PLAIN, font.size)
            foreground = if (f.app) JBColor.foreground() else JBColor.GRAY
        }
        if (FrameClassifier.generated(f.className, f.method)) return JBLabel(f.text).apply { setCopyable(true) }.styled()
        val holder = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply { isOpaque = false }
        val link = ActionLink(f.text).apply { styled(); toolTipText = "Open in the editor" }
        link.addActionListener {
            navigator.open(f.className, f.method, f.file, f.line, onMissing = {
                holder.removeAll()
                holder.add(JBLabel("${f.text} — not in this project or its libraries").apply { setCopyable(true) }.styled())
                holder.revalidate()
            })
        }
        holder.add(link)
        return holder
    }

    private fun messagesPage(detail: EntryDetail) = Page().apply {
        detail.messages.forEach { m ->
            field("${m.time}  ${m.kind.label}", listOfNotNull(m.preview, m.timing.ifEmpty { null }).joinToString("   "), mono = true)
        }
        detail.messagesMore?.let { line(JBLabel(it).apply { foreground = JBColor.GRAY }, top = 6) }
        end()
    }

    /** Why the entry could not be loaded; an entry that is no longer stored says so plainly (patterns.md). */
    private fun unavailable(e: Throwable?) {
        val gone = (e as? io.yamsergey.dta.client.DaemonCallException)?.code == io.yamsergey.dta.protocol.ops.ClientErrorCodes.NOT_FOUND
        empty(
            if (gone) "▲ This entry is no longer stored in the app: its history was cleared, or newer traffic evicted it."
            else "This entry could not be loaded: ${e?.message ?: "no reason given"}",
            detail = e?.message,
        )
    }

    private fun empty(text: String, detail: String? = null) {
        // Wraps instead of cutting the sentence off; the daemon's own words stay in the tooltip.
        placeholder.text = "<html><div style='text-align:center'>${text.replace("&", "&amp;").replace("<", "&lt;")}</div></html>"
        placeholder.toolTipText = detail
        removeAll()
        add(placeholder, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun scroll(page: Page) = JBScrollPane(page).apply { border = JBUI.Borders.empty() }

    /** Items side by side from the left edge, as tall as the tallest item. */
    private fun row(components: List<JComponent>) = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = JBUI.Borders.emptyTop(4)
        components.forEachIndexed { i, c ->
            if (i > 0) add(javax.swing.Box.createHorizontalStrut(JBUI.scale(12)))
            c.alignmentY = CENTER_ALIGNMENT
            add(c)
        }
        add(javax.swing.Box.createHorizontalGlue())
        maximumSize = java.awt.Dimension(Int.MAX_VALUE, preferredSize.height)
    }.left()

    private fun <T : JComponent> T.left(): T = apply { alignmentX = LEFT_ALIGNMENT }

    private fun editorFont() = com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().globalScheme.editorFontName

    private companion object {
        const val SELECT = "Select an entry to see its headers, bodies and initiator."
    }
}
