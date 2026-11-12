package io.yamsergey.dta.studio.toolwindow

import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.present.context.Connection
import io.yamsergey.dta.present.web.WebStatusLine
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.web.WebStatus
import io.yamsergey.dta.studio.ContextController
import io.yamsergey.dta.studio.inject.InjectionSettings
import io.yamsergey.dta.studio.ui.DtaColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.awt.FlowLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Feature 061: what browser capture is doing, above the Network list — only while a browser
 * switch is on and the tab is shown. Read off the UI thread every [POLL_MS]; a partial page
 * offers Reload.
 */
class WebStatusRow(
    private val context: ContextController,
    private val scope: CoroutineScope,
    private val settings: InjectionSettings = InjectionSettings.get(),
) : JPanel(com.intellij.util.ui.WrapLayout(FlowLayout.LEFT, JBUI.scale(6), 0)) {
    private var shown: List<WebStatusLine.Part> = emptyList()

    init {
        isVisible = false
        border = JBUI.Borders.empty(0, 2, 2, 8)
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                // A failed read (daemon gone, older daemon) shows nothing rather than a stale state.
                val parts = runCatching { read() }.getOrElse { emptyList() }
                SwingUtilities.invokeLater { show(parts) }
                delay(POLL_MS)
            }
        }
    }

    /** The parts to show: none when both switches are off or nothing is connected; the tab hidden keeps what it shows (no call). */
    private suspend fun read(): List<WebStatusLine.Part> {
        val b = settings.browsersFlow.value
        if (!b.webview && !b.customTabs) return emptyList()
        // The row itself is hidden while it has nothing to say: whether the tab is shown is its parent's.
        if (parent?.isShowing != true) return shown
        val st = context.state.value
        val client = context.client ?: return emptyList()
        val device = st.device ?: return emptyList()
        val app = st.app?.takeIf { st.connection == Connection.CONNECTED } ?: return emptyList()
        val result = client.call("web.status", buildJsonObject { put("device", JsonPrimitive(device)); put("app", JsonPrimitive(app)) }).result
        return WebStatusLine.of(DtaJson.decodeFromJsonElement(WebStatus.serializer(), result))
    }

    private fun show(parts: List<WebStatusLine.Part>) {
        if (parts == shown && isVisible == parts.isNotEmpty()) return
        shown = parts
        removeAll()
        parts.forEach { p ->
            add(JBLabel("${p.glyph} ${p.text}").apply {
                foreground = color(p.tone)
                toolTipText = p.tooltip
            })
            p.reload?.let { page -> add(ActionLink("Reload") { reload(page) }.apply { toolTipText = "Reload the page in the app, so DTA captures it whole" }) }
        }
        isVisible = parts.isNotEmpty()
        revalidate()
        repaint()
    }

    private fun reload(page: String) {
        val st = context.state.value
        val client = context.client ?: return
        val device = st.device ?: return
        val app = st.app ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                client.call("web.reload", buildJsonObject { put("device", JsonPrimitive(device)); put("app", JsonPrimitive(app)); put("page", JsonPrimitive(page)) })
            }
        }
    }

    private fun color(t: WebStatusLine.Tone) = when (t) {
        WebStatusLine.Tone.LIVE -> LIVE
        WebStatusLine.Tone.WAITING, WebStatusLine.Tone.MUTED -> JBColor.GRAY
        WebStatusLine.Tone.PROBLEM -> DtaColors.text(io.yamsergey.dta.present.StatementKind.DEGRADED)
    }

    private companion object {
        const val POLL_MS = 2_000L
        val LIVE = JBColor(0x2E7D32, 0x6CB46F)
    }
}
