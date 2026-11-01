package io.yamsergey.dta.studio.ui

import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.KeyStroke

/**
 * The views' shared keys (patterns.md, contracts/tool-window.md): `/` search, `f` filters,
 * `g`/`G` start/end and follow, `p` pause, `c` copy value; Enter and Escape move between list
 * and detail. Registered while focus is inside the view, so the IDE's own bindings still work.
 */
enum class ViewKey(val stroke: String) {
    SEARCH("SLASH"), FILTERS("F"), START("G"), END("shift G"), PAUSE("P"), COPY("C"), OPEN("ENTER"), BACK("ESCAPE"),
    /** Layout (feature 014): the next element under the last click on the picture. */
    NEXT_UNDER_POINTER("N"),
}

object Keyboard {
    fun bind(view: JComponent, key: ViewKey, action: () -> Unit) {
        val name = "dta.${key.name}"
        view.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(key.stroke), name)
        view.actionMap.put(name, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                // Letters typed into a field (search, filters) are text, not commands.
                val focus = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                if (focus is javax.swing.text.JTextComponent && key !in setOf(ViewKey.OPEN, ViewKey.BACK)) return
                action()
            }
        })
    }
}
