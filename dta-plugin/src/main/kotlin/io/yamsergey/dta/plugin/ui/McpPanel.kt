package io.yamsergey.dta.plugin.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.mcp.McpHttpServer
import io.yamsergey.dta.mcp.McpInstaller
import io.yamsergey.dta.mcp.McpServer
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Plugin tab that exposes the dta-mcp HTTP server in-process and lets the
 * user install its config into known agents (Android Studio Gemini in
 * particular). Enable + port are persisted via [PropertiesComponent], so the
 * server auto-starts on plugin load if the user previously enabled it.
 *
 * <p>Architecture: this tab does NOT reimplement any MCP behavior. It hosts
 * an [McpHttpServer] from the dta-mcp module and calls into [McpInstaller]
 * for config writes — exactly what {@code dta-cli mcp serve --http} and
 * {@code dta-cli mcp install} do, just driven from the IDE UI instead of
 * the command line.</p>
 */
class McpPanel : JPanel(BorderLayout()) {

    private val log = Logger.getInstance(McpPanel::class.java)

    // Persisted settings
    private val props = PropertiesComponent.getInstance()
    private val enableCheckbox = JCheckBox("Enable MCP HTTP server")
    private val portField = JBTextField(8)
    private val statusLabel = JBLabel("○ Stopped").apply {
        foreground = JBColor.GRAY
    }
    private val toolListModel = DefaultListModel<String>()
    private val toolList = JList(toolListModel).apply {
        visibleRowCount = 12
    }

    @Volatile
    private var server: McpHttpServer? = null

    init {
        border = JBUI.Borders.empty(8)
        layout = BorderLayout()

        portField.text = props.getInt(PROP_PORT, DEFAULT_PORT).toString()
        enableCheckbox.isSelected = props.getBoolean(PROP_ENABLED, false)

        val top = buildTopSection()
        add(top, BorderLayout.NORTH)
        add(buildCenterSection(), BorderLayout.CENTER)

        // Wire toggle
        enableCheckbox.addActionListener {
            props.setValue(PROP_ENABLED, enableCheckbox.isSelected)
            if (enableCheckbox.isSelected) startServer() else stopServer()
        }

        // Wire port edit (re-bind on change if running)
        portField.addActionListener {
            val newPort = portField.text.toIntOrNull() ?: DEFAULT_PORT
            props.setValue(PROP_PORT, newPort, DEFAULT_PORT)
            if (server != null) {
                stopServer()
                startServer()
            }
        }

        // Initial tool list (best-effort — works without daemon since
        // buildToolList() is lazy on the daemon connection)
        refreshToolList()

        // Auto-start if user previously enabled
        if (enableCheckbox.isSelected) {
            ApplicationManager.getApplication().executeOnPooledThread { startServer() }
        }
    }

    // ========================================================================
    // UI building
    // ========================================================================

    private fun buildTopSection(): JPanel {
        val top = JPanel(GridBagLayout())
        top.border = BorderFactory.createTitledBorder("MCP HTTP Server")
        val gbc = GridBagConstraints().apply {
            insets = Insets(2, 4, 2, 4)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.NONE
        }

        // Row 0: enable checkbox spans 2 cols
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2
        top.add(enableCheckbox, gbc)

        // Row 1: port label + input
        gbc.gridwidth = 1
        gbc.gridx = 0; gbc.gridy = 1
        top.add(JBLabel("Port:"), gbc)
        gbc.gridx = 1
        top.add(portField, gbc)

        // Row 2: status
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2
        top.add(statusLabel, gbc)

        // Row 3: install buttons
        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2
        top.add(buildInstallButtons(), gbc)
        return top
    }

    private fun buildInstallButtons(): JPanel {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4))
        row.add(JBLabel("Install to:"))
        row.add(JButton("Android Studio Gemini").apply {
            addActionListener { installTo(McpInstaller.Target.AS_GEMINI, asGeminiHint()) }
        })
        row.add(JButton("Claude Code").apply {
            addActionListener { installTo(McpInstaller.Target.CLAUDE_CODE, null) }
        })
        row.add(JButton("Copy snippet").apply {
            addActionListener { copySnippetToClipboard() }
        })
        row.add(JButton("Uninstall AS Gemini").apply {
            addActionListener { uninstallFrom(McpInstaller.Target.AS_GEMINI, asGeminiHint()) }
        })
        return row
    }

    private fun buildCenterSection(): JPanel {
        val center = JPanel(BorderLayout())
        val box = Box.createVerticalBox().apply {
            border = BorderFactory.createTitledBorder("Available tools")
            add(JBScrollPane(toolList).apply {
                preferredSize = Dimension(360, 240)
            })
        }
        center.add(box, BorderLayout.CENTER)
        return center
    }

    // ========================================================================
    // Server lifecycle
    // ========================================================================

    @Synchronized
    private fun startServer() {
        if (server != null) {
            updateStatus("● Running at http://localhost:${server!!.boundPort}/mcp", JBColor(0x2E7D32, 0x81C784))
            return
        }
        val port = portField.text.toIntOrNull() ?: DEFAULT_PORT
        try {
            val s = McpHttpServer()
            val actualPort = s.start(port)
            server = s
            // If user passed 0, sync the displayed port back to what was bound
            if (port == 0) {
                SwingUtilities.invokeLater { portField.text = actualPort.toString() }
            }
            updateStatus("● Running at http://localhost:$actualPort/mcp", JBColor(0x2E7D32, 0x81C784))
            log.info("Started MCP HTTP server on port $actualPort")
        } catch (e: Exception) {
            log.warn("Failed to start MCP HTTP server on port $port: ${e.message}", e)
            SwingUtilities.invokeLater {
                enableCheckbox.isSelected = false
                props.setValue(PROP_ENABLED, false)
            }
            updateStatus("● Error: ${e.message}", JBColor.RED)
        }
    }

    @Synchronized
    private fun stopServer() {
        server?.let {
            try {
                it.stop()
                log.info("Stopped MCP HTTP server")
            } catch (e: Exception) {
                log.warn("Error stopping MCP HTTP server: ${e.message}")
            }
        }
        server = null
        updateStatus("○ Stopped", JBColor.GRAY)
    }

    private fun updateStatus(text: String, color: Color) {
        SwingUtilities.invokeLater {
            statusLabel.text = text
            statusLabel.foreground = color
        }
    }

    private fun refreshToolList() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val names = McpServer.getToolNames()
                SwingUtilities.invokeLater {
                    toolListModel.clear()
                    names.forEach { toolListModel.addElement(it) }
                }
            } catch (e: Exception) {
                log.debug("Failed to load tool list: ${e.message}")
            }
        }
    }

    // ========================================================================
    // Install actions
    // ========================================================================

    /**
     * Returns the AS Gemini mcp.json path resolved via the IDE's own
     * [PathManager], so we don't have to scan filesystems for AS installs.
     * This is the killer advantage of running inside AS.
     */
    private fun asGeminiHint(): Path =
        Path.of(PathManager.getConfigPath()).resolve("options").resolve("mcp.json")

    private fun installTo(target: McpInstaller.Target, hint: Path?) {
        val port = currentPort()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = McpInstaller.install(target, port, hint)
            SwingUtilities.invokeLater { showResult(result, target.name + " install") }
        }
    }

    private fun uninstallFrom(target: McpInstaller.Target, hint: Path?) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = McpInstaller.uninstall(target, hint)
            SwingUtilities.invokeLater { showResult(result, target.name + " uninstall") }
        }
    }

    private fun copySnippetToClipboard() {
        val snippet = McpInstaller.snippet(currentPort())
        java.awt.Toolkit.getDefaultToolkit().systemClipboard
            .setContents(StringSelection(snippet), null)
        Messages.showInfoMessage(this, "MCP config snippet copied to clipboard.", "DTA")
    }

    private fun currentPort(): Int = portField.text.toIntOrNull() ?: DEFAULT_PORT

    private fun showResult(result: McpInstaller.Result, title: String) {
        if (result.success()) {
            Messages.showInfoMessage(this, result.message(), title)
        } else {
            Messages.showErrorDialog(this, result.message(), title)
        }
    }

    fun dispose() {
        stopServer()
    }

    companion object {
        private const val PROP_ENABLED = "dta.mcp.enabled"
        private const val PROP_PORT = "dta.mcp.port"
        private const val DEFAULT_PORT = 12321
    }
}
