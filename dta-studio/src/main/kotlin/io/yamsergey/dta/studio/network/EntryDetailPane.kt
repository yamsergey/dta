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
    private var marks = io.yamsergey.dta.present.network.SearchMarks()
    private var hitTab: io.yamsergey.dta.present.network.HitTab? = null
    private var hits: List<io.yamsergey.dta.protocol.network.SearchHit> = emptyList()
    private val navigator = FrameNavigator(project)

    init {
        empty(SELECT)
    }

    // A long title or header value must not push the split: the list keeps its width, the detail wraps or cuts.
    override fun getMinimumSize(): java.awt.Dimension = super.getMinimumSize().let { java.awt.Dimension(minOf(it.width, JBUI.scale(200)), it.height) }

    // The header and tabs are out of the hierarchy while nothing is selected: a theme change reaches them too.
    override fun updateUI() {
        super.updateUI()
        @Suppress("SENSELESS_COMPARISON") if (header != null) listOf(header, tabs, placeholder).forEach { javax.swing.SwingUtilities.updateComponentTreeUI(it) }
    }

    /**
     * Shows [id]; [gone] adds the caveat that it is no longer in the list (patterns.md); [unlisted]
     * that the list as filtered does not show it (reached by a link, 025).
     */
    fun show(
        client: DaemonClient?, target: JsonObject, id: String?, redact: Boolean, gone: Boolean, unlisted: Boolean = false,
        /** Feature 040: search words (D-034: and body filters) to mark, and the tab of the hit when one was moved to. */
        marks: io.yamsergey.dta.present.network.SearchMarks = io.yamsergey.dta.present.network.SearchMarks(), hitTab: io.yamsergey.dta.present.network.HitTab? = null,
        /** Feature 041: the entry's hits, for the "Found in" line. */
        hits: List<io.yamsergey.dta.protocol.network.SearchHit> = emptyList(),
        /** D-023: the selection's live revision; a new one reads the entry again, in place. */
        revision: Long = 0,
    ) {
        if (id == null) { shown = null; return empty(SELECT) }
        val marksChanged = marks != this.marks || hitTab != this.hitTab || hits != this.hits
        this.marks = marks
        this.hitTab = hitTab
        this.hits = hits
        if (shown == id && this.client === client) {
            if (gone != shownGone || unlisted != shownUnlisted || marksChanged) { shownUnlisted = unlisted; render(gone) }
            if (revision != shownRevision) follow(revision)
            return
        }
        shownRevision = revision
        shownUnlisted = unlisted
        lastRedact = redact
        this.client = client
        this.target = target
        shown = id
        // D-034: a hit only in what the app saw (a script changed the body) opens that side, where it is.
        side = if (appSideOnly(hits)) Side.APP else Side.WIRE
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

    private var shownRevision = 0L
    private var following: Job? = null

    /**
     * D-023: the shown entry changed live (state, end, messages): read again at most every
     * [FOLLOW_MS], shown in place — the tab, and the message selected, stay.
     */
    private fun follow(revision: Long) {
        shownRevision = revision
        if (following?.isActive == true) return
        val c = client ?: return
        val id = shown ?: return
        val params = JsonObject(target + mapOf("id" to JsonPrimitive(id)) + if (lastRedact) mapOf("redact" to JsonPrimitive(true)) else emptyMap())
        following = scope.launch {
            kotlinx.coroutines.delay(FOLLOW_MS)
            val asked = shownRevision
            val result = runCatching { c.call("network.entry", params).result.jsonObject }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) {
                if (shown != id || loading?.isActive == true) return@withContext
                raw = result
                render(shownGone, inPlace = true)
                // Changed again while it was read: once more.
                if (shownRevision != asked) follow(shownRevision)
            }
        }
    }

    /** Tests: the shown entry read again live (D-023's follow), with [result]. */
    internal fun followLoaded(result: JsonObject) {
        raw = result
        render(shownGone, inPlace = true)
    }

    /** Tests: shows an already loaded `network.entry` [result] for [id]. */
    internal fun showLoaded(id: String, result: JsonObject, unlisted: Boolean = false) {
        shown = id
        shownUnlisted = unlisted
        raw = result
        render(false)
    }

    private fun render(gone: Boolean, inPlace: Boolean = false) {
        shownGone = gone
        val detail = EntryDetail.of(raw ?: return, side)
        val id = shown ?: return
        renderHeader(detail, gone)
        if (inPlace && renderInPlace(detail, id)) {
            revalidate()
            repaint()
            return
        }

        val selected = tabs.selectedIndex.takeIf { it >= 0 }?.let { tabs.getTitleAt(it).substringBefore(" · ") }
        val message = (tabs.components.firstOrNull { it is MessagesView } as? MessagesView)?.selectedId
        tabs.removeAll()
        tabs.addTab("Headers", scroll(headersPage(detail)))
        val c = client
        if (c != null) {
            detail.request?.body?.let { tabs.addTab("Payload", BodyPane(project, c, bodyTarget(), id, it, scope, marks.inBody(io.yamsergey.dta.present.network.SearchMarks.BodySide.REQUEST))) }
            detail.response?.body?.let { tabs.addTab("Response", BodyPane(project, c, bodyTarget(), id, it, scope, marks.inBody(io.yamsergey.dta.present.network.SearchMarks.BodySide.RESPONSE))) }
        }
        if (detail.messages.isNotEmpty() || detail.messagesMore != null) tabs.addTab(detail.messagesTitle, messagesPanel(detail).also { v -> message?.let { (v as? MessagesView)?.select(it) } })
        detail.initiator?.let { tabs.addTab("Initiator", scroll(initiatorPage(detail))) }
        // A search hit: its tab. Otherwise the same tab stays open from entry to entry when the new one has it.
        val wanted = hitTab?.let(::tabTitle) ?: selected
        (0 until tabs.tabCount).firstOrNull { tabs.getTitleAt(it).substringBefore(" · ") == wanted }?.let { tabs.selectedIndex = it }

        removeAll()
        add(header, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    /**
     * D-091 (042 FR-002): the shown entry read again live (D-023): when it has the same tabs, each is refreshed where it
     * is and the Messages view takes the new messages itself (its expanded rows, selection, focus and scroll stay).
     * False when the tabs differ: the caller builds them anew.
     */
    private fun renderInPlace(detail: EntryDetail, id: String): Boolean {
        val view = (0 until tabs.tabCount).map { tabs.getComponentAt(it) }.filterIsInstance<MessagesView>().singleOrNull() ?: return false
        val c = client
        val wanted = buildList {
            add("Headers" to { scroll(headersPage(detail)) as java.awt.Component })
            if (c != null) {
                detail.request?.body?.let { b -> add("Payload" to { BodyPane(project, c, bodyTarget(), id, b, scope, marks.inBody(io.yamsergey.dta.present.network.SearchMarks.BodySide.REQUEST)) as java.awt.Component }) }
                detail.response?.body?.let { b -> add("Response" to { BodyPane(project, c, bodyTarget(), id, b, scope, marks.inBody(io.yamsergey.dta.present.network.SearchMarks.BodySide.RESPONSE)) as java.awt.Component }) }
            }
            if (detail.messages.isNotEmpty() || detail.messagesMore != null) add(detail.messagesTitle to { view as java.awt.Component })
            detail.initiator?.let { add("Initiator" to { scroll(initiatorPage(detail)) as java.awt.Component }) }
        }
        val titles = (0 until tabs.tabCount).map { tabs.getTitleAt(it).substringBefore(" · ") }
        if (titles != wanted.map { it.first.substringBefore(" · ") }) return false
        wanted.forEachIndexed { i, (title, make) ->
            if (tabs.getComponentAt(i) === view) {
                view.update(detail, raw ?: return false, FormatControl.top(detail, client, target, scope, edit = ::editFormat, remove = ::removeFormat))
            } else tabs.setComponentAt(i, make())
            if (tabs.getTitleAt(i) != title) tabs.setTitleAt(i, title)
        }
        return true
    }

    private fun tabTitle(t: io.yamsergey.dta.present.network.HitTab) = when (t) {
        io.yamsergey.dta.present.network.HitTab.HEADERS -> "Headers"
        io.yamsergey.dta.present.network.HitTab.REQUEST, io.yamsergey.dta.present.network.HitTab.MESSAGE -> "Payload"
        io.yamsergey.dta.present.network.HitTab.RESPONSE -> "Response"
    }

    /** D-034: a hit's place — on the side (wire or what the app saw) where it is, then its tab. */
    private fun open(f: io.yamsergey.dta.present.network.FoundPlace) {
        val wanted = if (isAppSide(f.first)) Side.APP else Side.WIRE
        val hasAppSide = raw?.let { EntryDetail.of(it, side).intervention?.hasAppSide } == true
        if (wanted != side && (wanted == Side.WIRE || hasAppSide)) { side = wanted; render(shownGone) }
        selectTab(f.tab)
    }

    private fun isAppSide(h: io.yamsergey.dta.protocol.network.SearchHit) = h.part == io.yamsergey.dta.protocol.network.BodyPart.APP_REQUEST ||
        h.part == io.yamsergey.dta.protocol.network.BodyPart.APP_RESPONSE || h.part == io.yamsergey.dta.protocol.network.BodyPart.APP_MESSAGE

    /** Every body hit is in what the app saw: none on the wire. */
    private fun appSideOnly(hits: List<io.yamsergey.dta.protocol.network.SearchHit>): Boolean {
        val bodies = hits.filter { it.part != null }
        return bodies.isNotEmpty() && bodies.all(::isAppSide) && hits.none { it.part == null }
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
        ) + Statements.forNetworkTab(detail.statements).filterNot { detail.ending != null && it.kind == StatementKind.FAILED }
        if (statements.isNotEmpty()) header.add(row(statements.map { StatementMark(it) }))
        // D-023: how a socket ended, in the failure colour (in place of the bare failed mark); it wraps when narrow.
        detail.ending?.let { header.add(io.yamsergey.dta.studio.ui.WrappingText(it, DtaColors.of("status-server")).apply { border = JBUI.Borders.emptyTop(4) }.left()) }
        if (shownUnlisted) header.add(JBLabel("Not in the list as filtered").apply { foreground = JBColor.GRAY }.left())
        // Feature 041: where the search words are, each place a link to its tab.
        val found = io.yamsergey.dta.present.network.Hits.foundIn(hits)
        if (found.isNotEmpty()) {
            header.add(row(listOf<JComponent>(JBLabel("Found in").apply { foreground = JBColor.GRAY }) + found.map { f ->
                ActionLink(f.label) { open(f) }.apply { toolTipText = f.first.excerpt.render { "[$it]" } }
            }))
        }
        detail.connection?.let { link ->
            header.add(row(listOf(
                JBLabel("Connection").apply { foreground = JBColor.GRAY },
                ActionLink(link.label) { open(link.id) }.apply { toolTipText = "Show the connection with all its messages" },
            )))
        }
        detail.intervention?.let { i ->
            // D-024: one sentence, the script named as the plugin knows it; it wraps in a narrow pane.
            header.add(io.yamsergey.dta.studio.ui.WrappingText(i.sentence(ScriptNames.name(i.scriptId)), DtaColors.of("intervened")).apply { border = JBUI.Borders.emptyTop(4) }.left())
            val items = mutableListOf<JComponent>()
            if (i.hasAppSide) {
                items += ComboBox(arrayOf("On the wire", "What the app saw")).apply {
                    selectedIndex = if (side == Side.WIRE) 0 else 1
                    addActionListener { side = if (selectedIndex == 0) Side.WIRE else Side.APP; render(shownGone) }
                }
            }
            if (items.isNotEmpty()) header.add(row(items))
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
     * Feature 042: the format control and a caption on top (one purpose per line), the messages
     * as a list of app content (first lines, expandable), the selected one whole below.
     */
    private fun messagesPanel(detail: EntryDetail): JComponent {
        val top = FormatControl.top(detail, client, target, scope, edit = ::editFormat, remove = ::removeFormat)
        return MessagesView(client, bodyTarget(), scope, detail, raw ?: JsonObject(emptyMap()), marks.inBody(io.yamsergey.dta.present.network.SearchMarks.BodySide.ANY), top, open)
    }

    /** What `network.body` is asked with: the view's redaction too (D-003), as the entry above it is. */
    private fun bodyTarget(): JsonObject = if (lastRedact) JsonObject(target + ("redact" to JsonPrimitive(true))) else target

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
        // Feature 043: nothing selected yet, the mascot above the sentence.
        placeholder.icon = if (text == SELECT) MASCOT else null
        placeholder.horizontalTextPosition = SwingConstants.CENTER
        placeholder.verticalTextPosition = SwingConstants.BOTTOM
        placeholder.iconTextGap = JBUI.scale(14)
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
        const val FOLLOW_MS = 700L
        private val MASCOT by lazy { com.intellij.openapi.util.IconLoader.getIcon("/icons/mascot.svg", EntryDetailPane::class.java.classLoader) }
    }
}
