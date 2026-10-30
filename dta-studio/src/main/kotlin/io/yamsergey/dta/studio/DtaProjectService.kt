package io.yamsergey.dta.studio

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope

/** One project's context over the IDE's daemon connection (FR-001, FR-002). */
@Service(Service.Level.PROJECT)
class DtaProjectService(private val project: Project, scope: CoroutineScope) : Disposable {
    val context = ContextController(DtaApplicationService.get().session.state, scope)
    val injection = io.yamsergey.dta.studio.inject.InjectionWatcher(project, context, scope, this)
    val network = io.yamsergey.dta.studio.network.NetworkController(context, scope)
    val layout = io.yamsergey.dta.studio.layout.LayoutController(context, scope)
    val scope: CoroutineScope = scope

    init {
        DtaApplicationService.get().ensureStarted()
        injection.refresh()
    }

    override fun dispose() = Unit

    companion object {
        fun get(project: Project): DtaProjectService = project.getService(DtaProjectService::class.java)
    }
}
