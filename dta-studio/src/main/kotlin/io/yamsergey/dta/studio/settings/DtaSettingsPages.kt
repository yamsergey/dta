package io.yamsergey.dta.studio.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import io.yamsergey.dta.studio.DtaProjectService
import io.yamsergey.dta.studio.agents.AgentServerService
import io.yamsergey.dta.studio.agents.AgentServerSettings
import io.yamsergey.dta.studio.inject.InjectionConfigurable
import io.yamsergey.dta.studio.toolwindow.AgentsPanel
import io.yamsergey.dta.studio.toolwindow.SchemasPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import javax.swing.JComponent

/** Feature 056: the gear in the DTA tool window header, as IntelliJ's own tool windows have it. */
class OpenDtaSettingsAction : DumbAwareAction("Settings…", "Open DTA's settings", AllIcons.General.Settings) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, InjectionConfigurable::class.java)
    }
}

/** Settings › Tools › DTA › Agents: what the Agents tab had (feature 056); changes apply at once, as there. */
class AgentsConfigurable : Configurable {
    private var scope: CoroutineScope? = null

    override fun getDisplayName() = "Agents"

    override fun createComponent(): JComponent {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
        return AgentsPanel(AgentServerService.get().controller, ApplicationManager.getApplication().getService(AgentServerSettings::class.java), s)
    }

    override fun isModified() = false
    override fun apply() = Unit
    override fun disposeUIResources() {
        scope?.cancel()
        scope = null
    }
}

/** Settings › Tools › DTA › Protobuf: what the Schemas tab had, for this project's app (feature 056). */
class ProtobufConfigurable(private val project: Project) : Configurable {
    private var scope: CoroutineScope? = null

    override fun getDisplayName() = "Protobuf"

    override fun createComponent(): JComponent {
        val service = DtaProjectService.get(project)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
        return SchemasPanel(project, service.context, s, service.schemaSync).also { it.show(service.context.state.value) }
    }

    override fun isModified() = false
    override fun apply() = Unit
    override fun disposeUIResources() {
        scope?.cancel()
        scope = null
    }
}

/** Opens the Protobuf page (the "not decoded" link of a body, feature 056). */
fun openProtobufSettings(project: Project) =
    ShowSettingsUtil.getInstance().showSettingsDialog(project, ProtobufConfigurable::class.java)
