package io.yamsergey.dta.studio.layout

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project

/** Feature 057: the Layout view's choices for this project (the workspace file: per person). */
@Service(Service.Level.PROJECT)
@State(name = "DtaLayout", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class LayoutSettings : SimplePersistentStateComponent<LayoutSettings.Options>(Options()), LayoutPrefs {
    class Options : BaseState() {
        var hideContainers by property(true)
        /** Manual by default: each capture runs on the app's main thread. */
        var autoRefresh by property(false)
    }

    override var hideContainers: Boolean
        get() = state.hideContainers
        set(value) { state.hideContainers = value }

    override var autoRefresh: Boolean
        get() = state.autoRefresh
        set(value) { state.autoRefresh = value }

    companion object {
        fun get(project: Project): LayoutSettings = project.getService(LayoutSettings::class.java)
    }
}
