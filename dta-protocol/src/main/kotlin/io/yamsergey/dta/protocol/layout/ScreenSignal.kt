package io.yamsergey.dta.protocol.layout

import kotlinx.serialization.Serializable

/**
 * Feature 057: "the app's screen changed", for a Layout view that refreshes by itself. Runs in the
 * app only while leased by a watcher ([METHOD]); records nothing; independent of the timeline.
 */
object ScreenSignal {
    /** App method: `{leaseMs}` arms or renews the watch (0 stops it); answers [LeaseAnswer]. */
    const val METHOD = "layout_watch"

    /** App event while leased: [LayoutChanged], at most one per [MIN_GAP_MS]. */
    const val APP_EVENT = "layout.changed"

    const val MAX_LEASE_MS = 30_000L
    const val MIN_GAP_MS = 500L

    const val WATCHING = "watching"
    const val UNSUPPORTED = "unsupported"
    const val RECONNECTING = "reconnecting"

    const val UNSUPPORTED_REASON = "this app's DTA is older and reports no screen changes: update it, or refresh by hand"
}

@Serializable
data class LeaseAnswer(val watching: Boolean, val until: Long, val sources: List<String> = emptyList())

/** [reasons]: `layout` (a View layout pass), `windows` (a window added or removed), `compose` (Compose state changed). */
@Serializable
data class LayoutChanged(val at: Long, val reasons: List<String>)

@Serializable
data class LayoutSignalState(val state: String, val reason: String? = null)
