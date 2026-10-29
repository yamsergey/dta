package io.yamsergey.dta.studio.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import javax.swing.SwingConstants

/** The DTA tool window (contracts/tool-window.md). */
class DtaToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val placeholder = JBLabel("DTA", SwingConstants.CENTER)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(placeholder, "", false))
    }
}
