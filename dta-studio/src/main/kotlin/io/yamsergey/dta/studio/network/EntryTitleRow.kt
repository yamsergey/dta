package io.yamsergey.dta.studio.network

import com.intellij.util.ui.JBUI
import io.yamsergey.dta.studio.ui.FitLabel
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The entry detail's title: "METHOD url" in bold, cut in the middle with an ellipsis when the pane is narrow (host and the end of
 * the path stay, the whole in the tooltip), and the Copy link beside it, always whole and inside the pane (D-153).
 */
class EntryTitleRow(text: String, copy: JComponent?) : JPanel(BorderLayout(JBUI.scale(12), 0)) {
    internal val title = FitLabel(asMiddle = true).apply { font = font.deriveFont(Font.BOLD); show(text) }

    init {
        isOpaque = false
        border = JBUI.Borders.emptyTop(4)
        add(title, BorderLayout.CENTER)
        copy?.let { add(it, BorderLayout.EAST) }
        alignmentX = LEFT_ALIGNMENT
        maximumSize = java.awt.Dimension(Int.MAX_VALUE, preferredSize.height)
    }
}
