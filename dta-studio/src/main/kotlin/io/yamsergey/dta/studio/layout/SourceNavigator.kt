package io.yamsergey.dta.studio.layout

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbModeBlockedFunctionality
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.AppExecutorUtil
import io.yamsergey.dta.present.layout.Source
import java.awt.Component

/**
 * Opens a node's `source(file, line)` in the editor (FR-012). The capture names only the file,
 * so the project's files of that name are candidates; those under the app's package come first.
 */
class SourceNavigator(private val project: Project) {

    /** The project files [source] may mean, best first; empty when it is not in this project. */
    fun candidates(source: Source, pkg: String?): List<VirtualFile> = ApplicationManager.getApplication().runReadAction(
        Computable {
            val files = FilenameIndex.getVirtualFilesByName(source.file, GlobalSearchScope.projectScope(project)).toList()
            rank(files, pkg) { it.path }
        },
    )

    /**
     * Opens [source]; several candidates → a chooser under [anchor]; none in the project → [onMissing].
     * The lookup runs off the UI thread. While the IDE indexes, the click is refused with the IDE's
     * own indexing notice (as Go to File does), not reported as missing; nothing is queued.
     */
    fun open(source: Source, pkg: String?, anchor: Component, onMissing: () -> Unit) {
        val dumb = DumbService.getInstance(project)
        if (dumb.isDumb) {
            dumb.showDumbModeNotificationForFunctionality("Opening the node's source is not available while indexing", DumbModeBlockedFunctionality.GotoClass)
            return
        }
        ReadAction.nonBlocking<List<VirtualFile>> { candidates(source, pkg) }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { files ->
                when {
                    files.isEmpty() -> onMissing()
                    files.size == 1 || preferred(files.map { it.path }, pkg) -> navigate(files.first(), source.line)
                    else -> JBPopupFactory.getInstance().createPopupChooserBuilder(files)
                        .setTitle("${source.file}:${source.line}")
                        .setRenderer(io.yamsergey.dta.studio.ui.textRenderer<VirtualFile> { f -> project.basePath?.let { f.path.removePrefix("$it/") } ?: f.path })
                        .setItemChosenCallback { navigate(it, source.line) }
                        .createPopup().showUnderneathOf(anchor)
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun navigate(file: VirtualFile, line: Int) = OpenFileDescriptor(project, file, (line - 1).coerceAtLeast(0), 0).navigate(true)

    companion object {
        /** Paths containing the package's directories first, then by path; stable for equal ranks. */
        fun <T> rank(items: List<T>, pkg: String?, path: (T) -> String): List<T> {
            val dir = pkg?.replace('.', '/')?.let { "/$it/" }
            return items.sortedWith(compareBy<T> { if (dir != null && dir in path(it)) 0 else 1 }.thenBy { path(it) })
        }

        /** Exactly one candidate lies under the package: that one is meant, no chooser needed. */
        fun preferred(paths: List<String>, pkg: String?): Boolean {
            val dir = pkg?.replace('.', '/')?.let { "/$it/" } ?: return false
            return paths.count { dir in it } == 1
        }
    }
}
