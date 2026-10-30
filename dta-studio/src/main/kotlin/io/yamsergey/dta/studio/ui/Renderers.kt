package io.yamsergey.dta.studio.ui

import com.intellij.ui.SimpleListCellRenderer
import javax.swing.JList
import javax.swing.ListCellRenderer

/** A list renderer showing [text] of each item, [none] for no item. */
fun <T> textRenderer(none: String = "", text: (T) -> String): ListCellRenderer<T?> = object : SimpleListCellRenderer<T?>() {
    override fun customize(list: JList<out T?>, value: T?, index: Int, selected: Boolean, hasFocus: Boolean) {
        setText(value?.let(text) ?: none)
    }
}
