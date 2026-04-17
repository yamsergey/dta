package io.yamsergey.dta.plugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Factory that creates the DTA Inspector tool window content.
 * Registers a [DtaToolWindowPanel] as the main content and wires up disposal.
 */
class DtaToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.setIcon(IconLoader.getIcon("/icons/dta-13.png", DtaToolWindowFactory::class.java))

        val panel = DtaToolWindowPanel()
        val content = ContentFactory.getInstance().createContent(panel, "Inspector", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)

        Disposer.register(toolWindow.disposable, panel)
    }
}
