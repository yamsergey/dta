package io.yamsergey.dta.plugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import javax.swing.JLabel
import javax.swing.JPanel
import java.awt.BorderLayout

class DtaToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JPanel(BorderLayout())
        panel.add(JLabel("DTA Inspector - Coming Soon"), BorderLayout.CENTER)

        val content = ContentFactory.getInstance().createContent(panel, "Inspector", false)
        toolWindow.contentManager.addContent(content)
    }
}
