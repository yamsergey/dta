package io.yamsergey.dta.sidekick.ui

import io.yamsergey.dta.protocol.timeline.Bounds
import io.yamsergey.dta.protocol.ui.WindowKind
import io.yamsergey.dta.protocol.ui.WindowRef

/** The `ui.windows` facet (feature 016, research R10): the app's open windows, bottom first. */
object WindowsFacet {
    fun value(windows: List<WindowRef>): String? {
        if (windows.isEmpty()) return null
        val text = windows.joinToString(" + ") { w ->
            if (w.kind == WindowKind.ACTIVITY) {
                w.owner?.substringAfterLast('.') ?: w.title ?: "activity"
            } else {
                val kind = kindName(w.kind)
                if (w.title.isNullOrBlank()) kind else "$kind \"${w.title}\""
            }
        }
        return if (text.length <= Bounds.FACET_VALUE) text else text.take(Bounds.FACET_VALUE - 1) + "…"
    }

    private fun kindName(kind: WindowKind): String = WindowKind.serializer().descriptor.getElementName(kind.ordinal)
}
