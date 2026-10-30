package io.yamsergey.dta.studio.toolwindow

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.present.context.Connection
import io.yamsergey.dta.present.context.ContextState
import io.yamsergey.dta.studio.ContextController
import io.yamsergey.dta.studio.ui.StatementMark
import java.awt.FlowLayout
import java.awt.event.ItemEvent
import javax.swing.JPanel
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/**
 * The context line (contracts/tool-window.md): device · app · connection, each picker followed
 * by its `auto` mark when DTA chose it. The daemon's version is on the Daemon tab; the run id is
 * for agents, a person reads the time of each request.
 */
class ContextBar(private val controller: ContextController) : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(2))) {
    private val devices = ComboBox<String>()
    private val apps = ComboBox<String>()
    private val deviceMark = mark()
    private val appMark = mark()
    private val notes = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { isOpaque = false }
    private val connection = JBLabel()
    private var updating = false

    init {
        add(JBLabel("Device")); add(devices); add(deviceMark)
        add(JBLabel("App")); add(apps); add(appMark)
        add(connection); add(notes)
        devices.renderer = SimpleListCellRenderer.create("No device") { it }
        apps.renderer = SimpleListCellRenderer.create("No app") { it }
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
        connection.text = when (state.connection) {
            Connection.UNKNOWN -> ""
            Connection.NO_DTA -> "no DTA"
            else -> state.connection.name.lowercase()
        }
        deviceMark.removeAll(); state.deviceAuto?.let { deviceMark.add(StatementMark(it)) }
        appMark.removeAll(); state.appAuto?.let { appMark.add(StatementMark(it)) }
        notes.removeAll()
        (state.selection - listOfNotNull(state.deviceAuto, state.appAuto).toSet()).forEach { notes.add(StatementMark(it)) }
        revalidate()
        repaint()
    }

    private fun mark() = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false }

    private fun onOpen(action: () -> Unit) = object : PopupMenuListener {
        override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) = action()
        override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) = Unit
        override fun popupMenuCanceled(e: PopupMenuEvent?) = Unit
    }
}
