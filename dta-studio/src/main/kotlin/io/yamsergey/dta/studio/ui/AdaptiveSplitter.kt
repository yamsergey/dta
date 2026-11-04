package io.yamsergey.dta.studio.ui

import com.intellij.ui.JBSplitter
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JComponent

/**
 * A master–detail split that follows the space (features 031, 032): the detail beside the master
 * in a wide low window (the usual bottom tool window), below it in a tall one; proportion remembered.
 */
fun adaptiveSplitter(key: String, proportion: Float, master: JComponent, detail: JComponent) = JBSplitter(true, key, proportion).apply {
    firstComponent = master
    secondComponent = detail
    addComponentListener(object : ComponentAdapter() {
        override fun componentResized(e: ComponentEvent) {
            val vertical = height > width / 2
            if (orientation != vertical) orientation = vertical
        }
    })
}
