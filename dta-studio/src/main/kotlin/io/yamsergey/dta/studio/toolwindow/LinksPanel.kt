package io.yamsergey.dta.studio.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.present.context.ContextState
import io.yamsergey.dta.present.links.LinkRow
import io.yamsergey.dta.present.links.LinksView
import io.yamsergey.dta.studio.ContextController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

/**
 * The Links view (feature 012): links that reached the app — when, the URI, how it arrived,
 * the activity — one link in full, the app's App Links state, and Open Link… on the device.
 * Refreshed every 2 s while shown (the list is a page of the app's one history, kinds = link).
 */
class LinksPanel(
    private val project: Project,
    private val controller: ContextController,
    private val scope: CoroutineScope,
) : JPanel(BorderLayout()) {
    private var rows: List<LinkRow> = emptyList()
    private val model = object : AbstractTableModel() {
        private val columns = listOf("Time", "URI", "Delivery", "Activity")
        override fun getRowCount() = rows.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(column: Int) = columns[column]
        override fun getValueAt(row: Int, column: Int): Any = rows[row].let { listOf(it.time, it.uri, it.delivery, it.activity)[column] }
    }
    private val table = JBTable(model).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        emptyText.text = LinksView.EMPTY
        selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) showDetail() }
    }
    private val detail = JPanel(BorderLayout())
    private val status = JBLabel().apply { foreground = JBColor.GRAY; setCopyable(true) }
    private var target: JsonObject? = null
    private var poll: Job? = null

    init {
        val open = JButton("Open Link…").apply { addActionListener { openLink() } }
        val appLinks = JButton("App Links").apply {
            toolTipText = "Which https hosts the app declares, whether Android verified them, and whether the user allows them."
            addActionListener { verification() }
        }
        val clear = JButton("Clear").apply { addActionListener { clear() } }
        val top = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 12)
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { add(open); add(appLinks); add(clear) }, BorderLayout.NORTH)
            add(status, BorderLayout.SOUTH)
        }
        add(top, BorderLayout.NORTH)
        add(JBSplitter(false, "dta.links.split", 0.6f).apply {
            firstComponent = JBScrollPane(table)
            secondComponent = JBScrollPane(detail)
        }, BorderLayout.CENTER)
    }

    /** Shown with this context: refreshed now and every 2 s until [stopRefreshing]. */
    fun show(state: ContextState) {
        target = if (state.device != null && state.app != null) JsonObject(mapOf("device" to JsonPrimitive(state.device), "app" to JsonPrimitive(state.app))) else null
        if (poll?.isActive == true) return
        poll = scope.launch {
            while (isActive) {
                refresh()
                delay(2_000)
            }
        }
    }

    fun stopRefreshing() {
        poll?.cancel()
        poll = null
    }

    private suspend fun refresh() {
        val client = controller.client
        val params = target
        if (client == null || params == null) return withContext(Dispatchers.Main) { update(emptyList(), "Choose an app with DTA inside.") }
        val result = runCatching { LinksView.rows(client.call("links.list", JsonObject(params + ("limit" to JsonPrimitive(500)))).result.jsonObject) }
        withContext(Dispatchers.Main) { update(result.getOrDefault(rows), result.exceptionOrNull()?.message) }
    }

    private fun update(next: List<LinkRow>, failure: String?) {
        val selected = table.selectedRow.takeIf { it >= 0 }?.let { rows.getOrNull(it)?.id }
        if (next != rows) {
            rows = next
            model.fireTableDataChanged()
            selected?.let { id -> rows.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { table.setRowSelectionInterval(it, it) } }
        }
        if (failure != null) status.text = failure
    }

    private fun showDetail() {
        val id = table.selectedRow.takeIf { it >= 0 }?.let { rows.getOrNull(it)?.id } ?: return render(emptyList())
        val client = controller.client ?: return
        val params = target ?: return
        scope.launch {
            val lines = runCatching {
                LinksView.detail(client.call("links.entry", JsonObject(params + ("id" to JsonPrimitive(id)))).result.jsonObject["entry"]!!.jsonObject)
            }.getOrElse { listOf("Not read" to (it.message ?: it.javaClass.simpleName)) }
            withContext(Dispatchers.Main) { render(lines) }
        }
    }

    private fun render(lines: List<Pair<String, String>>) {
        detail.removeAll()
        val form = FormBuilder.createFormBuilder()
        lines.forEach { (label, value) -> form.addLabeledComponent(label, JBLabel(value).apply { setCopyable(true) }) }
        detail.add(form.panel.apply { border = JBUI.Borders.empty(8, 12) }, BorderLayout.NORTH)
        detail.revalidate()
        detail.repaint()
    }

    private fun openLink() {
        val params = target ?: return
        val client = controller.client ?: return
        val uri = Messages.showInputDialog(project, "URI to open on the device (ACTION_VIEW):", "Open Link", null, "", null)?.trim()?.takeIf { it.isNotEmpty() } ?: return
        status.text = "Opening $uri…"
        scope.launch {
            val text = runCatching {
                LinksView.opened(client.call("links.open", JsonObject(mapOf("device" to params["device"]!!, "uri" to JsonPrimitive(uri), "package" to params["app"]!!))).result.jsonObject)
            }.getOrElse { it.message ?: it.javaClass.simpleName }
            withContext(Dispatchers.Main) { status.text = text }
        }
    }

    private fun verification() {
        val params = target ?: return
        val client = controller.client ?: return
        scope.launch {
            val lines = runCatching { LinksView.verification(client.call("links.verification", params).result.jsonObject) }
                .getOrElse { listOf(it.message ?: it.javaClass.simpleName) }
            withContext(Dispatchers.Main) { Messages.showInfoMessage(project, lines.joinToString("\n"), "App Links of ${(params["app"] as JsonPrimitive).content}") }
        }
    }

    private fun clear() {
        val params = target ?: return
        val client = controller.client ?: return
        val answer = Messages.showYesNoDialog(project, "Links and network traffic are one history: clearing deletes both.", "Clear the App's History", null)
        if (answer != Messages.YES) return
        scope.launch {
            val failure = runCatching { client.call("network.clear", params) }.exceptionOrNull()?.message
            withContext(Dispatchers.Main) { status.text = failure ?: "Cleared." }
        }
    }
}
