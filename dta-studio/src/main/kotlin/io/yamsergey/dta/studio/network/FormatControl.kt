package io.yamsergey.dta.studio.network

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.client.DaemonClient
import io.yamsergey.dta.present.network.EntryDetail
import io.yamsergey.dta.present.network.EntryKind
import io.yamsergey.dta.present.network.FormatLine
import io.yamsergey.dta.present.network.formats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSeparator

/**
 * Feature 042 (design system "Collections a person manages"): "Format: <name> ▾" — the rules
 * for this socket in a popup, each with what it read and why not, Edit… and Remove, and
 * "+ New rule…" at the end. The tab's top line is this control and a caption, nothing else.
 */
object FormatControl {
    /** The control's label: the rules in use by name, else the format detected, else none. */
    fun label(detail: EntryDetail): String {
        val line = FormatLine.of(detail)
        val name = when {
            detail.formatUse.isNotEmpty() -> line.labels().joinToString(", ")
            else -> formats(detail.messages)?.substringBefore(" (") ?: "none"
        }
        return "Format: $name ▾"
    }

    /** "12 messages · ↑2 ↓10". */
    fun caption(detail: EntryDetail): String {
        val out = detail.messages.count { it.kind == EntryKind.WS_OUT }
        val n = detail.messages.size
        return "$n message${if (n == 1) "" else "s"} · ↑$out ↓${n - out}"
    }

    fun top(
        detail: EntryDetail,
        client: DaemonClient?,
        target: JsonObject,
        scope: CoroutineScope,
        edit: (String?) -> Unit,
        remove: (String) -> Unit,
    ): JComponent = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), 0)).apply {
        border = JBUI.Borders.empty(6, 4, 6, 12)
        lateinit var link: ActionLink
        link = ActionLink(label(detail)) {
            if (client == null) return@ActionLink
            scope.launch {
                val rules = runCatching { client.call("network.format.list", target).result as JsonArray }.getOrNull()
                    ?.mapNotNull { it as? JsonObject }.orEmpty()
                withContext(Dispatchers.Main) {
                    var popup: JBPopup? = null
                    val content = popup(detail, rules, { id -> popup?.cancel(); edit(id) }, { id -> popup?.cancel(); remove(id) })
                    popup = JBPopupFactory.getInstance().createComponentPopupBuilder(content, null).setRequestFocus(true).setCancelOnClickOutside(true).createPopup()
                    popup.showUnderneathOf(link)
                }
            }
        }.apply { toolTipText = "The app's format rules for this socket: what each reads, edit, remove, add" }
        add(link)
        add(JBLabel(caption(detail)).apply { foreground = JBColor.GRAY })
    }

    /** The popup's content: one block per rule for this socket, then "+ New rule…". */
    internal fun popup(detail: EntryDetail, rules: List<JsonObject>, edit: (String?) -> Unit, remove: (String) -> Unit): JComponent =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8, 12)
            if (detail.formatUse.isEmpty()) {
                add(JBLabel("No rule for this socket: messages are read as ${formats(detail.messages)?.substringBefore(" (") ?: "plain text"}.").apply { foreground = JBColor.GRAY; alignmentX = java.awt.Component.LEFT_ALIGNMENT })
            }
            detail.formatUse.forEach { use ->
                val rule = rules.firstOrNull { (it["id"] as? JsonPrimitive)?.content == use.rule }
                fun str(k: String) = (rule?.get(k) as? JsonPrimitive)?.content
                val direction = when (str("direction")) { "in" -> "received"; "out" -> "sent by the app"; else -> "both directions" }
                add(JPanel(BorderLayout(JBUI.scale(16), 0)).apply {
                    isOpaque = false
                    alignmentX = java.awt.Component.LEFT_ALIGNMENT
                    border = JBUI.Borders.empty(4, 0)
                    add(JPanel().apply {
                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                        isOpaque = false
                        add(JBLabel(use.name).apply { font = font.deriveFont(Font.BOLD) })
                        add(JBLabel("${str("urlPattern") ?: ""} · $direction").apply { foreground = JBColor.GRAY })
                        add(JBLabel("reads ${use.read} of ${use.of}" + use.unread.joinToString("", " · not read: ") { "${it.reason} (${it.count})" }.takeIf { use.unread.isNotEmpty() }.orEmpty()).apply { foreground = JBColor.GRAY })
                    }, BorderLayout.CENTER)
                    add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
                        isOpaque = false
                        add(ActionLink("Edit…") { edit(use.rule) })
                        add(ActionLink("Remove") { remove(use.rule) })
                    }, BorderLayout.EAST)
                })
            }
            add(JSeparator().apply { alignmentX = java.awt.Component.LEFT_ALIGNMENT; maximumSize = java.awt.Dimension(Int.MAX_VALUE, JBUI.scale(8)) })
            add(ActionLink("+ New rule…") { edit(null) }.apply { alignmentX = java.awt.Component.LEFT_ALIGNMENT; toolTipText = "Say how this app's messages are built: a pattern or field paths, tried on these messages" })
        }
}
