package io.yamsergey.dta.studio

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope

/** One project's context over the IDE's daemon connection (FR-001, FR-002). */
@Service(Service.Level.PROJECT)
class DtaProjectService(@Suppress("unused") private val project: Project, scope: CoroutineScope) {
    val context = ContextController(DtaApplicationService.get().session.state, scope)
    val network = io.yamsergey.dta.studio.network.NetworkController(context, scope)
    val scope: CoroutineScope = scope

    init {
        DtaApplicationService.get().ensureStarted()
    }

    companion object {
        fun get(project: Project): DtaProjectService = project.getService(DtaProjectService::class.java)
    }
}
