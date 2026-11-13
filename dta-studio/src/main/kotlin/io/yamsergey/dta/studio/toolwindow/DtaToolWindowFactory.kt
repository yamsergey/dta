package io.yamsergey.dta.studio.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.content.ContentFactory
import com.intellij.openapi.application.ApplicationManager
import io.yamsergey.dta.studio.DtaApplicationService
import io.yamsergey.dta.studio.DtaProjectService
import io.yamsergey.dta.studio.ui.BannerPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
        val layoutPanel = LayoutPanel(project, service.layout, service.scope, service.selection)
        val scriptPanel = ScriptPanel(project, controller, service.scope)
        val linksPanel = LinksPanel(project, controller, service.scope)
        // Feature 048: set once the Runtime tab exists.
        var openViewModels: () -> Unit = {}
        val timelinePanel = TimelinePanel(
            service.timeline, service.scope,
            openNetwork = { id ->
                service.network.reveal(id)
                tabs.selectedIndex = 0
            },
            openLayout = { tabs.selectedComponent = layoutPanel },
            openViewModels = { openViewModels() },
        )
        // Feature 027: the app's stores, for the device and app the context line shows.
        val appCall: suspend (String, kotlinx.serialization.json.JsonObject) -> kotlinx.serialization.json.JsonObject = { op, params ->
            val state = controller.state.value
            val client = controller.client ?: error("Not connected to the DTA service.")
            val device = state.device ?: error("Choose a device.")
            val app = state.app ?: error("Choose an app with DTA inside.")
            val result = client.call(op, kotlinx.serialization.json.JsonObject(params + mapOf("device" to kotlinx.serialization.json.JsonPrimitive(device), "app" to kotlinx.serialization.json.JsonPrimitive(app)))).result
            // A list result (network.schema.list) comes wrapped as {"items": [...]}.
            result as? kotlinx.serialization.json.JsonObject ?: kotlinx.serialization.json.JsonObject(mapOf("items" to result))
        }
        val dataPanel = io.yamsergey.dta.studio.data.DataPanel(project, service.scope, call = appCall)
        // Feature 028: what the running process holds.
        val runtimePanel = io.yamsergey.dta.studio.data.RuntimePanel(project, service.scope, call = appCall)
        var runtimeFor: Pair<String?, String?>? = null
        openViewModels = {
            // The tab's own loading is skipped: the panel reads once, for this.
            controller.state.value.let { st -> runtimeFor = st.device to st.app }
            tabs.selectedComponent = runtimePanel
            runtimePanel.openViewModels()
        }
        var dataFor: Pair<String?, String?>? = null
        // Feature 056: Agents and Schemas live in Settings › Tools › DTA; Timeline is a feature switched there.
        tabs = JBTabbedPane().apply {
            addTab("Network", NetworkPanel(project, service.network, controller, service.scope))
            addTab("Data", dataPanel)
            addTab("Runtime", runtimePanel)
            addTab("Links", linksPanel)
            addTab("Layout", layoutPanel)
            addTab("Script", scriptPanel)
            addTab("Daemon", daemonPanel)
        }
        val settings = io.yamsergey.dta.studio.inject.InjectionSettings.get()
        fun showTimeline(on: Boolean) {
            val at = tabs.indexOfComponent(timelinePanel)
            if (on && at < 0) tabs.insertTab("Timeline", null, timelinePanel, null, 1)
            if (!on && at >= 0) tabs.removeTabAt(at)
        }
        showTimeline(settings.timeline)
        // A layout is a snapshot: captured only while its tab is the one shown.
        tabs.addChangeListener {
            service.layout.setActive(tabs.selectedComponent === layoutPanel)
            service.timeline.setActive(tabs.selectedComponent === timelinePanel)
            if (tabs.selectedComponent === runtimePanel) controller.state.value.let { st -> if (runtimeFor != st.device to st.app) { runtimeFor = st.device to st.app; runtimePanel.load() } }
            if (tabs.selectedComponent === dataPanel) controller.state.value.let { st -> if (dataFor != st.device to st.app) { dataFor = st.device to st.app; dataPanel.load() } }
            // Script is refreshed when shown and after its actions; Links while shown.
            if (tabs.selectedComponent === linksPanel) linksPanel.show(controller.state.value) else linksPanel.stopRefreshing()
            if (tabs.selectedComponent === scriptPanel) scriptPanel.show(controller.state.value)
        }
        val top = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(banner)
        }
        val root = JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(tabs, BorderLayout.CENTER)
        }
        // Feature 043: the title bar carries the mascot and the device and app instead of the name, one row less.
        // Before addContent: the platform reads this on each title update and adding content is one, so one always comes after it.
        toolWindow.component.putClientProperty(com.intellij.openapi.wm.impl.content.ToolWindowContentUi.HIDE_ID_LABEL, "true")
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(root, "", false))
        // The injection switch, also in Settings › Tools › DTA (FR-021).
        // Feature 056: the gear opens Settings › Tools › DTA.
        toolWindow.setTitleActions(listOf(io.yamsergey.dta.studio.settings.OpenDtaSettingsAction(), io.yamsergey.dta.studio.inject.ToggleInjectionAction()))
        (toolWindow as? com.intellij.openapi.wm.ex.ToolWindowEx)?.setTabActions(HeaderBar(bar))
        // Room left beside the mascot and the title actions (the checkbox, options, hide).
        val refit = { bar.fit(toolWindow.component.width - com.intellij.util.ui.JBUI.scale(TITLE_RESERVED)) }
        bar.onChanged = refit
        toolWindow.component.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent?) = refit()
        })

        // Feature 056: the tab follows the switch; the connected app is told, so it records (or not) from its next start.
        service.scope.launch {
            settings.timelineFlow.collect { on -> withContext(Dispatchers.Main) { showTimeline(on) } }
        }
        service.scope.launch {
            combine(controller.state, settings.timelineFlow) { st, on -> Triple(st.device, st.app.takeIf { st.connection == io.yamsergey.dta.present.context.Connection.CONNECTED }, on) }
                .distinctUntilChanged()
                .collect { (device, app, on) ->
                    val client = controller.client ?: return@collect
                    if (device == null || app == null) return@collect
                    runCatching {
                        client.call("timeline.enabled", kotlinx.serialization.json.buildJsonObject {
                            put("device", kotlinx.serialization.json.JsonPrimitive(device)); put("app", kotlinx.serialization.json.JsonPrimitive(app)); put("set", kotlinx.serialization.json.JsonPrimitive(if (on) "on" else "off"))
                        })
                    }.onFailure { com.intellij.openapi.diagnostic.logger<DtaToolWindowFactory>().info("timeline switch not sent to $app: ${it.message}") }
                }
        }
        // Feature 060: the browser switches, told to the connected app (it keeps them; DTA follows them).
        service.scope.launch {
            combine(controller.state, settings.browsersFlow) { st, b -> Triple(st.device, st.app.takeIf { st.connection == io.yamsergey.dta.present.context.Connection.CONNECTED }, b) }
                .distinctUntilChanged()
                .collect { (device, app, b) ->
                    val client = controller.client ?: return@collect
                    if (device == null || app == null) return@collect
                    listOf(if (b.webview) "webview-on" else "webview-off", if (b.customTabs) "tabs-on" else "tabs-off").forEach { set ->
                        runCatching {
                            client.call("web.capture", kotlinx.serialization.json.buildJsonObject {
                                put("device", kotlinx.serialization.json.JsonPrimitive(device)); put("app", kotlinx.serialization.json.JsonPrimitive(app)); put("set", kotlinx.serialization.json.JsonPrimitive(set))
                            })
                        }.onFailure { com.intellij.openapi.diagnostic.logger<DtaToolWindowFactory>().info("browser switch $set not sent to $app: ${it.message}") }
                    }
                }
        }
        service.scope.launch {
            controller.state.collect { state ->
                withContext(Dispatchers.Main) {
                    bar.show(state)
                    banner.show(state.banner)
                    daemonPanel.refresh(state)
                    if (tabs.selectedComponent === runtimePanel && runtimeFor != state.device to state.app) { runtimeFor = state.device to state.app; runtimePanel.load() }
                    if (tabs.selectedComponent === dataPanel && dataFor != state.device to state.app) { dataFor = state.device to state.app; dataPanel.load() }
                    when (tabs.selectedComponent) {
                        scriptPanel -> scriptPanel.show(state)
                        linksPanel -> linksPanel.show(state)
                    }
                }
            }
        }
    }
}

private const val TITLE_RESERVED = 140

/** The title bar's mascot and context line (feature 043). */
private class HeaderBar(private val bar: ContextBar) : com.intellij.openapi.actionSystem.AnAction(), com.intellij.openapi.actionSystem.ex.CustomComponentAction, DumbAware {
    override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) = Unit
    override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
    override fun createCustomComponent(presentation: com.intellij.openapi.actionSystem.Presentation, place: String): javax.swing.JComponent =
        JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, com.intellij.util.ui.JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(com.intellij.ui.components.JBLabel(com.intellij.openapi.util.IconLoader.getIcon("/icons/mascot-16.svg", HeaderBar::class.java.classLoader)))
            bar.isOpaque = false
            add(bar)
        }
}
