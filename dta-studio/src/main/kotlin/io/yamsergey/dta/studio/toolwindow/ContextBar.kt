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
    private var chipText = ""
    private var chipReason: String? = null
    /** What the bar's owner does when its contents' widths changed. */
    var onChanged: () -> Unit = {}

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
        chipText = line?.text ?: ""
        chipReason = line?.reason
        connection.foreground = line?.let { if (it.token == "ink-muted") com.intellij.ui.JBColor.GRAY else DtaColors.of(it.token) }
        // Whole until fit() says the room is short (D-108); the owner's onChanged fits again.
        connection.text = chipText
        connection.toolTipText = ConnectionChipFit.of(chipText, chipReason, 0, 0, 0, 0, Int.MAX_VALUE).tooltip
        onChanged()
        revalidate()
        repaint()
    }

    /**
     * Feature 043: the bar lives in the title bar now, which does not wrap: in a narrow window the
     * two combos give up width so the connection state stays visible.
     */
    fun fit(available: Int) {
        val gap = JBUI.scale(8)
        // D-108: the chip's text whole, or only its dot when the combos would have to give up their floor.
        connection.text = chipText
        val base = components.filter { it !== devices && it !== apps && it !== connection }.sumOf { it.preferredSize.width + gap } + 2 * gap
        val fm = connection.getFontMetrics(connection.font)
        val shown = ConnectionChipFit.of(chipText, chipReason, fm.stringWidth(chipText) + gap, fm.stringWidth(chipText.substringBefore(' ')) + gap, base, JBUI.scale(160), available)
        connection.text = shown.text
        connection.toolTipText = shown.tooltip
        val others = base + (if (shown.text.isEmpty()) 0 else connection.preferredSize.width + gap)
        devices.preferredSize = null
        apps.preferredSize = null
        val room = (available - others).coerceAtLeast(JBUI.scale(160))
        val d = devices.preferredSize
        val a = apps.preferredSize
        if (d.width + a.width > room) {
            val half = room / 2
            val dw = if (d.width <= half) d.width else if (a.width <= half) room - a.width else half
            devices.preferredSize = java.awt.Dimension(dw, d.height)
            apps.preferredSize = java.awt.Dimension((room - dw).coerceAtMost(a.width), a.height)
        }
        revalidate()
    }

    private fun onOpen(action: () -> Unit) = object : PopupMenuListener {
        override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) = action()
        override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) = Unit
        override fun popupMenuCanceled(e: PopupMenuEvent?) = Unit
    }
}
