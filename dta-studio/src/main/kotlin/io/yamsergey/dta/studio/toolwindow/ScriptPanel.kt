package io.yamsergey.dta.studio.toolwindow

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.present.context.ContextState
import io.yamsergey.dta.present.script.ScriptView
import io.yamsergey.dta.studio.ContextController
import io.yamsergey.dta.studio.ui.DtaColors
import io.yamsergey.dta.studio.ui.StatementMark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * The Script view (FR-013): the app's network script — status, counters and what keeps it from
 * applying; install from a file or the current editor; clear; and its log. Refreshed when shown
 * and after every action.
 */
class ScriptPanel(
    private val project: Project,
    private val controller: ContextController,
    private val scope: CoroutineScope,
) : JPanel(BorderLayout()) {
    private val status = JPanel(BorderLayout())
    private val error = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
    private val log = JBTextArea().apply { isEditable = false; font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, font.size) }
    private val clear = JButton("Clear")
    private var since = 0L
    private var target: JsonObject? = null

    init {
        val fromFile = JButton("Install from File…").apply { addActionListener { chooseFile() } }
        val fromEditor = JButton("Install Current Editor").apply { addActionListener { currentEditor() } }
        clear.addActionListener { run("network.script.clear") { refresh() } }
        val refresh = JButton("Refresh").apply { addActionListener { refresh() } }
        val actions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { listOf(fromFile, fromEditor, clear, refresh).forEach(::add) }
        val top = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 12)
            add(actions, BorderLayout.NORTH)
            add(status, BorderLayout.CENTER)
            add(error, BorderLayout.SOUTH)
        }
        add(top, BorderLayout.NORTH)
        add(JBScrollPane(log).apply { border = JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0) }, BorderLayout.CENTER)
    }

    /** The context changed (device, app, connection): show that app's script. */
    fun show(state: ContextState) {
        val next = if (state.device != null && state.app != null) {
            JsonObject(mapOf("device" to JsonPrimitive(state.device), "app" to JsonPrimitive(state.app)))
        } else {
            null
        }
        if (next != target) {
            target = next
            since = 0
            log.text = ""
        }
        refresh()
    }

    fun refresh() {
        val client = controller.client
        val params = target
        if (client == null || params == null) return render(null, "Choose an app with DTA inside.")
        scope.launch {
            val view = runCatching { ScriptView.from(client.call("network.script.status", params).result.jsonObject) }
            val page = runCatching { ScriptView.log(client.call("network.script.logs", JsonObject(params + ("since" to JsonPrimitive(since)))).result.jsonObject) }.getOrNull()
            withContext(Dispatchers.Main) {
                render(view.getOrNull(), view.exceptionOrNull()?.message)
                page?.let { p ->
                    p.lost?.let { log.append("${it.kind.glyph} ${it.detail}\n") }
                    p.lines.forEach { log.append(it.text + "\n") }
                    since = p.next
                }
            }
        }
    }

    private fun render(view: ScriptView?, problem: String?) {
        status.removeAll()
        val form = FormBuilder.createFormBuilder()
        if (view == null) {
            form.addComponent(JBLabel(problem ?: ""))
        } else {
            form.addComponent(JBLabel(view.headline))
            view.handlers?.let { form.addLabeledComponent("Handlers", JBLabel(it)) }
            view.counters?.let { form.addLabeledComponent("This run", JBLabel(it).apply { foreground = JBColor.GRAY }) }
            view.statements.forEach { s ->
                form.addComponent(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { add(StatementMark(s)); add(JBLabel(s.detail)) })
            }
        }
        clear.isEnabled = view?.canClear == true
        status.add(form.panel, BorderLayout.CENTER)
        status.revalidate()
        status.repaint()
    }

    private fun chooseFile() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("js").withTitle("Install Network Script")
        FileChooser.chooseFile(descriptor, project, null)?.let(::install)
    }

    private fun currentEditor() {
        val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        if (file == null) return showError("No file is open in the editor.", null, null)
        install(file)
    }

    private fun install(file: VirtualFile) {
        val document = FileDocumentManager.getInstance().getDocument(file)
        val source = document?.text ?: String(file.contentsToByteArray(), file.charset)
        val params = target ?: return showError("Choose an app with DTA inside.", null, null)
        showError(null, null, null)
        run("network.script.set", JsonObject(params + ("source" to JsonPrimitive(source))), onError = { message ->
            val e = ScriptView.installError(message)
            showError("${file.name}: ${e.message}", file, e.line)
        }) { refresh() }
    }

    private fun showError(message: String?, file: VirtualFile?, line: Int?) {
        error.removeAll()
        if (message != null) {
            error.add(JBLabel(message).apply { foreground = DtaColors.of("status-server"); setCopyable(true) })
            if (file != null && line != null) {
                error.add(ActionLink("Go to line $line") { OpenFileDescriptor(project, file, line - 1, 0).navigate(true) })
            }
        }
        error.revalidate()
        error.repaint()
    }

    private fun run(operation: String, params: JsonObject? = target, onError: (String) -> Unit = { showError(it, null, null) }, then: () -> Unit) {
        val client = controller.client ?: return
        val p = params ?: return
        scope.launch {
            val result = runCatching { client.call(operation, p) }
            withContext(Dispatchers.Main) {
                result.onSuccess { then() }.onFailure { onError(it.message ?: it.javaClass.simpleName) }
            }
        }
    }
}
