package io.yamsergey.dta.studio.toolwindow

import com.intellij.util.ui.JBUI
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The connect row of the Agents tab: the agent choice with its Copy command link always beside it (spec 073, mockup
 * board 5: the pair never breaks), the hint after them in one line when it fits, under them when it does not (D-140).
 */
object ConnectRow {
    fun build(agent: JComponent, copy: JComponent, hint: JComponent): JPanel = JPanel(LineOrStack(JBUI.scale(8), lastAtEdge = false)).apply {
        isOpaque = false
        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(agent)
            add(javax.swing.Box.createHorizontalStrut(JBUI.scale(8)))
            add(copy)
        })
        add(hint)
    }
}
