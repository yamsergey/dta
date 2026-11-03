package io.yamsergey.dta.studio.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.content.ContentFactory
import com.intellij.openapi.application.ApplicationManager
import io.yamsergey.dta.studio.DtaApplicationService
import io.yamsergey.dta.studio.agents.AgentServerService
import io.yamsergey.dta.studio.agents.AgentServerSettings
import io.yamsergey.dta.studio.DtaProjectService
import io.yamsergey.dta.studio.ui.BannerPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JPanel

/** The DTA tool window (contracts/tool-window.md): context line, whole-view banner, one tab per view. */
class DtaToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = DtaProjectService.get(project)
        val controller = service.context
        val bar = ContextBar(controller)
        val banner = BannerPanel().apply { onAction { DtaApplicationService.get().session.retry() } }
        lateinit var tabs: JBTabbedPane
        val daemonPanel = DaemonPanel(controller, service.scope) { scope ->
            service.network.setArchive(scope)
            tabs.selectedIndex = 0
        }
        val layoutPanel = LayoutPanel(project, service.layout, service.scope)
        val scriptPanel = ScriptPanel(project, controller, service.scope)
        val schemasPanel = SchemasPanel(project, controller, service.scope, service.schemaSync)
        val linksPanel = LinksPanel(project, controller, service.scope)
        val timelinePanel = TimelinePanel(
            service.timeline, service.scope,
            openNetwork = { id ->
                service.network.reveal(id)
                tabs.selectedIndex = 0
            },
            openLayout = { tabs.selectedComponent = layoutPanel },
        )
        // Feature 027: the app's stores, for the device and app the context line shows.
        val dataPanel = io.yamsergey.dta.studio.data.DataPanel(project, service.scope) { op, params ->
            val state = controller.state.value
            val client = controller.client ?: error("Not connected to the DTA service.")
            val device = state.device ?: error("Choose a device.")
            val app = state.app ?: error("Choose an app with DTA inside.")
            val result = client.call(op, kotlinx.serialization.json.JsonObject(params + mapOf("device" to kotlinx.serialization.json.JsonPrimitive(device), "app" to kotlinx.serialization.json.JsonPrimitive(app)))).result
            // A list result (network.schema.list) comes wrapped as {"items": [...]}.
            result as? kotlinx.serialization.json.JsonObject ?: kotlinx.serialization.json.JsonObject(mapOf("items" to result))
        }
        var dataFor: Pair<String?, String?>? = null
        tabs = JBTabbedPane().apply {
            addTab("Network", NetworkPanel(project, service.network, controller, service.scope))
            addTab("Timeline", timelinePanel)
            addTab("Data", dataPanel)
            addTab("Links", linksPanel)
            addTab("Layout", layoutPanel)
            addTab("Script", scriptPanel)
            addTab("Schemas", schemasPanel)
            addTab("Daemon", daemonPanel)
            val agents = AgentServerService.get()
            addTab("Agents", AgentsPanel(agents.controller, ApplicationManager.getApplication().getService(AgentServerSettings::class.java), service.scope))
        }
        // A layout is a snapshot: captured only while its tab is the one shown.
        tabs.addChangeListener {
            service.layout.setActive(tabs.selectedComponent === layoutPanel)
            service.timeline.setActive(tabs.selectedComponent === timelinePanel)
            if (tabs.selectedComponent === dataPanel) controller.state.value.let { st -> if (dataFor != st.device to st.app) { dataFor = st.device to st.app; dataPanel.load() } }
            // Script and Schemas are refreshed when shown and after their actions; Links while shown.
            if (tabs.selectedComponent === linksPanel) linksPanel.show(controller.state.value) else linksPanel.stopRefreshing()
            when (tabs.selectedComponent) {
                scriptPanel -> scriptPanel.show(controller.state.value)
                schemasPanel -> schemasPanel.show(controller.state.value)
            }
        }
        val top = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(bar)
            add(banner)
        }
        val root = JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(tabs, BorderLayout.CENTER)
        }
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(root, "", false))
        // The injection switch, also in Settings › Tools › DTA (FR-021).
        toolWindow.setTitleActions(listOf(io.yamsergey.dta.studio.inject.ToggleInjectionAction()))

        service.scope.launch {
            controller.state.collect { state ->
                withContext(Dispatchers.Main) {
                    bar.show(state)
                    banner.show(state.banner)
                    daemonPanel.refresh(state)
                    if (tabs.selectedComponent === dataPanel && dataFor != state.device to state.app) { dataFor = state.device to state.app; dataPanel.load() }
                    when (tabs.selectedComponent) {
                        scriptPanel -> scriptPanel.show(state)
                        schemasPanel -> schemasPanel.show(state)
                        linksPanel -> linksPanel.show(state)
                    }
                }
            }
        }
    }
}
