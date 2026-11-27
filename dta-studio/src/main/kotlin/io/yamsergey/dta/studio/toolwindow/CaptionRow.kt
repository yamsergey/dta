package io.yamsergey.dta.studio.toolwindow

import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * The footer's caption line: spinner, caption, link. The caption takes what the others leave and ends in
 * the label's ellipsis when short of room, the whole text in its tooltip: never cut at the row's edge (D-106).
 */
class CaptionRow(spinner: JComponent, private val caption: JLabel, link: JComponent) : JPanel(BorderLayout(JBUI.scale(6), 0)) {
    init {
        isOpaque = false
        add(spinner, BorderLayout.WEST); add(caption, BorderLayout.CENTER); add(link, BorderLayout.EAST)
    }

    fun setCaption(text: String) { caption.text = text; caption.toolTipText = text.ifEmpty { null } }
}
