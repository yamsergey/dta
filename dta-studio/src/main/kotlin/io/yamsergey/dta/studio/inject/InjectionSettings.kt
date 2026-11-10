package io.yamsergey.dta.studio.inject

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether IDE builds get DTA (FR-021): on by default, kept across restarts, for every project. */
@Service(Service.Level.APP)
@State(name = "DtaInjection", storages = [Storage("dta.xml")])
class InjectionSettings : SimplePersistentStateComponent<InjectionSettings.Options>(Options()) {
    class Options : BaseState() {
        var enabled by property(true)
        /** Compose's tooling data added where a Compose app lacks it (feature 010 US6). */
        var tooling by property(true)
        /** Feature 056: the Timeline feature; off by default. */
        var timeline by property(false)
    }

    private val changes = MutableStateFlow(true)

    /** The switch as it changes, for the views that state it. */
    val enabledFlow: StateFlow<Boolean> = changes.asStateFlow()

    var enabled: Boolean
        get() = state.enabled
        set(value) {
            state.enabled = value
            changes.value = value
        }

    var tooling: Boolean
        get() = state.tooling
        set(value) {
            state.tooling = value
        }

    private val timelineChanges = MutableStateFlow(false)

    /** Feature 056: the Timeline switch as it changes (the tab and the connected app follow it). */
    val timelineFlow: StateFlow<Boolean> = timelineChanges.asStateFlow()

    var timeline: Boolean
        get() = state.timeline
        set(value) {
            state.timeline = value
            timelineChanges.value = value
        }

    override fun loadState(state: Options) {
        super.loadState(state)
        changes.value = state.enabled
        timelineChanges.value = state.timeline
    }

    companion object {
        fun get(): InjectionSettings = ApplicationManager.getApplication().getService(InjectionSettings::class.java)
    }
}
