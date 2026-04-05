package io.yamsergey.dta.plugin

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Factory that creates the DTA Inspector tool window content.
 * Registers a [DtaToolWindowPanel] as the main content and wires up disposal.
 */
class DtaToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DtaToolWindowPanel()
        val content = ContentFactory.getInstance().createContent(panel, "Inspector", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)

        // Ensure panel is disposed when the tool window is disposed
        Disposer.register(toolWindow.disposable, panel)
    }
}
