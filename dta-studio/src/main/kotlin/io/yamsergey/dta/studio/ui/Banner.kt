package io.yamsergey.dta.studio.ui

import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.present.context.Banner
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JPanel

/**
 * A whole-view statement in the IDE's banner layout (surfaces.md): glyph and text in the
 * statement's colour on its tint, cause and next step, an optional action. Never dismissible.
 */
class BannerPanel : JPanel(BorderLayout()) {
    private val text = JBLabel()
    private val actions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply { isOpaque = false }
    private var onAction: (String) -> Unit = {}

    init {
        border = JBUI.Borders.empty(6, 12)
        add(text, BorderLayout.CENTER)
        add(actions, BorderLayout.EAST)
        isVisible = false
    }

    fun onAction(handler: (String) -> Unit) { onAction = handler }

    fun show(banner: Banner?) {
        isVisible = banner != null
        actions.removeAll()
        banner ?: return
        background = DtaColors.tint(banner.kind)
        text.foreground = DtaColors.text(banner.kind)
        text.text = "<html>${banner.kind.glyph} ${escape(banner.text)}</html>"
        banner.action?.let { name -> actions.add(ActionLink(name) { onAction(name) }) }
        revalidate()
        repaint()
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
}
