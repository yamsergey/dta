package io.yamsergey.dta.studio.inject

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

/** Settings › Tools › DTA: the injection switches (009 FR-021, 010 FR-015) and the optional features (056). */
class InjectionConfigurable : BoundConfigurable("DTA") {
    override fun createPanel(): DialogPanel = panel {
        row {
            checkBox("Add DTA to debug builds run from Android Studio")
                .bindSelected(InjectionSettings.get()::enabled)
                .comment("No project files change. Release builds never get DTA; a build where DTA cannot be added runs without it, and the DTA tool window says why.")
        }
        row {
            checkBox("Add Compose tooling data where a Compose app lacks it")
                .bindSelected(InjectionSettings.get()::tooling)
                .comment("Layout then shows composable names and source lines. Added in the app's own Compose version, to debug builds only.")
        }
        group("Features") {
            row {
                checkBox("Timeline: record what happens in the app (taps, screens, network, ViewModel changes)")
                    .bindSelected(InjectionSettings.get()::timeline)
                    .comment("Shows the Timeline tab and its flows. Off: the tab is hidden and the app records nothing for it. A change applies from the app's next start.")
            }
        }
    }
}
