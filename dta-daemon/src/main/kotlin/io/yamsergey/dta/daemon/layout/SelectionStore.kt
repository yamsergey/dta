package io.yamsergey.dta.daemon.layout

import io.yamsergey.dta.protocol.layout.LayoutCapture
import io.yamsergey.dta.protocol.layout.LayoutSelection
import io.yamsergey.dta.protocol.layout.SelectedElement
import io.yamsergey.dta.protocol.layout.Selections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Feature 055: one layout selection per app on a device, in memory only, shared by every client;
 * and the latest full capture of each app (from any client), against which ids and points are
 * resolved and selected elements are said to be still on screen or gone.
 */
class SelectionStore(private val clock: () -> Long = System::currentTimeMillis) {
    private data class Key(val serial: String, val pkg: String)
    private class Latest(val capture: LayoutCapture, val at: Long)

    private val selections = ConcurrentHashMap<Key, MutableStateFlow<LayoutSelection>>()
    private val latest = ConcurrentHashMap<Key, Latest>()

    fun flow(serial: String, pkg: String): StateFlow<LayoutSelection> = state(serial, pkg)

    /** A whole (unfiltered) capture of the app; filtered answers say nothing of the screen. */
    fun remember(serial: String, pkg: String, capture: LayoutCapture, at: Long = clock()) {
        if (capture.filtersApplied || capture.windows.isEmpty()) return
        latest[Key(serial, pkg)] = Latest(capture, at)
    }

    fun latest(serial: String, pkg: String): Pair<LayoutCapture, Long>? = latest[Key(serial, pkg)]?.let { it.capture to it.at }

    /** The selection, each element said on-screen, gone or not-checked against the latest capture. */
    fun read(serial: String, pkg: String): LayoutSelection {
        val s = state(serial, pkg).value
        val l = latest[Key(serial, pkg)]
        return s.copy(elements = s.elements.map { it.copy(now = Selections.freshness(it, l?.capture, l?.at)) })
    }

    @Synchronized
    fun change(serial: String, pkg: String, mode: String, elements: List<SelectedElement>, by: String): LayoutSelection {
        val flow = state(serial, pkg)
        flow.value = Selections.apply(flow.value, mode, elements, by, clock())
        return read(serial, pkg)
    }

    /** The app process ended: its selection and latest capture go, and the selection says so. */
    @Synchronized
    fun ended(serial: String, pkg: String) {
        latest.remove(Key(serial, pkg))
        val flow = selections[Key(serial, pkg)] ?: return
        if (flow.value.elements.isEmpty()) return
        flow.value = flow.value.copy(revision = flow.value.revision + 1, elements = emptyList(), omitted = null, changedBy = "dta", changedAt = clock(), note = Selections.ENDED)
    }

    private fun state(serial: String, pkg: String) = selections.getOrPut(Key(serial, pkg)) { MutableStateFlow(LayoutSelection(serial, pkg)) }
}
