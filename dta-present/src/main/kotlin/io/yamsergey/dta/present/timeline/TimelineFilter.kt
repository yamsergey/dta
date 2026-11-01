package io.yamsergey.dta.present.timeline

import io.yamsergey.dta.protocol.timeline.Kinds
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.TimelineEvent

/** What the Timeline tab narrows to (feature 017 US3). Gaps and process ends always pass. */
data class TimelineFilter(
    /** Empty: every source. */
    val sources: Set<String> = emptySet(),
    val text: String = "",
    val unexplainedOnly: Boolean = false,
) {
    fun matches(e: TimelineEvent): Boolean {
        if (e.source == Sources.TIMELINE) return true
        if (sources.isNotEmpty() && e.source !in sources) return false
        if (unexplainedOnly && !unexplained(e)) return false
        if (text.isNotBlank()) {
            val needle = text.trim().lowercase()
            if (needle !in e.id.lowercase() && needle !in TimelineEventText.line(e).lowercase()) return false
        }
        return true
    }

    val active: Boolean get() = sources.isNotEmpty() || text.isNotBlank() || unexplainedOnly

    companion object {
        /** No exact or declared cause, and not a root (the user's input, the timeline's own marks). */
        fun unexplained(e: TimelineEvent): Boolean =
            e.source != Sources.INPUT && e.source != Sources.TIMELINE && e.kind != Kinds.DECLARED_CAUSE && e.causes.isEmpty()
    }
}
