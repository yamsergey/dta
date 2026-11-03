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
 * while the pane is shown and is released when it is removed.
 */
class BodyEditor(private val project: Project) : JPanel(BorderLayout()) {
    private var editor: Editor? = null
    private var text: String = ""
    private var extension: String = "txt"

    /** Shows [text] highlighted as a file with [extension] (`json`, `xml`, `html`, `graphql`, `txt`). */
    fun show(text: String, extension: String) {
        this.text = text
        this.extension = extension
        if (isDisplayable) rebuild()
    }

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
        val file = LightVirtualFile("body.${if (type == PlainTextFileType.INSTANCE) "txt" else extension}", type, text).apply { isWritable = false }
        val document = FileDocumentManager.getInstance().getDocument(file) ?: EditorFactory.getInstance().createDocument(text)
        val created = EditorFactory.getInstance().createViewer(document, project, EditorKind.PREVIEW) as EditorEx
        created.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, file)
        created.settings.apply {
            isLineNumbersShown = true
            isFoldingOutlineShown = true
            isLineMarkerAreaShown = false
            isIndentGuidesShown = true
            additionalLinesCount = 0
            additionalColumnsCount = 0
            isCaretRowShown = false
            isUseSoftWraps = false
        }
        created.setCaretVisible(false)
        editor = created
        add(created.component, BorderLayout.CENTER)
        // Fold regions come from the file's language (objects, arrays, elements); a new document is already committed.
        runCatching { CodeFoldingManager.getInstance(project).updateFoldRegions(created) }
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
