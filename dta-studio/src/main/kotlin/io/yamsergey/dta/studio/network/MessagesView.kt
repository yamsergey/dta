package io.yamsergey.dta.studio.network

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.yamsergey.dta.client.DaemonClient
import io.yamsergey.dta.present.network.ContentLines
import io.yamsergey.dta.present.network.EntryDetail
import io.yamsergey.dta.present.network.EntryKind
import io.yamsergey.dta.present.network.EntryRow
import io.yamsergey.dta.present.network.Highlights
import io.yamsergey.dta.present.network.Span
import io.yamsergey.dta.present.network.SpanKind
import io.yamsergey.dta.present.network.envelopeOf
import io.yamsergey.dta.present.network.label
import io.yamsergey.dta.present.network.replies
import io.yamsergey.dta.studio.ui.DtaColors
import io.yamsergey.dta.studio.ui.SEARCH_MARK
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListCellRenderer

/**
 * Feature 042 (design system "Lists of app content"): a socket's messages, each row its summary
 * and the first lines of the message as Formatted shows it, coloured; "▸ N more lines" expands
 * the row in place (→ / ← and a click on that line); a double-click opens the message on its
 * own (all body modes). Nothing depends on a column's width.
 */
class MessagesView(
    private val client: DaemonClient?,
    private val target: JsonObject,
    private val scope: CoroutineScope,
    detail: EntryDetail,
    /** The `network.entry` result: the messages' own JSON (stored sizes, to tell a cut preview). */
    raw: JsonObject,
    private val marks: List<String>,
    /** The tab's top line: the format control and the caption. */
    top: JComponent,
    /** A double-click opens the message as an entry of its own (025). */
    private val open: (String) -> Unit = {},
) : JPanel(BorderLayout()) {
    private class Item(val row: EntryRow, val payload: JsonObject?, val text: String?, val cut: Boolean, val note: String) {
        var expanded = false
        /** The whole text, fetched on expanding a message the list had only the start of. */
        var whole: String? = null
    }

    private val payloads: Map<String, JsonObject?> = (raw["messages"] as? JsonArray).orEmpty()
        .mapNotNull { it as? JsonObject }.associate { m -> (m["id"] as? JsonPrimitive)?.content.orEmpty() to (m["payload"] as? JsonObject) }

    private val items: List<Item> = run {
        val answers = replies(detail.messages)
        detail.messages.map { m ->
            val payload = payloads[m.id]
            val rulePayload = m.envelope?.takeIf { it.rule != null }?.payload
            val preview = m.preview?.takeIf { it != "binary" }
            val stored = (payload?.get("storedBytes") as? JsonPrimitive)?.content?.toLongOrNull() ?: 0
            val cut = rulePayload == null && preview != null && stored > preview.toByteArray(Charsets.UTF_8).size
            val note = listOfNotNull(answers[m.id]?.let { (asked, ms) -> "↩ answers ${asked.time}" + (ms?.let { " (+$it ms)" } ?: "") }, m.timing.ifEmpty { null }).joinToString("   ")
            Item(m, payload, rulePayload ?: preview, cut, note)
        }
    }

    private val model = javax.swing.DefaultListModel<Item>().apply { items.forEach(::addElement) }
    private val list = JBList(model).apply {
        cellRenderer = Renderer()
        emptyText.text = "No messages"
        selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
    }
    private val mono: Font get() = Font(EditorColorsManager.getInstance().globalScheme.editorFontName, Font.PLAIN, UIUtil.getLabelFont().size)

    init {
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val index = list.locationToIndex(e.point).takeIf { it >= 0 } ?: return
                val cell = list.getCellBounds(index, index) ?: return
                // The "▸ N more lines" / "▾ less" line is the cell's last.
                val onMore = moreText(items[index]) != null && e.y >= cell.y + cell.height - lineHeight() - JBUI.scale(4)
                if (onMore) toggle(index) else if (e.clickCount == 2) open(items[index].row.id)
            }
        })
        list.registerKeyboardAction({ list.selectedIndex.takeIf { it >= 0 }?.let { expand(it, true) } }, KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), WHEN_FOCUSED)
        list.registerKeyboardAction({ list.selectedIndex.takeIf { it >= 0 }?.let { expand(it, false) } }, KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), WHEN_FOCUSED)
        add(top, BorderLayout.NORTH)
        add(JBScrollPane(list), BorderLayout.CENTER)
        detail.messagesMore?.let { add(JBLabel(it).apply { foreground = JBColor.GRAY; border = JBUI.Borders.empty(4, 12) }, BorderLayout.SOUTH) }
    }

    /** Tests: the rows as the person reads them (summary, then content lines, then the more line). */
    internal fun rowTexts(index: Int): List<String> = lines(items[index]).let { (summary, content, more) ->
        listOf(summary) + content.map { l -> l.joinToString("") { it.text } } + listOfNotNull(more)
    }

    internal val rowCount: Int get() = items.size

    internal fun select(index: Int) { list.selectedIndex = index }

    internal fun toggle(index: Int) = expand(index, !items[index].expanded)

    private fun expand(index: Int, on: Boolean) {
        val item = items[index]
        if (item.expanded == on || (on && moreText(item) == null && !item.expanded)) return
        item.expanded = on
        if (on && item.cut && item.whole == null) fetchWhole(item)
        relayout()
    }

    private fun relayout() {
        // A JList caches row heights: reset it so expanded rows take their height.
        list.fixedCellHeight = 1
        list.fixedCellHeight = -1
        list.revalidate()
        list.repaint()
    }

    private fun fetchWhole(item: Item) {
        val c = client ?: return
        scope.launch {
            val r = runCatching {
                c.call("network.body", JsonObject(target + mapOf("id" to JsonPrimitive(item.row.id), "part" to JsonPrimitive("message")))).result.jsonObject
            }.getOrNull() ?: return@launch
            val text = (r["text"] as? JsonPrimitive)?.content ?: return@launch
            val payload = envelopeOf(r["envelope"] as? JsonObject)?.takeIf { it.rule != null }?.payload
            withContext(Dispatchers.Main) { item.whole = payload ?: text; relayout() }
        }
    }

    private fun content(item: Item): ContentLines? {
        val text = item.whole ?: item.text ?: return null
        val whole = item.whole != null || !item.cut
        return ContentLines.of(text, if (item.expanded) EXPANDED_LINES else LINES, cut = !whole)
    }

    private fun moreText(item: Item): String? {
        val c = content(item) ?: return null
        val more = c.more
        return when {
            item.expanded -> "▾ less"
            more == null -> "▸ more"
            more > 0 -> "▸ $more more line${if (more == 1) "" else "s"}"
            else -> null
        }
    }

    private fun lines(item: Item): Triple<String, List<List<Span>>, String?> {
        val r = item.row
        val summary = listOf(r.time, if (r.kind == EntryKind.WS_OUT) "↑" else "↓", r.envelope?.label().orEmpty(), item.note).filter { it.isNotEmpty() }.joinToString("  ")
        val content = content(item)
        val binary = if (item.text == null) listOf(listOf(Span("binary", SpanKind.PLAIN))) else emptyList()
        return Triple(summary, content?.lines ?: binary, moreText(item))
    }

    private fun lineHeight() = list.getFontMetrics(mono).height

    private inner class Renderer : ListCellRenderer<Item> {
        override fun getListCellRendererComponent(l: JList<out Item>, value: Item, index: Int, selected: Boolean, focus: Boolean): Component {
            val bg = if (selected) UIUtil.getListSelectionBackground(l.hasFocus()) else l.background
            val panel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                background = bg
                border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(3, 8))
            }
            val r = value.row
            panel.add(SimpleColoredComponent().apply {
                isOpaque = false
                ipad = JBUI.emptyInsets()
                append(r.time + "  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append(if (r.kind == EntryKind.WS_OUT) "↑ " else "↓ ", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, DtaColors.of(r.kind.token)))
                r.envelope?.label()?.takeIf { it.isNotEmpty() }?.let { append("$it  ", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES) }
                if (value.note.isNotEmpty()) append(value.note, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                alignmentX = LEFT_ALIGNMENT
            })
            val (_, content, more) = lines(value)
            content.forEach { line -> panel.add(lineComponent(line).apply { alignmentX = LEFT_ALIGNMENT }) }
            more?.let { m ->
                panel.add(SimpleColoredComponent().apply {
                    isOpaque = false
                    ipad = JBUI.insetsLeft(12)
                    append(m, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBUI.CurrentTheme.Link.Foreground.ENABLED))
                    alignmentX = LEFT_ALIGNMENT
                })
            }
            return panel
        }

        private fun lineComponent(line: List<Span>) = SimpleColoredComponent().apply {
            isOpaque = false
            font = mono
            ipad = JBUI.insetsLeft(12)
            val text = line.joinToString("") { it.text }
            val hits = Highlights.spans(text, marks)
            var at = 0
            line.forEach { span ->
                val attrs = attributes(span.kind)
                var i = 0
                // Split the span where search hits start and end, marking the hit parts.
                while (i < span.text.length) {
                    val pos = at + i
                    val hit = hits.firstOrNull { pos in it }
                    val end = if (hit != null) minOf(span.text.length, hit.last + 1 - at) else
                        hits.filter { it.first > pos }.minOfOrNull { it.first - at }?.coerceAtMost(span.text.length) ?: span.text.length
                    val piece = span.text.substring(i, maxOf(end, i + 1))
                    append(piece, if (hit != null) SimpleTextAttributes(SEARCH_MARK, attrs.fgColor, null, attrs.style) else attrs)
                    i += piece.length
                }
                at += span.text.length
            }
        }

        private fun attributes(kind: SpanKind): SimpleTextAttributes = when (kind) {
            SpanKind.KEY -> BodyTree.attributes(DefaultLanguageHighlighterColors.INSTANCE_FIELD)
            SpanKind.STRING -> BodyTree.attributes(DefaultLanguageHighlighterColors.STRING)
            SpanKind.NUMBER -> BodyTree.attributes(DefaultLanguageHighlighterColors.NUMBER)
            SpanKind.LITERAL -> BodyTree.attributes(DefaultLanguageHighlighterColors.KEYWORD)
            SpanKind.PUNCT, SpanKind.PLAIN -> SimpleTextAttributes.REGULAR_ATTRIBUTES
        }
    }

    companion object {
        const val LINES = 3
        const val EXPANDED_LINES = 2000
    }
}
