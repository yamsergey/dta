package io.yamsergey.dta.studio.toolwindow

import com.intellij.util.ui.JBUI
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/** The footer's caption line: spinner, caption, link. */
class CaptionRow(private val spinner: JComponent, private val caption: JLabel, private val link: JComponent) : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)) {
    init { isOpaque = false; add(spinner); add(caption); add(link) }

    fun setCaption(text: String) { caption.text = text }
}
