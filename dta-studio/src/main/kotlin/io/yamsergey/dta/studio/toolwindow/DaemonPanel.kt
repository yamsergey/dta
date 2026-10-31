package io.yamsergey.dta.studio.toolwindow

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.ActionLink
import io.yamsergey.dta.present.archive.ArchiveTree
import io.yamsergey.dta.present.context.ContextState
import io.yamsergey.dta.present.daemon.DaemonView
import io.yamsergey.dta.studio.ContextController
import io.yamsergey.dta.studio.ui.StatementMark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JPanel

/** The Daemon view (FR-016): the shared daemon and the app's capabilities, refreshed on every context change. */
class DaemonPanel(
    private val controller: ContextController,
    private val scope: CoroutineScope,
    /** Opens an archive scope in the Network view (feature 011). */
    private val showArchive: (String) -> Unit = {},
) : JPanel(BorderLayout()) {
    private val body = JPanel(BorderLayout())

    init {
        add(JBScrollPane(body), BorderLayout.CENTER)
    }

    fun refresh(state: ContextState) {
        injection = state.injectionLine
        val client = controller.client ?: return show(null)
        scope.launch {
            val status = runCatching { client.call("daemon.status").result.jsonObject }.getOrNull()
            val capability = if (state.device != null && state.app != null && state.connection != io.yamsergey.dta.present.context.Connection.NO_DTA) {
                runCatching { client.call("capability.report", buildJsonObject { put("device", state.device); put("app", state.app) }).result as JsonObject }.getOrNull()
            } else {
                null
            }
            val view = status?.let { DaemonView.from(it, capability) }
            val archive = runCatching { client.call("archive.list").result.jsonObject }.getOrNull()
            withContext(Dispatchers.Main) {
                show(view)
                showArchive(archive, state)
            }
        }
    }

    private var injection = ""

    private fun show(view: DaemonView?) {
        body.removeAll()
        if (view == null) {
            body.add(JBLabel("No DTA daemon connected."), BorderLayout.NORTH)
        } else {
            val form = FormBuilder.createFormBuilder()
                .addLabeledComponent("Daemon", JBLabel(view.version))
                .addLabeledComponent("Process", JBLabel(view.process))
                .addLabeledComponent("Injection", JBLabel(injection))
                .addLabeledComponent("adb", view.adbProblem?.let { StatementMark(it) } ?: JBLabel(view.adb ?: ""))
                .addLabeledComponent("Clients", JBLabel(view.clients.joinToString(", ")))
                .addLabeledComponent("Devices", JBLabel(view.devices.joinToString(", ").ifEmpty { "none" }))
                .addLabeledComponent("App connections", JBLabel(view.connections.joinToString(", ").ifEmpty { "none" }))
            view.sidekick?.let { form.addSeparator().addLabeledComponent("App", JBLabel(it)) }
            view.capabilities.forEach { row ->
                val line = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
                line.add(JBLabel("    ".repeat(row.depth) + row.name))
                line.add(row.statement?.let { StatementMark(it) } ?: JBLabel(row.status))
                row.reason?.let { line.add(JBLabel(it).apply { foreground = com.intellij.ui.JBColor.GRAY }) }
                form.addComponent(line)
                // What the gap means and the next step, wrapped under its row.
                row.advice?.let { advice ->
                    val text = "<html>${advice.replace("&", "&amp;").replace("<", "&lt;")}</html>"
                    form.addComponent(JBLabel(text).apply { border = JBUI.Borders.emptyLeft(JBUI.scale(24 * (row.depth + 1))); setCopyable(true) })
                }
            }
            body.add(form.panel.apply { border = JBUI.Borders.empty(12) }, BorderLayout.NORTH)
        }
        body.revalidate()
        body.repaint()
    }

    private val archivePanel = JPanel(BorderLayout())

    /** The host's network archive (feature 011): what devices forgot, kept here; show or delete it. */
    private fun showArchive(listing: JsonObject?, state: ContextState) {
        archivePanel.removeAll()
        val form = FormBuilder.createFormBuilder().addSeparator()
        form.addComponent(JBLabel("Network archive").apply { font = JBUI.Fonts.label().asBold() })
        val rows = listing?.let { ArchiveTree.rows(it) }.orEmpty()
        if (listing == null) form.addComponent(JBLabel("The archive could not be read."))
        else if (rows.isEmpty()) form.addComponent(JBLabel(ArchiveTree.EMPTY))
        rows.forEach { row ->
            val line = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
            line.add(JBLabel("    ".repeat(row.depth) + row.text))
            row.note?.let { line.add(StatementMark(io.yamsergey.dta.present.Statement(io.yamsergey.dta.present.StatementKind.DEGRADED, detail = it))) }
            row.epoch?.takeIf { !row.live && row.packageName == state.app }?.let { epoch -> line.add(ActionLink("Show") { showArchive(epoch) }) }
            val target = when {
                row.epoch != null && !row.live -> "epoch" to JsonPrimitive(row.epoch!!)
                row.depth == 2 && row.installation != null -> "installation" to JsonPrimitive(row.installation!!)
                row.depth == 1 && row.packageName != null -> "packageName" to JsonPrimitive(row.packageName!!)
                else -> null
            }
            target?.let { (name, value) -> line.add(ActionLink("Delete…") { delete(name, value, state) }) }
            form.addComponent(line)
        }
        archivePanel.add(form.panel.apply { border = JBUI.Borders.empty(0, 12, 12, 12) }, BorderLayout.NORTH)
        if (archivePanel.parent == null) body.add(archivePanel, BorderLayout.CENTER) else { body.remove(archivePanel); body.add(archivePanel, BorderLayout.CENTER) }
        body.revalidate()
        body.repaint()
    }

    /** Says what would go, asks, then deletes; the live history is refused by the daemon. */
    private fun delete(name: String, value: JsonPrimitive, state: ContextState) {
        val client = controller.client ?: return
        scope.launch {
            val preview = runCatching { client.call("archive.delete", JsonObject(mapOf(name to value))).result.jsonObject }
            withContext(Dispatchers.Main) {
                val removed = preview.getOrNull()?.get("removed") as? JsonObject
                if (removed == null) {
                    Messages.showErrorDialog(this@DaemonPanel, preview.exceptionOrNull()?.message ?: "The archive could not be read.", "DTA Archive")
                    return@withContext
                }
                fun n(k: String) = (removed[k] as? JsonPrimitive)?.content ?: "0"
                val question = "Delete ${n("epochs")} archived epochs, ${n("entries")} entries and ${n("bodiesFreed")} bodies " +
                    "(${io.yamsergey.dta.present.Units.bytes(n("bytesFreed").toLong())})? This cannot be undone."
                if (Messages.showYesNoDialog(this@DaemonPanel, question, "Delete Archived History", Messages.getWarningIcon()) == Messages.YES) {
                    scope.launch {
                        runCatching { client.call("archive.delete", JsonObject(mapOf(name to value, "confirm" to JsonPrimitive(true)))) }
                        withContext(Dispatchers.Main) { refresh(state) }
                    }
                }
            }
        }
    }
}
