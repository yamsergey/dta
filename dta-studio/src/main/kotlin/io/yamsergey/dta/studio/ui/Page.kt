package io.yamsergey.dta.studio.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.Scrollable
import javax.swing.SwingConstants

/**
 * A vertical page for one scroll pane: it takes the viewport's width, so long values wrap
 * instead of scrolling sideways (one scroll per pane, patterns.md master-detail).
 */
open class Page : JPanel(GridBagLayout()), Scrollable {
    private var row = 0

    init {
        border = JBUI.Borders.empty(8, 12)
    }

    /** Adds [c] across the full width. */
    fun line(c: JComponent, top: Int = 0) {
        add(c, GridBagConstraints().apply {
            gridx = 0; gridy = row++; gridwidth = 2; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; anchor = GridBagConstraints.NORTHWEST
            insets = JBUI.insetsTop(top)
        })
    }

    /** A section title, as the IDE's own panels draw them. */
    fun title(text: String) = line(JBLabel(text).apply { font = font.deriveFont(Font.BOLD) }, top = if (row == 0) 0 else 12)

    /** Name and value in two aligned columns; the value wraps and can be selected and copied. */
    fun field(name: String, value: String, mono: Boolean = false) {
        add(JBLabel(name).apply { foreground = JBColor.GRAY; verticalAlignment = SwingConstants.TOP }, GridBagConstraints().apply {
            gridx = 0; gridy = row; anchor = GridBagConstraints.NORTHWEST; insets = JBUI.insets(2, 0, 2, 12)
        })
        add(Value(value, mono), GridBagConstraints().apply {
            gridx = 1; gridy = row++; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; anchor = GridBagConstraints.NORTHWEST; insets = JBUI.insets(2, 0)
        })
    }

    /** Pushes everything above it to the top. */
    fun end() {
        add(JPanel().apply { isOpaque = false }, GridBagConstraints().apply { gridx = 0; gridy = row++; weighty = 1.0; gridwidth = 2; fill = GridBagConstraints.BOTH })
    }

    fun clear() {
        removeAll()
        row = 0
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = visibleRect.height
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false

    /** Read-only wrapping text that looks like a label. */
    private class Value(text: String, mono: Boolean) : JTextArea(text) {
        init {
            isEditable = false
            lineWrap = true
            wrapStyleWord = false
            isOpaque = false
            border = null
            font = if (mono) UIUtil.getFontWithFallback(EditorFont.name, Font.PLAIN, UIUtil.getLabelFont().size) else UIUtil.getLabelFont()
            foreground = UIUtil.getLabelForeground()
        }

        // A wrapping text area must not insist on its unwrapped width inside the grid.
        override fun getMinimumSize(): Dimension = Dimension(0, super.getMinimumSize().height)
    }

    private object EditorFont {
        val name: String
            get() = com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().globalScheme.editorFontName
    }
}
