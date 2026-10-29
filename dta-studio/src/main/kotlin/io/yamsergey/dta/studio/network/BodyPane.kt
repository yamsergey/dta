package io.yamsergey.dta.studio.network

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.client.DaemonClient
import io.yamsergey.dta.present.network.BodyContent
import io.yamsergey.dta.present.network.BodyMode
import io.yamsergey.dta.present.network.BodyRef
import io.yamsergey.dta.present.network.BodyView
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
import java.io.File
import java.nio.file.Files
import javax.swing.ButtonGroup
import javax.swing.JPanel
import javax.swing.JToggleButton

/**
 * One body (FR-009, patterns.md body viewer): the header stays fixed above the content; modes
 * formatted / raw / hex; bodies over the inline limit and binary ones are fetched to a file
 * and open in a read-only editor tab; save to file.
 */
class BodyPane(
    private val project: Project,
    private val client: DaemonClient,
    private val target: JsonObject,
    private val entryId: String,
    private val ref: BodyRef,
    private val scope: CoroutineScope,
) : JPanel(BorderLayout()) {
    private val header = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
    private val modes = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
    private val area = JBTextArea().apply {
        isEditable = false
        font = com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().globalScheme.getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN)
        lineWrap = false
    }
    private var view: BodyView? = null
    private var bytes: ByteArray? = null

    init {
        add(JPanel(BorderLayout()).apply { add(header, BorderLayout.CENTER); add(modes, BorderLayout.EAST) }, BorderLayout.NORTH)
        add(JBScrollPane(area).apply { preferredSize = JBUI.size(400, 160) }, BorderLayout.CENTER)
        header.add(JBLabel("Loading ${ref.part} body…"))
        load()
    }

    private fun load() = scope.launch {
        val stored = (ref.info["storedBytes"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0
        val binary = (ref.info["binary"] as? JsonPrimitive)?.content == "true"
        val result = runCatching {
            val params = JsonObject(target + mapOf("id" to JsonPrimitive(entryId), "part" to JsonPrimitive(ref.part)))
            if (BodyView.needsFile(stored)) {
                client.call("network.body", JsonObject(params + ("output" to JsonPrimitive(tempFile(extension()).path)))).result.jsonObject
            } else {
                val inline = client.call("network.body", params).result.jsonObject
                // A binary body that did not decode comes as a file: the hex view reads its bytes.
                if (binary && inline["text"] == null) client.call("network.body", JsonObject(params + ("output" to JsonPrimitive(tempFile().path)))).result.jsonObject else inline
            }
        }
        val loaded = result.getOrNull()?.let(BodyView::of)
        bytes = (loaded?.content as? BodyContent.File)?.takeIf { !loaded.tooLarge }?.let { runCatching { Files.readAllBytes(File(it.path).toPath()) }.getOrNull() }
        withContext(Dispatchers.Main) { show(loaded, result.exceptionOrNull()?.message) }
    }

    private fun show(loaded: BodyView?, error: String?) {
        view = loaded
        header.removeAll()
        modes.removeAll()
        if (loaded == null) {
            header.add(JBLabel("Body not available: ${error ?: "no reason given"}"))
        } else {
            header.add(JBLabel(loaded.header.line))
            loaded.header.statements.forEach { header.add(StatementMark(it)) }
            if (loaded.tooLarge) {
                val file = loaded.content as BodyContent.File
                header.add(ActionLink("Open in editor") { openInEditor(file.path) })
                area.text = "This body is ${io.yamsergey.dta.present.Units.bytes(file.bytes)}: too large to show here. It was written to ${file.path} (sha256 ${file.sha256})."
            } else {
                val group = ButtonGroup()
                loaded.modes.forEach { mode ->
                    val button = JToggleButton(mode.name.lowercase()).apply { addActionListener { render(mode) } }
                    group.add(button)
                    modes.add(button)
                    if (mode == loaded.defaultMode) button.isSelected = true
                }
                modes.add(ActionLink("Save…") { save() }.apply { border = JBUI.Borders.emptyLeft(8) })
                render(loaded.defaultMode)
            }
        }
        revalidate()
        repaint()
    }

    private fun render(mode: BodyMode) {
        val v = view ?: return
        area.lineWrap = mode == BodyMode.FORMATTED
        area.text = v.render(mode, bytes)
        area.caretPosition = 0
    }

    private fun openInEditor(path: String) {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(path)) ?: return
        file.isWritable = false
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    private fun save() {
        val v = view ?: return
        val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(FileSaverDescriptor("Save Body", "Save the body as DTA shows it"), project)
        val target = wrapper.save("${entryId.replace(':', '-')}-${ref.part}") ?: return
        val out = target.file
        val file = (v.content as? BodyContent.File)?.path
        if (file != null) File(file).copyTo(out, overwrite = true) else out.writeText(v.text ?: "")
    }

    private fun tempFile(extension: String = ".bin"): File = File.createTempFile("dta-body-", extension).apply { deleteOnExit() }

    /** The editor picks its highlighting from the extension. */
    private fun extension(): String {
        val type = (ref.info["contentType"] as? JsonPrimitive)?.content.orEmpty()
        return when {
            "json" in type || "protobuf" in type -> ".json"
            "xml" in type -> ".xml"
            "html" in type -> ".html"
            "text" in type -> ".txt"
            else -> ".bin"
        }
    }
}
