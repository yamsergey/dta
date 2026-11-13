package io.yamsergey.dta.studio.network

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.util.textCompletion.TextCompletionUtil

/**
 * Feature 063: in the query line, typing the colon of a key (`host:`) opens its values, as the old list did.
 * The IDE opens completion by itself only on letters and digits.
 */
class QueryTypedHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (charTyped != ':' || TextCompletionUtil.getProvider(file) !is QueryCompletion) return Result.CONTINUE
        AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        return Result.STOP
    }
}
