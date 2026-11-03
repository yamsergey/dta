package io.yamsergey.dta.studio.network

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
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
import javax.swing.JPanel

/**
 * One body (FR-009, patterns.md body viewer): the header stays fixed above the content; modes
 * formatted / tree / graph / query / image / raw / hex (feature 022: text in a read-only IDE
 * editor with the format's highlighting and folding); bodies over the inline limit and binary
 * ones are fetched to a file and open in a read-only editor tab; save to file.
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
    private val modes = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
    private val content = JPanel(BorderLayout())
    private val actions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0))
    private val editor = BodyEditor(project)
    private var tree: BodyTree? = null
    private var graph: BodyGraph? = null
    /** The node selected in the tree or the graph: the other one shows it too. */
    private var selectedPath: String? = null
    private var view: BodyView? = null
    private var bytes: ByteArray? = null

    init {
        // The header stays put above the one scroll of the content; it lines up with the tab's other pages.
        add(JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(com.intellij.ui.JBColor.border()), JBUI.Borders.empty(4, 6, 4, 12))
            add(header, BorderLayout.CENTER)
            add(modes, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(JPanel(BorderLayout()).apply { add(actions, BorderLayout.NORTH); add(content, BorderLayout.CENTER) }, BorderLayout.CENTER)
        header.add(JBLabel("Loading ${ref.part} body…"))
        load()
    }

    private fun load() = scope.launch {
        val stored = (ref.info["storedBytes"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0
        val binary = (ref.info["binary"] as? JsonPrimitive)?.content == "true"
        val result = runCatching {
            val params = JsonObject(target + mapOf("id" to JsonPrimitive(entryId), "part" to JsonPrimitive(ref.part)))
            BodyView.fetch(
                stored, binary,
                inline = { client.call("network.body", params).result.jsonObject },
                toFile = { client.call("network.body", JsonObject(params + ("output" to JsonPrimitive(tempFile(if (binary) ".bin" else extension()).path)))).result.jsonObject },
            )
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
                setContent(JBLabel("This body is ${io.yamsergey.dta.present.Units.bytes(file.bytes)}: too large to show here. It was written to ${file.path} (sha256 ${file.sha256}).").apply { border = JBUI.Borders.empty(8, 12) })
            } else {
                lateinit var switch: com.intellij.ui.dsl.builder.SegmentedButton<BodyMode>
                modes.add(com.intellij.ui.dsl.builder.panel {
                    row { switch = segmentedButton(loaded.modes) { text = it.label }.whenItemSelectedFromUi { render(it) } }
                }.apply { isOpaque = false })
                switch.selectedItem = loaded.defaultMode
                modes.add(ActionLink("Save…") { save() }.apply { border = JBUI.Borders.emptyLeft(8) })
                render(loaded.defaultMode)
            }
        }
        revalidate()
        repaint()
    }

    private fun render(mode: BodyMode) {
        val v = view ?: return
        actions.removeAll()
        when (mode) {
            BodyMode.TREE -> {
                val t = tree ?: BodyTree(v.tree() ?: return) { path -> selectedPath = path }.also { tree = it }
                selectedPath?.let(t::select)
                actions.add(ActionLink("Expand all") { t.expandAll(true) })
                actions.add(ActionLink("Collapse all") { t.expandAll(false) })
                actions.add(JBLabel("Right-click to copy a value or its path").apply { foreground = com.intellij.ui.JBColor.GRAY })
                setContent(t)
            }
            BodyMode.GRAPH -> {
                val g = graph ?: BodyGraph(v.tree() ?: return) { path -> selectedPath = path }.also { graph = it }
                selectedPath?.let(g::select)
                setContent(g)
            }
            BodyMode.IMAGE -> setContent(image(bytes))
            else -> {
                val extension = when (mode) {
                    BodyMode.QUERY -> "graphql"
                    BodyMode.HEX -> "txt"
                    else -> v.format.extension
                }
                editor.show(v.render(mode, bytes), extension)
                if (mode == BodyMode.FORMATTED || mode == BodyMode.QUERY) {
                    actions.add(ActionLink("Expand all") { editor.foldAll(false) })
                    actions.add(ActionLink("Collapse all") { editor.foldAll(true) })
                }
                setContent(editor)
            }
        }
        actions.isVisible = actions.componentCount > 0
    }

    private fun setContent(c: java.awt.Component) {
        if (content.componentCount == 1 && content.getComponent(0) === c) { content.revalidate(); content.repaint(); return }
        content.removeAll()
        content.add(c, BorderLayout.CENTER)
        content.revalidate()
        content.repaint()
    }

    /** The picture and its size; what could not be decoded says so. */
    private fun image(data: ByteArray?): java.awt.Component {
        val img = data?.let { runCatching { javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(it)) }.getOrNull() }
        if (img == null) return JBLabel("This image could not be decoded here (${(ref.info["contentType"] as? JsonPrimitive)?.content ?: "unknown type"}); see Hex or save it.").apply { border = JBUI.Borders.empty(8, 12) }
        val label = JBLabel(javax.swing.ImageIcon(img)).apply { verticalAlignment = javax.swing.SwingConstants.TOP; horizontalAlignment = javax.swing.SwingConstants.LEFT; border = JBUI.Borders.empty(8, 12) }
        actions.add(JBLabel("${img.width} × ${img.height} px").apply { foreground = com.intellij.ui.JBColor.GRAY })
        return JBScrollPane(label).apply { border = JBUI.Borders.empty() }
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
