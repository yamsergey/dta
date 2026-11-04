package io.yamsergey.dta.studio.network

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.client.DaemonClient
import io.yamsergey.dta.present.network.FormatRuleForm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.awt.Dimension
import java.awt.Font
import javax.swing.JComponent
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Feature 039: "Set format…" of a WebSocket connection — how the app's own protocol is built
 * (a pattern with named groups, field paths into JSON), tried live on this connection's latest
 * messages with `network.format.test`. OK adds the rule (replacing [replaces] when editing).
 */
class FormatRuleDialog(
    project: Project,
    private val client: DaemonClient,
    private val target: JsonObject,
    private val connection: String,
    initial: FormatRuleForm,
    private val replaces: String?,
    private val scope: CoroutineScope,
) : DialogWrapper(project) {
    private val name = JBTextField(initial.name)
    private val url = JBTextField(initial.urlPattern)
    private val direction = ComboBox(arrayOf("any", "out", "in")).apply { selectedItem = initial.direction }
    private val pattern = JBTextField(initial.pattern).apply { font = mono(); emptyText.text = "^(?<id>\\d+)\\|(?<kind>\\w+)\\|(?<payload>.*)$" }
    private val kind = JBTextField(initial.kind).apply { emptyText.text = "h.op" }
    private val id = JBTextField(initial.idPath).apply { emptyText.text = "h.rid" }
    private val topic = JBTextField(initial.topic).apply { emptyText.text = "h.ch[0]" }
    private val payload = JBTextField(initial.payload).apply { emptyText.text = "b" }
    private val payloadFormat = ComboBox(arrayOf("json", "text")).apply { selectedItem = initial.payloadFormat }
    private val preview = JBTextArea(8, 70).apply { isEditable = false; font = mono(); lineWrap = false }
    private val problem = JBLabel().apply { foreground = JBColor.RED }
    private val debounce = Timer(400) { test() }.apply { isRepeats = false }
    private var testing: Job? = null
    private var refusal: String? = null

    init {
        title = if (replaces == null) "Set Message Format" else "Edit Message Format ($replaces)"
        setOKButtonText(if (replaces == null) "Add Rule" else "Replace Rule")
        val listener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = changed()
            override fun removeUpdate(e: DocumentEvent) = changed()
            override fun changedUpdate(e: DocumentEvent) = changed()
        }
        listOf(name, url, pattern, kind, id, topic, payload).forEach { it.document.addDocumentListener(listener) }
        direction.addActionListener { changed() }
        payloadFormat.addActionListener { changed() }
        init()
        changed()
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Name:", name)
        .addLabeledComponent("Socket URL:", url)
        .addTooltip("A glob: * is any run of characters.")
        .addLabeledComponent("Direction:", direction)
        .addSeparator()
        .addLabeledComponent("Pattern:", pattern)
        .addTooltip("A regex over the whole message; name groups kind, id, topic, payload.")
        .addLabeledComponent("Kind path:", kind)
        .addLabeledComponent("Id path:", id)
        .addLabeledComponent("Topic path:", topic)
        .addLabeledComponent("Payload path:", payload)
        .addTooltip("Field paths into the JSON payload (or the whole message without a pattern): names joined by dots, [n] for an item.")
        .addLabeledComponent("Payload is:", payloadFormat)
        .addSeparator()
        .addComponent(problem)
        .addComponentFillVertically(JBScrollPane(preview).apply { preferredSize = Dimension(JBUI.scale(640), JBUI.scale(200)) }, 4)
        .panel

    /** What the user typed. */
    fun form() = FormatRuleForm(
        name.text, url.text, direction.selectedItem as String, pattern.text, kind.text, id.text, topic.text, payload.text, payloadFormat.selectedItem as String,
    )

    override fun doValidate(): ValidationInfo? = when {
        url.text.isBlank() -> ValidationInfo("Give the socket's URL (a glob).", url)
        form().empty -> ValidationInfo("Give a pattern or at least one field path.", pattern)
        refusal != null -> ValidationInfo(refusal!!)
        else -> null
    }

    override fun doOKAction() {
        val params = JsonObject(target + form().params())
        isOKActionEnabled = false
        scope.launch {
            val added = runCatching { client.call("network.format.add", params) }
            // Editing replaces: the new rule is in place before the old one goes.
            if (added.isSuccess && replaces != null) runCatching { client.call("network.format.remove", JsonObject(target + mapOf("id" to JsonPrimitive(replaces)))) }
            withContext(Dispatchers.Main) {
                isOKActionEnabled = true
                added.onSuccess { super.doOKAction() }.onFailure { problem.text = it.message }
            }
        }
    }

    override fun dispose() {
        debounce.stop()
        testing?.cancel()
        super.dispose()
    }

    private fun changed() {
        refusal = null
        debounce.restart()
    }

    private fun test() {
        val f = form()
        if (f.empty || f.urlPattern.isBlank()) {
            problem.text = ""
            preview.text = "Type a pattern or a field path to see how it reads this connection's messages."
            return
        }
        testing?.cancel()
        testing = scope.launch {
            val r = runCatching { client.call("network.format.test", JsonObject(target + f.params() + mapOf("connection" to JsonPrimitive(connection)))).result.jsonObject }
            withContext(Dispatchers.Main) {
                r.onSuccess {
                    refusal = null
                    problem.text = ""
                    preview.text = FormatRuleForm.preview(it)
                    preview.caretPosition = 0
                }.onFailure {
                    refusal = it.message
                    problem.text = it.message
                }
            }
        }
    }

    private fun mono() = Font(com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().globalScheme.editorFontName, Font.PLAIN, JBUI.scaleFontSize(12f))
}
