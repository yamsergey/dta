package io.yamsergey.dta.studio.schemas

import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The project's `.proto` files, its own and those packaged in its dependencies (feature 013,
 * research R3), keyed as their imports name them ([ProtoKeys]).
 */
object ProjectProtos {
    /**
     * Finds the files in a non-blocking read action (it yields to the IDE's writes and waits for
     * indexing), then reads them outside it: a large project never freezes the IDE here.
     */
    suspend fun collect(project: Project): Map<String, String> {
        val files = smartReadAction(project) { FilenameIndex.getAllFilesByExt(project, "proto", GlobalSearchScope.allScope(project)).sortedBy { it.path } }
        return withContext(Dispatchers.IO) {
            files.associate { file -> ProtoKeys.of(file.path, project.basePath) to String(file.contentsToByteArray(), file.charset) }
        }
    }
}
