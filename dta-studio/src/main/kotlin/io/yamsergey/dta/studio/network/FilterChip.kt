package io.yamsergey.dta.studio.network

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.yamsergey.dta.present.network.SavedChip
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel

/**
 * Feature 043: a saved filter drawn as the IDE draws its tags (the theme's Tag background,
 * rounded), key muted, value in text colour, with the IDE's close icon. A click edits it.
 */
class FilterChip(word: String, edit: () -> Unit, remove: () -> Unit) : JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)) {
    private var hovered = false

    init {
        isOpaque = false
        border = JBUI.Borders.empty(2, 8, 2, 2)
        toolTipText = "Saved for this app. Click to edit, × to remove."
        val chip = SavedChip.of(word)
        val small = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
        if (chip.key.isNotEmpty()) add(JBLabel(chip.key).apply { font = small; foreground = JBColor.GRAY })
        add(JBLabel(chip.value).apply { font = small; border = JBUI.Borders.emptyRight(4) })
        add(InplaceButton(IconButton("Remove this saved filter", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { remove() })
        val mouse = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hovered = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hovered = false; repaint() }
            override fun mouseClicked(e: MouseEvent) = edit()
        }
        addMouseListener(mouse)
        components.filterIsInstance<JBLabel>().forEach { it.addMouseListener(mouse) }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = if (hovered) JBColor.namedColor("ActionButton.hoverBackground", TAG) else TAG
        val arc = JBUI.scale(8)
        g2.fillRoundRect(0, 0, width, height, arc, arc)
        g2.dispose()
    }

    private companion object {
        val TAG = JBColor.namedColor("Tag.background", JBColor(0xEBECF0, 0x26282C))
    }
}
