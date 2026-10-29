package io.yamsergey.dta.studio.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.content.ContentFactory
import io.yamsergey.dta.studio.DtaApplicationService
import io.yamsergey.dta.studio.DtaProjectService
import io.yamsergey.dta.studio.ui.BannerPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.SwingConstants

/** The DTA tool window (contracts/tool-window.md): context line, whole-view banner, one tab per view. */
class DtaToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = DtaProjectService.get(project)
        val controller = service.context
        val bar = ContextBar(controller)
        val banner = BannerPanel().apply { onAction { DtaApplicationService.get().session.retry() } }
        val daemonPanel = DaemonPanel(controller, service.scope)
        val tabs = JBTabbedPane().apply {
            listOf("Network", "Layout", "Script", "Schemas").forEach { addTab(it, placeholder(it)) }
            addTab("Daemon", daemonPanel)
            addTab("Agents", placeholder("Agents"))
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

        service.scope.launch {
            controller.state.collect { state ->
                withContext(Dispatchers.Main) {
                    bar.show(state)
                    banner.show(state.banner)
                    daemonPanel.refresh(state)
                }
            }
        }
    }

    private fun placeholder(name: String) = JBLabel("$name — coming in this release", SwingConstants.CENTER)
}
