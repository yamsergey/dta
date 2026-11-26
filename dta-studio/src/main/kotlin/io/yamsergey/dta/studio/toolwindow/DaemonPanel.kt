package io.yamsergey.dta.studio.toolwindow

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.present.StatementKind
import io.yamsergey.dta.present.Units
import io.yamsergey.dta.present.archive.HistorySection
import io.yamsergey.dta.present.context.ContextState
import io.yamsergey.dta.present.daemon.DaemonView
import io.yamsergey.dta.protocol.network.ArchiveListing
import io.yamsergey.dta.protocol.network.HistoryDefaults
import io.yamsergey.dta.protocol.network.HistorySummary
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.studio.ContextController
import io.yamsergey.dta.studio.ui.DtaColors
import io.yamsergey.dta.studio.ui.StatementMark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The Daemon view (FR-016): the shared daemon, the selected app's network history on the device
 * and on this computer (feature 066), and the app's capabilities; refreshed on every context change.
 */
class DaemonPanel(
    private val controller: ContextController,
    private val scope: CoroutineScope,
    /** Opens an archive scope in the Network view (feature 011). */
    private val showArchive: (String) -> Unit = {},
) : JPanel(BorderLayout()) {
    private val daemonSlot = JPanel(BorderLayout())
    private val historySlot = JPanel(BorderLayout())
    private val appSlot = JPanel(BorderLayout())
    private val body = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        listOf(daemonSlot, historySlot, appSlot).forEach { it.alignmentX = LEFT_ALIGNMENT; add(it) }
    }
    private var last: ContextState? = null

    /** The last history shown, laid out again when the panel crosses the narrow width (mockup's 480 px board). */
    private var shown: Triple<Result<HistorySummary>?, ArchiveListing?, ContextState>? = null
    private var narrow = false

    init {
        add(JBScrollPane(ViewportWide().apply { add(body, BorderLayout.NORTH) }), BorderLayout.CENTER)
        addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent?) {
                val now = width < JBUI.scale(NARROW)
                if (now == narrow) return
                narrow = now
                shown?.let { (h, l, st) -> showHistory(h, l, st) }
            }
        })
    }

    private var poll: kotlinx.coroutines.Job? = null
    private var shownView: DaemonView? = null
    private var reads = 0

    /** M3: shown, the tab reads again now and every 2 s (the counts move with the app's traffic) until [stopRefreshing]. */
    fun show(state: ContextState) {
        last = state
        if (poll?.isActive == true) return
        poll = scope.launch {
            while (isActive) {
                withContext(Dispatchers.Main) { last?.let { refresh(it) } }
                delay(2_000)
            }
        }
    }

    fun stopRefreshing() {
        poll?.cancel()
        poll = null
    }

    fun refresh(state: ContextState) {
        last = state
        injection = state.injectionLine
        // Another device or app: the numbers shown are not this one's; say so until the answer comes (never a stale count as fact).
        shown?.let { (_, _, was) -> if (was.device != state.device || was.app != state.app) { shown = null; showReading(state) } }
        val client = controller.client ?: return show(null)
        val read = ++reads
        scope.launch {
            val status = runCatching { client.call("daemon.status").result.jsonObject }.getOrNull()
            val capability = if (state.device != null && state.app != null && state.connection != io.yamsergey.dta.present.context.Connection.NO_DTA) {
                runCatching { client.call("capability.report", target(state)).result as JsonObject }.getOrNull()
            } else {
                null
            }
            val view = status?.let { DaemonView.from(it, capability) }
            val listing = runCatching { DtaJson.decodeFromJsonElement(ArchiveListing.serializer(), client.call("archive.list").result) }.getOrNull()
            val history = if (state.device != null && state.app != null) {
                runCatching { DtaJson.decodeFromJsonElement(HistorySummary.serializer(), client.call("network.history", target(state)).result) }
            } else {
                null
            }
            withContext(Dispatchers.Main) {
                // An older answer never replaces a newer one; an answer equal to what is shown changes nothing (links stay clickable).
                if (read != reads) return@withContext
                val same = view == shownView && shown?.let { (h, l, st) -> h?.getOrNull() == history?.getOrNull() && h?.exceptionOrNull()?.message == history?.exceptionOrNull()?.message && l == listing && st == state } == true
                if (same) return@withContext
                shownView = view
                show(view)
                showHistory(history, listing, state)
            }
        }
    }

    private fun showReading(state: ContextState) {
        historySlot.removeAll()
        historySlot.add(gray("Reading the network history of ${state.app ?: "the app"}…").apply { border = JBUI.Borders.empty(12) }, BorderLayout.NORTH)
        body.revalidate()
        body.repaint()
    }

    /** Feature 066: the Network tab's "Network History…" and the notification land here. */
    fun revealHistory() {
        val target = historySlot
        javax.swing.SwingUtilities.invokeLater { target.scrollRectToVisible(java.awt.Rectangle(0, 0, target.width, target.height)) }
    }

    private var injection = ""

    private fun target(state: ContextState) = JsonObject(mapOf("device" to JsonPrimitive(state.device!!), "app" to JsonPrimitive(state.app!!)))

    private fun show(view: DaemonView?) {
        daemonSlot.removeAll()
        appSlot.removeAll()
        if (view == null) {
            daemonSlot.add(JBLabel("No DTA daemon connected.").apply { border = JBUI.Borders.empty(12) }, BorderLayout.NORTH)
        } else {
            val form = FormBuilder.createFormBuilder()
                .addLabeledComponent("Daemon", JBLabel(view.version))
                .addLabeledComponent("Process", JBLabel(view.process))
                .addLabeledComponent("Injection", JBLabel(injection))
                .addLabeledComponent("adb", view.adbProblem?.let { StatementMark(it) } ?: JBLabel(view.adb ?: ""))
                .addLabeledComponent("Clients", JBLabel(view.clients.joinToString(", ")))
                .addLabeledComponent("Devices", JBLabel(view.devices.joinToString(", ").ifEmpty { "none" }))
                .addLabeledComponent("App connections", JBLabel(view.connections.joinToString(", ").ifEmpty { "none" }))
            daemonSlot.add(form.panel.apply { border = JBUI.Borders.empty(12, 12, 0, 12) }, BorderLayout.NORTH)
            val app = FormBuilder.createFormBuilder()
            view.sidekick?.let { app.addSeparator().addLabeledComponent("App", JBLabel(it)) }
            view.capabilities.forEach { row ->
                val line = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
                line.add(JBLabel("    ".repeat(row.depth) + row.name))
                line.add(row.statement?.let { StatementMark(it) } ?: JBLabel(row.status))
                row.reason?.let { line.add(JBLabel(it).apply { foreground = JBColor.GRAY }) }
                app.addComponent(line)
                // What the gap means and the next step, wrapped under its row.
                row.advice?.let { advice ->
                    app.addComponent(JBLabel(html(advice)).apply { border = JBUI.Borders.emptyLeft(JBUI.scale(24 * (row.depth + 1))); setCopyable(true) })
                }
            }
            appSlot.add(app.panel.apply { border = JBUI.Borders.empty(0, 12, 12, 12) }, BorderLayout.NORTH)
        }
        body.revalidate()
        body.repaint()
    }

    /** Feature 066 (mockup "066 Network history"): the app's history on the device and here, and what can be done with it. */
    private fun showHistory(history: Result<HistorySummary>?, listing: ArchiveListing?, state: ContextState) {
        shown = Triple(history, listing, state)
        historySlot.removeAll()
        val form = FormBuilder.createFormBuilder().addSeparator()
        val title = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            add(JBLabel("Network history").apply { font = JBUI.Fonts.label().asBold() })
            state.app?.let { add(gray(it)) }
        }
        form.addComponent(title)
        val summary = history?.getOrNull()
        when {
            state.app == null -> form.addComponent(gray("Choose an app to see its network history."))
            summary == null -> form.addComponent(gray("The history could not be read: ${history?.exceptionOrNull()?.message ?: "no answer"}"))
            else -> {
                val deviceKey = listing?.devices?.firstOrNull { it.serial == state.device }?.key
                val section = HistorySection.of(summary, listing, deviceKey, state.app!!, System.currentTimeMillis())
                section.banner?.let { text ->
                    form.addComponent(JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
                        background = DtaColors.tint(StatementKind.EVICTED)
                        border = JBUI.Borders.empty(6, 12)
                        // Wrapped to the section's width (an HTML label stays one line); the actions under it.
                        add(wrapped(text).apply { foreground = DtaColors.text(StatementKind.EVICTED) }, BorderLayout.CENTER)
                        add(links(ActionLink("Clear device history…") { clearDevice(summary, state) }, ActionLink("Change…") { change(summary, state) }).apply { isOpaque = false }, BorderLayout.SOUTH)
                    })
                }
                val device = column(
                    wrapped(section.device),
                    section.deviceSub?.let { wrapped(it).apply { foreground = JBColor.GRAY } },
                    if (section.deviceKnown) {
                        links(ActionLink("Change…") { change(summary, state) }, ActionLink("Clear device history…") { clearDevice(summary, state) })
                    } else {
                        links(ActionLink("Change…").apply { isEnabled = false }, ActionLink("Clear device history…").apply { isEnabled = false })
                    },
                )
                form.addComponent(labeled("On the device", device))
                val table = JPanel(GridBagLayout())
                val c = GridBagConstraints().apply { anchor = GridBagConstraints.WEST; insets = JBUI.insets(1, 0, 1, 14) }
                section.rows.forEachIndexed { y, r ->
                    c.gridy = y
                    // Narrow: the period goes (mockup), so the actions stay in view.
                    listOfNotNull(JBLabel(r.installation), JBLabel(r.period).takeUnless { narrow }, gray(r.state), JBLabel(r.entries), JBLabel(r.size)).forEachIndexed { x, cell ->
                        c.gridx = x
                        c.anchor = if (x >= (if (narrow) 2 else 3)) GridBagConstraints.EAST else GridBagConstraints.WEST
                        table.add(cell, c)
                    }
                    c.gridx = if (narrow) 4 else 5
                    c.anchor = GridBagConstraints.WEST
                    table.add(
                        if (r.live) gray("in Network") else links(ActionLink("Show") { showArchive(r.epochId) }, ActionLink("Delete…") { delete("epoch", JsonPrimitive(r.epochId), state) }),
                        c,
                    )
                }
                val computer = column(wrapped(section.host), table.takeIf { section.rows.isNotEmpty() }?.let { JPanel(BorderLayout()).apply { add(it, BorderLayout.WEST) } }, links(ActionLink("Delete archived history before…") { deleteBefore(state) }))
                form.addComponent(labeled("On this computer", computer).apply { border = JBUI.Borders.emptyTop(8) })
                if (section.others.isNotEmpty()) {
                    form.addSeparator()
                    form.addComponent(gray("Other apps on this computer"))
                    section.others.forEach { o ->
                        val delete = ActionLink("Delete…") { delete("packageName", JsonPrimitive(o.packageName), state) }
                        form.addComponent(if (narrow) column(JBLabel(o.packageName), links(gray(o.what), delete)) else links(JBLabel(o.packageName), gray(o.what), delete))
                    }
                }
            }
        }
        historySlot.add(form.panel.apply { border = JBUI.Borders.empty(8, 12, 8, 12) }, BorderLayout.NORTH)
        body.revalidate()
        body.repaint()
    }

    /** A label column, top-aligned with its value (the mockup's two columns). */
    private fun labeled(label: String, value: JComponent) = JPanel(BorderLayout()).apply {
        if (narrow) {
            add(gray(label), BorderLayout.NORTH)
        } else {
            add(JPanel(BorderLayout()).apply {
                add(gray(label), BorderLayout.NORTH)
                preferredSize = java.awt.Dimension(JBUI.scale(LABEL_WIDTH), 0)
            }, BorderLayout.WEST)
        }
        add(value, BorderLayout.CENTER)
    }

    /** Text that wraps to the width it is given (labels stay one line). */
    private fun wrapped(text: String) = com.intellij.ui.components.JBTextArea(text).apply {
        lineWrap = true
        wrapStyleWord = true
        isEditable = false
        isOpaque = false
        border = null
        font = JBUI.Fonts.label()
    }

    /** The tab's content is as wide as the view, so long lines wrap instead of scrolling sideways. */
    private class ViewportWide : JPanel(BorderLayout()), javax.swing.Scrollable {
        override fun getPreferredScrollableViewportSize(): java.awt.Dimension = preferredSize
        override fun getScrollableUnitIncrement(r: java.awt.Rectangle, o: Int, d: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(r: java.awt.Rectangle, o: Int, d: Int) = r.height
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }

    private fun gray(text: String) = JBLabel(text).apply { foreground = JBColor.GRAY }

    private fun html(text: String) = "<html>${text.replace("&", "&amp;").replace("<", "&lt;")}</html>"

    private fun column(vararg parts: JComponent?) = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        parts.filterNotNull().forEach { it.alignmentX = LEFT_ALIGNMENT; add(it) }
    }

    private fun links(vararg parts: JComponent) = JPanel(com.intellij.util.ui.WrapLayout(FlowLayout.LEFT, JBUI.scale(12), JBUI.scale(2))).apply {
        border = JBUI.Borders.emptyLeft(-JBUI.scale(12))
        parts.forEach { add(it) }
    }

    /** Change…: the keep-on-device budget and warning size, checked as the device checks them. */
    private fun change(summary: HistorySummary, state: ContextState) {
        val d = summary.device ?: return
        val keep = JBTextField(mb(d.keepBytes), 10)
        val warn = JBTextField(mb(d.warnBytes), 10)
        val dialog = object : DialogWrapper(this, true) {
            init {
                title = "Network History on the Device"
                init()
            }

            override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
                .addLabeledComponent("Keep on the device", keep)
                .addLabeledComponent("Warn above", warn)
                .addComponent(JBLabel(html("The device removes only what this computer has archived, oldest first, until it fits. Over the warning size you are told here, in CLI and agent answers, and in the device log when the app starts. At least 16 MB; the warning size cannot be under the budget.")).apply {
                    foreground = JBColor.GRAY
                    preferredSize = java.awt.Dimension(JBUI.scale(420), preferredSize.height * 4)
                })
                .panel

            override fun getPreferredFocusedComponent(): JComponent = keep

            override fun doValidate(): ValidationInfo? {
                val k = runCatching { HistoryDefaults.parseSize(keep.text) }.getOrElse { return ValidationInfo(it.message ?: "not a size", keep) }
                val w = runCatching { HistoryDefaults.parseSize(warn.text) }.getOrElse { return ValidationInfo(it.message ?: "not a size", warn) }
                return HistoryDefaults.problem(k, w)?.let { ValidationInfo(it, if (k < HistoryDefaults.MIN_BYTES) keep else warn) }
            }
        }
        if (!dialog.showAndGet()) return
        call("network.history.set", target(state) + mapOf("keep" to JsonPrimitive(keep.text.trim()), "warn" to JsonPrimitive(warn.text.trim())), state)
    }

    /** Clear device history…: this computer copies what it does not have yet first, and says what it could not keep. */
    private fun clearDevice(summary: HistorySummary, state: ContextState) {
        val d = summary.device ?: return
        val question = "Remove the network history of ${state.app} from the device: ${Units.count(d.entries, "entry", "entries")}, ${Units.bytes(d.bytes)}?\n\n" +
            "This computer first copies what it does not have yet; the archive keeps everything it has. " +
            "If the copy does not finish in 20 s, what was not copied is gone, and you are told how much."
        if (Messages.showYesNoDialog(this, question, "Clear Device History", Messages.getWarningIcon()) != Messages.YES) return
        val client = controller.client ?: return
        scope.launch {
            val result = runCatching { client.call("network.clear", target(state)).result.jsonObject }
            withContext(Dispatchers.Main) {
                result.exceptionOrNull()?.let { Messages.showErrorDialog(this@DaemonPanel, it.message ?: "The history could not be cleared.", "Clear Device History") }
                (result.getOrNull()?.get("notKept") as? JsonObject)?.let { n ->
                    fun v(k: String) = (n[k] as? JsonPrimitive)?.content
                    Messages.showWarningDialog(this@DaemonPanel, "${plural(v("entries"), "entry", "entries")} and ${plural(v("bodies"), "body", "bodies")} were not on this computer yet and are gone: ${v("reason")}.", "Clear Device History")
                }
                refresh(state)
            }
        }
    }

    /** Delete archived history before…: a date, then what would go, then confirm (this app only). */
    private fun deleteBefore(state: ContextState) {
        val format = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val text = Messages.showInputDialog(this, "Delete this app's archived history closed before:", "Delete Archived History", null,
            java.time.LocalDateTime.now().minusDays(1).withSecond(0).withNano(0).format(format), null) ?: return
        val ms = runCatching { java.time.LocalDateTime.parse(text.trim(), format).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }
            .getOrElse { Messages.showErrorDialog(this, "Write the date as 2026-11-14 00:00.", "Delete Archived History"); return }
        delete("closedBeforeMs", JsonPrimitive(ms), state, extra = mapOf("packageName" to JsonPrimitive(state.app!!)))
    }

    /** Says what would go, asks, then deletes; the live history is refused by the daemon. */
    private fun delete(name: String, value: JsonPrimitive, state: ContextState, extra: Map<String, JsonPrimitive> = emptyMap()) {
        val client = controller.client ?: return
        val params = mapOf(name to value) + extra
        scope.launch {
            val preview = runCatching { client.call("archive.delete", JsonObject(params)).result.jsonObject }
            withContext(Dispatchers.Main) {
                val removed = preview.getOrNull()?.get("removed") as? JsonObject
                if (removed == null) {
                    Messages.showErrorDialog(this@DaemonPanel, preview.exceptionOrNull()?.message ?: "The archive could not be read.", "DTA Archive")
                    return@withContext
                }
                fun n(k: String) = (removed[k] as? JsonPrimitive)?.content ?: "0"
                val question = "Delete ${plural(n("epochs"), "archived epoch")}, ${plural(n("entries"), "entry", "entries")} and ${plural(n("bodiesFreed"), "body", "bodies")} " +
                    "(${Units.bytes(n("bytesFreed").toLong())})? The live history and other apps are not touched. " +
                    "This cannot be undone; entries the device already removed are then gone from both."
                if (Messages.showYesNoDialog(this@DaemonPanel, question, "Delete Archived History", Messages.getWarningIcon()) == Messages.YES) {
                    call("archive.delete", params + mapOf("confirm" to JsonPrimitive(true)), state)
                }
            }
        }
    }

    private fun call(op: String, params: Map<String, kotlinx.serialization.json.JsonElement>, state: ContextState) {
        val client = controller.client ?: return
        scope.launch {
            val result = runCatching { client.call(op, JsonObject(params)) }
            withContext(Dispatchers.Main) {
                result.exceptionOrNull()?.let { Messages.showErrorDialog(this@DaemonPanel, it.message ?: "$op failed", "DTA") }
                refresh(state)
            }
        }
    }

    private companion object {
        const val LABEL_WIDTH = 128
        /** Below this width the section stacks (the mockup's narrow board). */
        const val NARROW = 600
    }

    /** "1 entry", "3 entries" for a count read from an answer as text. */
    private fun plural(count: String?, one: String, many: String = one + "s") = Units.count(count?.toLongOrNull() ?: 0, one, many)

    private fun mb(bytes: Long) = if (bytes % (1024L * 1024 * 1024) == 0L) "${bytes / (1024L * 1024 * 1024)} GB" else "${bytes / (1024 * 1024)} MB"
}
