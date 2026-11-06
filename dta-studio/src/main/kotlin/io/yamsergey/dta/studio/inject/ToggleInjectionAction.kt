package io.yamsergey.dta.studio.inject

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.CheckboxAction
import com.intellij.openapi.project.DumbAware

/** The injection switch in the DTA tool window's title bar, a checkbox with its name as the tooltip (043), the same one as in Settings. */
class ToggleInjectionAction : CheckboxAction("", "Add DTA to debug builds run from Android Studio", null), DumbAware {
    override fun isSelected(e: AnActionEvent): Boolean = InjectionSettings.get().enabled

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        InjectionSettings.get().enabled = state
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
