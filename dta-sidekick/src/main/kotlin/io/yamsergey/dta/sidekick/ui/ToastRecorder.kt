package io.yamsergey.dta.sidekick.ui

import android.os.SystemClock
import android.view.View
import android.widget.Toast
import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.rpc.DegradationMarker
import io.yamsergey.dta.protocol.ui.UiEvent
import io.yamsergey.dta.protocol.ui.UiEventKind
import io.yamsergey.dta.protocol.ui.UiSource
import io.yamsergey.dta.protocol.ui.WindowKind
import io.yamsergey.dta.protocol.ui.WindowRef
import io.yamsergey.dta.shim.EntryHandler
import io.yamsergey.dta.shim.ShimReflect
import io.yamsergey.dta.sidekick.hooks.Hooks
import java.util.Collections
import java.util.WeakHashMap

/**
 * Records toasts when the app shows them (feature 015 US2, research R6): an entry hook on
 * Toast#show, the text and duration read through the shim. A toast with its own view is
 * recorded once, as a toast: its window is not recorded again ([isToastView]).
 */
class ToastRecorder(
    private val buffer: UiJournalBuffer,
    private val walker: WindowWalker,
    private val links: (Long) -> List<io.yamsergey.dta.protocol.ui.EventLink>,
) {
    private val toastViews: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())

    var failure: String? = null
        private set

    fun isToastView(view: View): Boolean = synchronized(toastViews) { view in toastViews }

    fun install(): String? {
        val result = Hooks.entry(Toast::class.java, "show", "()V", EntryHandler { self, _ -> runCatching { onShow(self as Toast) } })
        failure = when (result) {
            is Hooks.Install.Installed -> null
            is Hooks.Install.ClassAbsent -> "Toast: class absent"
            is Hooks.Install.Failed -> "Toast#show: ${result.reason}"
        }
        return failure
    }

    private fun onShow(toast: Toast) {
        val now = SystemClock.uptimeMillis()
        val unreadable = mutableListOf<String>()
        val text = try {
            (ShimReflect.field(toast, "mText") as? CharSequence)?.toString()
        } catch (_: Throwable) {
            unreadable += "text"
            null
        }
        val view = try {
            ShimReflect.field(toast, "mNextView") as? View
        } catch (_: Throwable) {
            null
        }
        view?.let { synchronized(toastViews) { toastViews += it } }
        val summary = view?.let { ContentSummaries.summarize(walker.walk(it), secure = false, settled = true, takenMonoMs = now) }
        val duration = if (toast.duration == Toast.LENGTH_LONG) LONG_MS else SHORT_MS
        buffer.append { id, seq ->
            UiEvent(
                id, seq, now, System.currentTimeMillis(), UiEventKind.TOAST, UiSource.TOAST,
                window = WindowRef(windowId = "toast$seq", kind = WindowKind.TOAST, title = text ?: summary?.firstText),
                content = summary?.summary,
                text = text ?: summary?.firstText,
                durationMs = duration,
                completeness = unreadable.map { DegradationMarker("ui", "${DegradationCodes.UI_FIELD_UNREADABLE}: toast $it not readable on this Android version") },
                links = links(System.currentTimeMillis()),
            )
        }
    }

    private companion object {
        const val SHORT_MS = 2_000L
        const val LONG_MS = 3_500L
    }
}
