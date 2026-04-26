package io.yamsergey.dta.plugin

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.daemon.sidekick.SidekickConnectionManager.Device
import io.yamsergey.dta.daemon.sidekick.SidekickConnectionManager.SidekickSocket
import io.yamsergey.dta.plugin.DtaService.DtaServiceListener
import io.yamsergey.dta.plugin.ui.DaemonPanel
import io.yamsergey.dta.plugin.ui.LayoutPanel
import io.yamsergey.dta.plugin.ui.RuntimePanel
import io.yamsergey.dta.plugin.ui.McpPanel
import io.yamsergey.dta.plugin.ui.NetworkPanel
import io.yamsergey.dta.plugin.ui.WebSocketPanel
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JPanel

/**
 * Main panel for the DTA Inspector tool window.
 * Contains a toolbar (device/app combo boxes, refresh, status) and tabbed content.
 */
class DtaToolWindowPanel : JPanel(BorderLayout()), Disposable, DtaServiceListener {

    private val service = DtaService.getInstance()

    // Toolbar components
    private val deviceCombo = ComboBox<String>().apply { isEditable = false }
    private val appCombo = ComboBox<String>().apply { isEditable = false }
    private val statusLabel = JBLabel("Disconnected").apply {
        foreground = JBColor.GRAY
        border = JBUI.Borders.emptyLeft(8)
    }

    // Track actual objects behind combo display strings
    private var deviceList: List<Device> = emptyList()
    private var appList: List<SidekickSocket> = emptyList()
    private var updatingCombos = false

    // Tabs
    private val tabbedPane = JBTabbedPane()
    private val daemonPanel = DaemonPanel()
    private val layoutPanel = LayoutPanel()
    private val networkPanel = NetworkPanel()
    private val webSocketPanel = WebSocketPanel()
    private val runtimePanel = RuntimePanel()
    private val mcpPanel = McpPanel()

    init {
        // Build toolbar
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        toolbar.add(JBLabel("Device:"))
        toolbar.add(deviceCombo)
        toolbar.add(JBLabel("App:"))
        toolbar.add(appCombo)

        // Refresh action toolbar
        val refreshAction = object : AnAction("Refresh", "Refresh devices and apps", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                service.refreshDevices()
            }
        }
        val injectAction = object : com.intellij.openapi.actionSystem.ToggleAction(
            "Auto-inject Sidekick", "Inject dta-sidekick into debug builds (no project file changes)", AllIcons.Actions.Download
        ) {
            override fun isSelected(e: AnActionEvent): Boolean = SidekickInjector.enabled
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                SidekickInjector.toggle()
            }
        }
        val actionGroup = DefaultActionGroup().apply {
            add(refreshAction)
            add(injectAction)
        }
        val actionToolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLBAR, actionGroup, true)
        actionToolbar.targetComponent = this
        toolbar.add(actionToolbar.component)
        toolbar.add(statusLabel)

        add(toolbar, BorderLayout.NORTH)

        // Build tabs — data tabs first, Daemon last (treats it as a
        // settings/troubleshooting surface rather than a primary view).
        tabbedPane.addTab("Layout", layoutPanel)
        tabbedPane.addTab("Network", networkPanel)
        tabbedPane.addTab("WebSocket", webSocketPanel)
        tabbedPane.addTab("Runtime", runtimePanel)
        tabbedPane.addTab("MCP", mcpPanel)
        tabbedPane.addTab("Daemon", daemonPanel)
        add(tabbedPane, BorderLayout.CENTER)

        // Combo listeners
        deviceCombo.addActionListener {
            if (updatingCombos) return@addActionListener
            val idx = deviceCombo.selectedIndex
            if (idx in deviceList.indices) {
                service.selectDevice(deviceList[idx])
            }
        }
        appCombo.addActionListener {
            if (updatingCombos) return@addActionListener
            val idx = appCombo.selectedIndex
            if (idx in appList.indices) {
                service.selectApp(appList[idx])
            }
        }

        // Register as listener and kick off initial discovery
        service.addListener(this)
        service.refreshDevices()
    }

    // ========================================================================
    // DtaServiceListener callbacks (called on EDT)
    // ========================================================================

    override fun onDevicesChanged(devices: List<Device>) {
        deviceList = devices
        updatingCombos = true
        try {
            val model = DefaultComboBoxModel<String>()
            for (d in devices) {
                val label = buildString {
                    append(d.serial())
                    val m = d.model()
                    if (!m.isNullOrEmpty()) append(" ($m)")
                }
                model.addElement(label)
            }
            deviceCombo.model = model
            // Restore selection
            val sel = service.selectedDevice
            if (sel != null) {
                val idx = devices.indexOfFirst { it.serial() == sel.serial() }
                if (idx >= 0) deviceCombo.selectedIndex = idx
            }
        } finally {
            updatingCombos = false
        }
    }

    override fun onAppsChanged(apps: List<SidekickSocket>) {
        appList = apps
        updatingCombos = true
        try {
            val model = DefaultComboBoxModel<String>()
            for (a in apps) {
                model.addElement(a.packageName())
            }
            appCombo.model = model
            val sel = service.selectedApp
            if (sel != null) {
                val idx = apps.indexOfFirst { it.packageName() == sel.packageName() }
                if (idx >= 0) appCombo.selectedIndex = idx
            }
        } finally {
            updatingCombos = false
        }
    }

    override fun onConnectionChanged(status: String, connected: Boolean) {
        statusLabel.text = status
        statusLabel.foreground = when {
            status.startsWith("Connected") -> JBColor(0x2E7D32, 0x81C784)
            status.contains("failed", ignoreCase = true) -> JBColor.RED
            else -> JBColor.GRAY
        }
    }

    override fun dispose() {
        service.removeListener(this)
        daemonPanel.dispose()
        mcpPanel.dispose()
    }
}
