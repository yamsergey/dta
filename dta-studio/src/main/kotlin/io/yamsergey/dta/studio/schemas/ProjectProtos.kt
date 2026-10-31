package io.yamsergey.dta.studio.schemas

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * The project's `.proto` files, its own and those packaged in its dependencies (feature 013,
 * research R3), keyed as their imports name them ([ProtoKeys]).
 */
object ProjectProtos {
    fun collect(project: Project): Map<String, String> = ApplicationManager.getApplication().runReadAction(Computable {
        val files = FilenameIndex.getAllFilesByExt(project, "proto", GlobalSearchScope.allScope(project))
        files.sortedBy { it.path }.associate { file ->
            ProtoKeys.of(file.path, project.basePath) to String(file.contentsToByteArray(), file.charset)
        }
    })
}
