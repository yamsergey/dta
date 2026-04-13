package io.yamsergey.dta.plugin.ui

import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.mcp.McpHttpServer
import io.yamsergey.dta.mcp.McpInstaller
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Plugin tab for the dta-mcp HTTP server. Lets the user enable/disable
 * the server, pick a port, install into known agents, and see the config
 * snippet for manual setup.
 */
class McpPanel : JPanel(BorderLayout()) {

    private val log = Logger.getInstance(McpPanel::class.java)

    private val props = PropertiesComponent.getInstance()
    private val enableCheckbox = JCheckBox("Enable MCP HTTP server")
    private val portField = JBTextField(8)
    private val statusLabel = JBLabel("○ Stopped").apply {
        foreground = JBColor.GRAY
    }
    private val snippetArea = JBTextArea(6, 40).apply {
        isEditable = false
        font = java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12)
    }

    @Volatile
    private var server: McpHttpServer? = null

    private val isMacOs = System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("mac")

    init {
        border = JBUI.Borders.empty(8)
        layout = BorderLayout()

        portField.text = props.getInt(PROP_PORT, DEFAULT_PORT).toString()
        enableCheckbox.isSelected = props.getBoolean(PROP_ENABLED, false)

        add(buildTopSection(), BorderLayout.NORTH)
        add(buildCenterSection(), BorderLayout.CENTER)

        enableCheckbox.addActionListener {
            props.setValue(PROP_ENABLED, enableCheckbox.isSelected)
            if (enableCheckbox.isSelected) startServer() else stopServer()
            updateSnippet()
        }

        portField.addActionListener {
            val newPort = portField.text.toIntOrNull() ?: DEFAULT_PORT
            props.setValue(PROP_PORT, newPort, DEFAULT_PORT)
            if (server != null) {
                stopServer()
                startServer()
            }
            updateSnippet()
        }

        updateSnippet()

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

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2
        top.add(enableCheckbox, gbc)

        gbc.gridwidth = 1
        gbc.gridx = 0; gbc.gridy = 1
        top.add(JBLabel("Port:"), gbc)
        gbc.gridx = 1
        top.add(portField, gbc)

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2
        top.add(statusLabel, gbc)

        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2
        top.add(buildInstallButtons(), gbc)

        return top
    }

    private fun buildInstallButtons(): JPanel {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4))
        row.add(JBLabel("Install to:"))

        // AS Gemini — only on macOS (MCP server support confirmed for macOS AS only so far)
        if (isMacOs) {
            row.add(JButton("Android Studio Gemini").apply {
                addActionListener { installTo(McpInstaller.Target.AS_GEMINI, asGeminiHint()) }
            })
        }

        row.add(JButton("Cursor").apply {
            addActionListener { openCursorInstallLink() }
        })

        row.add(JButton("Claude Code").apply {
            addActionListener { installTo(McpInstaller.Target.CLAUDE_CODE, null) }
        })

        if (isMacOs) {
            row.add(JButton("Uninstall AS Gemini").apply {
                addActionListener { uninstallFrom(McpInstaller.Target.AS_GEMINI, asGeminiHint()) }
            })
        }

        return row
    }

    private fun buildCenterSection(): JPanel {
        val center = JPanel(BorderLayout())
        val box = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Configuration snippet")
        }
        box.add(JBScrollPane(snippetArea), BorderLayout.CENTER)

        val copyBtn = JButton("Copy to clipboard").apply {
            addActionListener {
                val snippet = snippetArea.text
                java.awt.Toolkit.getDefaultToolkit().systemClipboard
                    .setContents(StringSelection(snippet), null)
                Messages.showInfoMessage(this@McpPanel, "Snippet copied to clipboard.", "DTA")
            }
        }
        val btnPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4))
        btnPanel.add(copyBtn)
        box.add(btnPanel, BorderLayout.SOUTH)

        center.add(box, BorderLayout.CENTER)
        return center
    }

    // ========================================================================
    // Snippet
    // ========================================================================

    private fun updateSnippet() {
        val port = portField.text.toIntOrNull() ?: DEFAULT_PORT
        snippetArea.text = McpInstaller.snippet(port)
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

    // ========================================================================
    // Install actions
    // ========================================================================

    private fun asGeminiHint(): Path =
        Path.of(PathManager.getConfigPath()).resolve("mcp.json")

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

    private fun openCursorInstallLink() {
        val link = McpInstaller.cursorInstallLink(currentPort())
        if (link != null) {
            try {
                // cursor:// is a custom protocol — use Desktop.browse with URI
                // (BrowserUtil only handles http/https reliably)
                java.awt.Desktop.getDesktop().browse(java.net.URI(link))
            } catch (_: Exception) {
                // Fallback: copy the link so the user can open it manually
                java.awt.Toolkit.getDefaultToolkit().systemClipboard
                    .setContents(java.awt.datatransfer.StringSelection(link), null)
                Messages.showInfoMessage(this,
                    "Could not open Cursor automatically.\nThe install link has been copied to your clipboard — paste it in a browser.",
                    "DTA")
            }
        } else {
            Messages.showErrorDialog(this, "Failed to generate Cursor install link.", "DTA")
        }
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
