package io.yamsergey.dta.studio.network

import com.intellij.codeInsight.folding.CodeFoldingManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightVirtualFile
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * A body as text in a read-only IDE editor (feature 022, research R1): the file type's
 * highlighting and folding, find, brace matching, the user's colour scheme. The editor exists
 * while the pane is shown and is released when it is removed. Feature 031: [show] with
 * `editable` makes it writable (a ViewModel value edited in place); [text] reads it back.
 */
class BodyEditor(private val project: Project) : JPanel(BorderLayout()) {
    private var editor: Editor? = null
    private var text: String = ""
    private var extension: String = "txt"
    private var editable: Boolean = false

    private var marks: List<String> = emptyList()

    /**
     * Shows [text] highlighted as a file with [extension] (`json`, `xml`, `html`, `graphql`, `txt`);
     * the search words in [marks] are marked and the first is scrolled into view (feature 040).
     */
    fun show(text: String, extension: String, editable: Boolean = false, marks: List<String> = emptyList()) {
        this.text = text
        this.extension = extension
        this.editable = editable
        this.marks = marks
        if (isDisplayable) rebuild()
    }

    private fun mark(e: EditorEx) {
        val spans = io.yamsergey.dta.present.network.Highlights.spans(e.document.text, marks)
        if (spans.isEmpty()) return
        val attributes = com.intellij.openapi.editor.markup.TextAttributes().apply { backgroundColor = io.yamsergey.dta.studio.ui.SEARCH_MARK }
        spans.forEach {
            e.markupModel.addRangeHighlighter(it.first, it.last + 1, com.intellij.openapi.editor.markup.HighlighterLayer.SELECTION - 1, attributes, com.intellij.openapi.editor.markup.HighlighterTargetArea.EXACT_RANGE)
        }
        val first = spans.first().first
        // A folded region would hide it: unfold where the first one is, then bring it into view.
        e.foldingModel.runBatchFoldingOperation { e.foldingModel.allFoldRegions.filter { it.startOffset <= first && it.endOffset > first }.forEach { it.isExpanded = true } }
        e.caretModel.moveToOffset(first)
        e.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
    }

    /** The text as it is now in the editor (edited, when [show] made it editable). */
    fun text(): String = editor?.document?.text ?: text

    /** Folds every region but the outermost (the top-level members stay visible), or unfolds all. */
    fun foldAll(fold: Boolean) {
        val e = editor ?: return
        val regions = e.foldingModel.allFoldRegions
        e.foldingModel.runBatchFoldingOperation {
            regions.forEach { r -> r.isExpanded = !fold || regions.none { o -> o !== r && o.startOffset <= r.startOffset && o.endOffset >= r.endOffset } }
        }
    }

    override fun addNotify() {
        super.addNotify()
        rebuild()
    }

    override fun removeNotify() {
        release()
        super.removeNotify()
    }

    private fun rebuild() {
        release()
        val type = fileType(extension)
        val file = LightVirtualFile("body.${if (type == PlainTextFileType.INSTANCE) "txt" else extension}", type, text)
        // The file stays writable (a read-only one gets the IDE's "Reader Mode" badge); the viewer keeps it read-only.
        val document = FileDocumentManager.getInstance().getDocument(file) ?: EditorFactory.getInstance().createDocument(text)
        val created = (if (editable) EditorFactory.getInstance().createEditor(document, project, EditorKind.UNTYPED)
            else EditorFactory.getInstance().createViewer(document, project, EditorKind.PREVIEW)) as EditorEx
        created.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, file)
        created.settings.apply {
            isLineNumbersShown = true
            isFoldingOutlineShown = true
            isLineMarkerAreaShown = false
            isIndentGuidesShown = true
            additionalLinesCount = 0
            additionalColumnsCount = 0
            isCaretRowShown = editable
            isUseSoftWraps = false
        }
        created.setCaretVisible(editable)
        editor = created
        add(created.component, BorderLayout.CENTER)
        // Fold regions come from the file's language (objects, arrays, elements), computed off the UI thread.
        com.intellij.openapi.application.ReadAction
            .nonBlocking<Runnable?> { CodeFoldingManager.getInstance(project).updateFoldRegionsAsync(created, true) }
            .expireWhen { created.isDisposed }
            .finishOnUiThread(com.intellij.openapi.application.ModalityState.any()) { apply -> if (!created.isDisposed) { apply?.run(); mark(created) } }
            .submit(com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService())
        revalidate()
        repaint()
    }

    private fun release() {
        editor?.let { e ->
            remove(e.component)
            if (!e.isDisposed) EditorFactory.getInstance().releaseEditor(e)
        }
        editor = null
    }

    private fun fileType(extension: String): FileType =
        FileTypeManager.getInstance().getFileTypeByExtension(extension).takeIf { it != UnknownFileType.INSTANCE } ?: PlainTextFileType.INSTANCE
}
