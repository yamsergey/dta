package io.yamsergey.dta.studio.inject

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareToggleAction

/** The injection switch in the DTA tool window's title bar, the same one as in Settings. */
class ToggleInjectionAction : DumbAwareToggleAction("Add DTA to Debug Builds", "Add DTA to debug builds run from Android Studio", null) {
    override fun isSelected(e: AnActionEvent): Boolean = InjectionSettings.get().enabled

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        InjectionSettings.get().enabled = state
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
