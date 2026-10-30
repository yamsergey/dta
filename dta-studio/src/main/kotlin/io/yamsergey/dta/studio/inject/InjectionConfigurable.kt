package io.yamsergey.dta.studio.inject

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

/** Settings › Tools › DTA: the injection switch (FR-021). */
class InjectionConfigurable : BoundConfigurable("DTA") {
    override fun createPanel(): DialogPanel = panel {
        row {
            checkBox("Add DTA to debug builds run from Android Studio")
                .bindSelected(InjectionSettings.get()::enabled)
                .comment("No project files change. Release builds never get DTA; a build where DTA cannot be added runs without it, and the DTA tool window says why.")
        }
    }
}
