package io.yamsergey.dta.studio.ui

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.present.network.DataTrees
import io.yamsergey.dta.studio.network.BodyEditor
import io.yamsergey.dta.studio.network.BodyTree
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import javax.swing.JPanel

/**
 * One JSON value, readable (feature 031): the Network body viewer's parts — the IDE's JSON editor
 * (highlighting, folding, find) and the body tree — under a one-line header, with Copy,
 * Expand/Collapse and, for an editable value, Edit in place with Apply / Cancel.
 */
class JsonValueView(project: Project) : JPanel(BorderLayout()) {
    enum class Mode(val label: String) { FORMATTED("Formatted"), TREE("Tree") }

    private val title = JBLabel(" ").apply { setCopyable(true) }
    private val modes = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
    private val actions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0))
    private val content = JPanel(BorderLayout())
    private val editor = BodyEditor(project)
    private val error = JBLabel().apply { foreground = JBColor.RED }

    private var text = ""
    private var structured = false
    private var apply: ((String) -> Unit)? = null
    private var mode = Mode.FORMATTED
    private var editing = false
    private var tree: BodyTree? = null

    init {
        add(JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(4, 8, 4, 12))
            add(title, BorderLayout.CENTER)
            add(modes, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(JPanel(BorderLayout()).apply {
            add(actions.apply { border = JBUI.Borders.empty(2, 0) }, BorderLayout.NORTH)
            add(content, BorderLayout.CENTER)
        }, BorderLayout.CENTER)
    }

    /**
     * Shows [pretty] (indented JSON) titled [header]; [structured] adds the Tree mode;
     * [onApply], when given, makes it editable: it hears the edited text after Apply.
     */
    fun show(header: String, pretty: String, structured: Boolean, onApply: ((String) -> Unit)? = null) {
        title.text = header
        text = pretty
        this.structured = structured
        apply = onApply
        editing = false
        tree = null
        if (!structured) mode = Mode.FORMATTED
        rebuild()
    }

    /** Edit in place (the Formatted editor becomes writable); nothing when the value is read-only. */
    fun beginEdit() {
        if (apply == null) return
        editing = true
        mode = Mode.FORMATTED
        rebuild()
    }

    /** Says why an Apply was refused, keeping the edit open. */
    fun refuse(message: String) {
        error.text = message
        error.isVisible = true
    }

    val isEditing: Boolean get() = editing

    /** Applies [edited] text (what Apply does with the editor's text). */
    fun submit(edited: String) { apply?.invoke(edited) }

    /** The header line shown. */
    val header: String get() = title.text

    private fun rebuild() {
        modes.removeAll()
        if (structured && !editing) {
            lateinit var switch: com.intellij.ui.dsl.builder.SegmentedButton<Mode>
            modes.add(com.intellij.ui.dsl.builder.panel {
                // Any selection, not only the mouse's: an accessibility action selects too.
                row { switch = segmentedButton(Mode.entries) { text = it.label }.whenItemSelected { if (it != mode) { mode = it; render() } } }
            }.apply { isOpaque = false })
            switch.selectedItem = mode
        }
        render()
        modes.revalidate()
        modes.repaint()
    }

    private fun render() {
        actions.removeAll()
        error.isVisible = false
        if (editing) {
            editor.show(text, "json", editable = true)
            actions.add(ActionLink("Apply") { submit(editor.text()) })
            actions.add(ActionLink("Cancel") { editing = false; rebuild() })
            actions.add(JBLabel("Editing: the value is checked as JSON before it is set.").apply { foreground = JBColor.GRAY })
            actions.add(error)
            setContent(editor)
        } else when (mode) {
            Mode.FORMATTED -> {
                editor.show(text, "json")
                actions.add(ActionLink("Copy") { CopyPasteManager.getInstance().setContents(StringSelection(text)) })
                if (structured) {
                    actions.add(ActionLink("Expand all") { editor.foldAll(false) })
                    actions.add(ActionLink("Collapse all") { editor.foldAll(true) })
                }
                if (apply != null) actions.add(ActionLink("Edit") { beginEdit() })
                setContent(editor)
            }
            Mode.TREE -> {
                val t = tree ?: DataTrees.json(text)?.let { root -> BodyTree(root) {} }?.also { tree = it }
                if (t == null) { mode = Mode.FORMATTED; return render() }
                actions.add(ActionLink("Expand all") { t.expandAll(true) })
                actions.add(ActionLink("Collapse all") { t.expandAll(false) })
                if (apply != null) actions.add(ActionLink("Edit") { beginEdit() })
                actions.add(JBLabel("Right-click to copy a value or its path").apply { foreground = JBColor.GRAY })
                setContent(t)
            }
        }
        actions.revalidate()
        actions.repaint()
    }

    private fun setContent(c: java.awt.Component) {
        if (content.componentCount != 1 || content.getComponent(0) !== c) {
            content.removeAll()
            content.add(c, BorderLayout.CENTER)
        }
        content.revalidate()
        content.repaint()
    }
}
