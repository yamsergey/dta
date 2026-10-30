package io.yamsergey.dta.studio.toolwindow

import com.intellij.openapi.ui.ComboBox
import io.yamsergey.dta.studio.ui.textRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.present.context.ContextState
import io.yamsergey.dta.studio.ContextController
import io.yamsergey.dta.studio.ui.DtaColors
import java.awt.FlowLayout
import java.awt.event.ItemEvent
import javax.swing.JPanel
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/**
 * The context line (contracts/tool-window.md): device · app · connection, with pickers for
 * choosing. What DTA chose is not marked: the pickers show it. The daemon's version is on the
 * Daemon tab; the run id is for agents, a person reads the time of each request.
 */
class ContextBar(private val controller: ContextController) : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(2))) {
    private val devices = ComboBox<String>()
    private val apps = ComboBox<String>()
    private val connection = JBLabel()
    private var updating = false

    init {
        add(JBLabel("Device")); add(devices)
        add(JBLabel("App")); add(apps)
        add(connection)
        devices.renderer = textRenderer<String>("No device") { it }
        apps.renderer = textRenderer<String>("No app") { it }
        devices.addPopupMenuListener(onOpen { controller.refreshDevices() })
        apps.addPopupMenuListener(onOpen { controller.refreshApps() })
        devices.addItemListener { e -> if (!updating && e.stateChange == ItemEvent.SELECTED) controller.chooseDevice((e.item as String).substringBefore(' ')) }
        apps.addItemListener { e -> if (!updating && e.stateChange == ItemEvent.SELECTED) controller.chooseApp(e.item as String) }
    }

    fun show(state: ContextState) {
        updating = true
        try {
            devices.removeAllItems()
            state.devices.orEmpty().filter { it.state == "online" }.forEach { devices.addItem(listOfNotNull(it.serial, it.model).joinToString(" ")) }
            devices.selectedIndex = state.devices.orEmpty().filter { it.state == "online" }.indexOfFirst { it.serial == state.device }
            apps.removeAllItems()
            state.app?.let { apps.addItem(it) }
            apps.selectedItem = state.app
        } finally {
            updating = false
        }
        val line = state.connectionLine
        connection.text = line?.text ?: ""
        connection.foreground = line?.let { if (it.token == "ink-muted") com.intellij.ui.JBColor.GRAY else DtaColors.of(it.token) }
        connection.toolTipText = line?.reason
        revalidate()
        repaint()
    }

    private fun onOpen(action: () -> Unit) = object : PopupMenuListener {
        override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) = action()
        override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) = Unit
        override fun popupMenuCanceled(e: PopupMenuEvent?) = Unit
    }
}
