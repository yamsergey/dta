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
import io.yamsergey.dta.present.Statements
import io.yamsergey.dta.present.network.label
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
class EntryDetailPane(
    private val project: Project,
    private val scope: CoroutineScope,
    /** Feature 026: the details' Copy menu; none in tests. */
    private val copyMenu: EntryCopyMenu? = null,
    /** Shows another entry, as a click in the list would (025: a message's connection, a connection's message). */
    private val open: (String) -> Unit = {},
    /** Feature 041: a format rule was added, edited or removed — the list's rows are read again. */
    private val formatsChanged: () -> Unit = {},
) : JPanel(BorderLayout()) {
    private val header = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); border = JBUI.Borders.empty(8, 12, 4, 12) }
    private val tabs = JBTabbedPane()
    private val placeholder = JBLabel("", SwingConstants.CENTER).apply { foreground = JBColor.GRAY; border = JBUI.Borders.empty(12) }
    private var loading: Job? = null
    private var shown: String? = null
    private var shownGone = false
    private var shownUnlisted = false
    private var raw: JsonObject? = null
    private var client: DaemonClient? = null
    private var target: JsonObject = JsonObject(emptyMap())
    private var side = Side.WIRE
    private var lastRedact = false
    private var marks: List<String> = emptyList()
    private var hitTab: io.yamsergey.dta.present.network.HitTab? = null
    private var hits: List<io.yamsergey.dta.protocol.network.SearchHit> = emptyList()
    private val navigator = FrameNavigator(project)

    init {
        empty(SELECT)
    }

    /**
     * Shows [id]; [gone] adds the caveat that it is no longer in the list (patterns.md); [unlisted]
     * that the list as filtered does not show it (reached by a link, 025).
     */
    fun show(
        client: DaemonClient?, target: JsonObject, id: String?, redact: Boolean, gone: Boolean, unlisted: Boolean = false,
        /** Feature 040: search words to mark, and the tab of the hit when one was moved to. */
        marks: List<String> = emptyList(), hitTab: io.yamsergey.dta.present.network.HitTab? = null,
        /** Feature 041: the entry's hits, for the "Found in" line. */
        hits: List<io.yamsergey.dta.protocol.network.SearchHit> = emptyList(),
    ) {
        if (id == null) { shown = null; return empty(SELECT) }
        val marksChanged = marks != this.marks || hitTab != this.hitTab || hits != this.hits
        this.marks = marks
        this.hitTab = hitTab
        this.hits = hits
        if (shown == id && this.client === client) {
            if (gone != shownGone || unlisted != shownUnlisted || marksChanged) { shownUnlisted = unlisted; render(gone) }
            return
        }
        shownUnlisted = unlisted
        lastRedact = redact
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

    /** Tests: shows an already loaded `network.entry` [result] for [id]. */
    internal fun showLoaded(id: String, result: JsonObject, unlisted: Boolean = false) {
        shown = id
        shownUnlisted = unlisted
        raw = result
        render(false)
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
            detail.request?.body?.let { tabs.addTab("Payload", BodyPane(project, c, target, id, it, scope, marks)) }
            detail.response?.body?.let { tabs.addTab("Response", BodyPane(project, c, target, id, it, scope, marks)) }
        }
        if (detail.messages.isNotEmpty() || detail.messagesMore != null) tabs.addTab("Messages", messagesPanel(detail))
        detail.initiator?.let { tabs.addTab("Initiator", scroll(initiatorPage(detail))) }
        // A search hit: its tab. Otherwise the same tab stays open from entry to entry when the new one has it.
        val wanted = hitTab?.let(::tabTitle) ?: selected
        (0 until tabs.tabCount).firstOrNull { tabs.getTitleAt(it) == wanted }?.let { tabs.selectedIndex = it }

        removeAll()
        add(header, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun tabTitle(t: io.yamsergey.dta.present.network.HitTab) = when (t) {
        io.yamsergey.dta.present.network.HitTab.HEADERS -> "Headers"
        io.yamsergey.dta.present.network.HitTab.REQUEST, io.yamsergey.dta.present.network.HitTab.MESSAGE -> "Payload"
        io.yamsergey.dta.present.network.HitTab.RESPONSE -> "Response"
    }

    private fun selectTab(t: io.yamsergey.dta.present.network.HitTab) {
        (0 until tabs.tabCount).firstOrNull { tabs.getTitleAt(it) == tabTitle(t) }?.let { tabs.selectedIndex = it }
    }

    private fun renderHeader(detail: EntryDetail, gone: Boolean) {
        header.removeAll()
        detail.summary.firstOrNull()?.let { first ->
            val title = JBLabel(first.value).apply { font = font.deriveFont(Font.BOLD); setCopyable(true) }
            val id = shown
            val menu = copyMenu
            if (menu != null && id != null) {
                val type = (raw?.get("entry") as? JsonObject)?.get("type")?.let { (it as JsonPrimitive).content }
                lateinit var link: ActionLink
                link = ActionLink("Copy ▾") { menu.show(id, type, link, 0, link.height) }
                header.add(row(listOf(title, link)))
            } else header.add(title.left())
        }
        val statements = listOfNotNull(
            if (gone) Statement(StatementKind.EVICTED, word = "no longer in the list", detail = "no longer in the list") else null,
        ) + Statements.forNetworkTab(detail.statements)
        if (statements.isNotEmpty()) header.add(row(statements.map { StatementMark(it) }))
        if (shownUnlisted) header.add(JBLabel("Not in the list as filtered").apply { foreground = JBColor.GRAY }.left())
        // Feature 041: where the search words are, each place a link to its tab.
        val found = io.yamsergey.dta.present.network.Hits.foundIn(hits)
        if (found.isNotEmpty()) {
            header.add(row(listOf<JComponent>(JBLabel("Found in").apply { foreground = JBColor.GRAY }) + found.map { f ->
                ActionLink(f.label) { selectTab(f.tab) }.apply { toolTipText = f.first.excerpt.render { "[$it]" } }
            }))
        }
        detail.connection?.let { link ->
            header.add(row(listOf(
                JBLabel("Connection").apply { foreground = JBColor.GRAY },
                ActionLink(link.label) { open(link.id) }.apply { toolTipText = "Show the connection with all its messages" },
            )))
        }
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
        marks = this@EntryDetailPane.marks
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

    /**
     * Feature 041: the format line (rules by name, how many each read and why not) above the
     * messages as a table — kind, id and topic in columns, the text as sent (or a rule's payload).
     */
    private fun messagesPanel(detail: EntryDetail): JComponent {
        val line = io.yamsergey.dta.present.network.FormatLine.of(detail)
        val labels = line.labels()
        val top = row(buildList {
            add(JBLabel(line.text).apply { foreground = JBColor.GRAY; setCopyable(true) })
            if (client != null) {
                if (line.rules.isEmpty()) add(ActionLink("Set format…") { editFormat(null) }.apply { toolTipText = "Say how this app's messages are built: a pattern or field paths, tried on these messages" })
                line.rules.forEachIndexed { i, rule ->
                    val name = if (line.rules.size > 1) " ${labels[i]}" else ""
                    add(ActionLink("Edit$name…") { editFormat(rule.id) }.apply { toolTipText = "Edit rule ${rule.name} (${rule.id})" })
                    add(ActionLink("Remove$name") { removeFormat(rule.id) }.apply { toolTipText = "Remove rule ${rule.name} (${rule.id})" })
                }
            }
        }).apply { border = JBUI.Borders.empty(6, 12, 6, 12) }
        val table = io.yamsergey.dta.present.network.MessageTable.of(detail.messages)
        val model = object : javax.swing.table.AbstractTableModel() {
            override fun getRowCount() = table.rows.size
            override fun getColumnCount() = table.columns.size
            override fun getColumnName(column: Int) = table.columns[column]
            override fun getValueAt(row: Int, column: Int): Any = table.cell(table.rows[row], table.columns[column])
        }
        val grid = com.intellij.ui.table.JBTable(model).apply {
            setShowGrid(false)
            autoResizeMode = javax.swing.JTable.AUTO_RESIZE_LAST_COLUMN
            emptyText.text = "No messages"
            val mono = Font(com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().globalScheme.editorFontName, Font.PLAIN, font.size)
            setDefaultRenderer(Any::class.java, object : javax.swing.table.DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(t: javax.swing.JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, column: Int): java.awt.Component {
                    val c = super.getTableCellRendererComponent(t, value, selected, false, row, column)
                    val name = table.columns[column]
                    font = if (name == io.yamsergey.dta.present.network.MessageTable.TEXT) mono else t.font
                    if (!selected && name != io.yamsergey.dta.present.network.MessageTable.TEXT && name != io.yamsergey.dta.present.network.MessageTable.KIND) foreground = JBColor.GRAY
                    toolTipText = if (name == io.yamsergey.dta.present.network.MessageTable.TEXT) (value as? String)?.take(2000) else null
                    return c
                }
            })
            fun width(name: String, w: Int) { table.columns.indexOf(name).takeIf { it >= 0 }?.let { columnModel.getColumn(it).apply { preferredWidth = JBUI.scale(w); if (name == io.yamsergey.dta.present.network.MessageTable.DIRECTION) maxWidth = JBUI.scale(w) } } }
            width(io.yamsergey.dta.present.network.MessageTable.TIME, 96)
            width(io.yamsergey.dta.present.network.MessageTable.DIRECTION, 24)
            width(io.yamsergey.dta.present.network.MessageTable.KIND, 110)
            width(io.yamsergey.dta.present.network.MessageTable.ID, 70)
            width(io.yamsergey.dta.present.network.MessageTable.TOPIC, 110)
            width(io.yamsergey.dta.present.network.MessageTable.TEXT, 520)
            toolTipText = "Double-click a message to open it"
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (e.clickCount != 2) return
                    val r = rowAtPoint(e.point).takeIf { it >= 0 } ?: return
                    open(table.rows[r].id)
                }
            })
        }
        return JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(JBScrollPane(grid), BorderLayout.CENTER)
            detail.messagesMore?.let { add(JBLabel(it).apply { foreground = JBColor.GRAY; border = JBUI.Borders.empty(4, 12) }, BorderLayout.SOUTH) }
        }
    }

    /** Feature 039: the connection's format rule — a new one prefilled with its socket, or [rule] to replace. */
    private fun editFormat(rule: String?) {
        val c = client ?: return
        val connection = shown ?: return
        val url = (raw?.get("entry") as? JsonObject)?.get("url")?.let { (it as? JsonPrimitive)?.content }
        scope.launch {
            val existing = rule?.let { id ->
                runCatching { c.call("network.format.list", target).result as kotlinx.serialization.json.JsonArray }.getOrNull()
                    ?.mapNotNull { it as? JsonObject }?.firstOrNull { (it["id"] as? JsonPrimitive)?.content == id }
            }
            withContext(Dispatchers.Main) {
                val form = existing?.let { io.yamsergey.dta.present.network.FormatRuleForm.of(it) } ?: io.yamsergey.dta.present.network.FormatRuleForm.forConnection(url)
                if (FormatRuleDialog(project, c, target, connection, form, rule, scope).showAndGet()) { reload(); formatsChanged() }
            }
        }
    }

    private fun removeFormat(rule: String) {
        val c = client ?: return
        scope.launch {
            runCatching { c.call("network.format.remove", JsonObject(target + mapOf("id" to JsonPrimitive(rule)))) }
            withContext(Dispatchers.Main) { reload(); formatsChanged() }
        }
    }

    /** Loads the shown entry again (its messages read with the rules as they are now). */
    private fun reload() {
        val id = shown ?: return
        val c = client ?: return
        val redact = lastRedact
        shown = null
        show(c, target, id, redact, shownGone, shownUnlisted)
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
