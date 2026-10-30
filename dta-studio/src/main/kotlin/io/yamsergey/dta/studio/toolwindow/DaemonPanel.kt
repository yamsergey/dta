package io.yamsergey.dta.studio.toolwindow

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.present.context.ContextState
import io.yamsergey.dta.present.daemon.DaemonView
import io.yamsergey.dta.studio.ContextController
import io.yamsergey.dta.studio.ui.StatementMark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JPanel

/** The Daemon view (FR-016): the shared daemon and the app's capabilities, refreshed on every context change. */
class DaemonPanel(private val controller: ContextController, private val scope: CoroutineScope) : JPanel(BorderLayout()) {
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
            withContext(Dispatchers.Main) { show(view) }
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
}
