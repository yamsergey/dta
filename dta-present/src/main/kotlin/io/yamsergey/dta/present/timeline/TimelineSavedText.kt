package io.yamsergey.dta.present.timeline

import io.yamsergey.dta.protocol.network.SavedFiltersApplied

/**
 * Feature 065: the line every timeline text ends with when Network's saved filters hid network
 * rows from it (constitution III: never silent). Nothing is said when nothing was hidden or the
 * call opted out; a store problem or no history to judge with is said, as nothing was hidden.
 */
object TimelineSavedText {
    const val HIDDEN_MARK = "· hidden by Network's filters"

    /**
     * The line a live watch says once at its start and again when the saved filters change: the
     * person who saved them knows; whoever forgot them, or an agent, learns why rows are missing.
     */
    fun watching(saved: SavedFiltersApplied, optOut: String = "--no-saved-filters"): String? {
        if (saved.ignored) return null
        saved.problem?.let { return "Network's saved filters could not be read ($it); nothing is hidden" }
        saved.unavailable?.let { return "Network's saved filters are not applied: $it" }
        if (saved.words.isEmpty()) return "Network's filters hide nothing now"
        return "Network's filters hide matching network rows (${saved.words.joinToString(", ")}) · $optOut shows them"
    }

    fun notice(saved: SavedFiltersApplied?, optOut: String = "--no-saved-filters"): String? {
        if (saved == null || saved.ignored) return null
        saved.problem?.let { return "Network's saved filters could not be read ($it); nothing is hidden" }
        saved.unavailable?.let { return "Network's saved filters are not applied: $it" }
        val hidden = saved.hidden ?: 0
        val notJudged = saved.notJudged ?: 0
        if (hidden == 0 && notJudged == 0) return null
        val filters = saved.words.joinToString(", ")
        val unjudged = when (notJudged) {
            0 -> null
            1 -> "1 not judged: its request is no longer kept"
            else -> "$notJudged not judged: their requests are no longer kept"
        }
        if (hidden == 0) return listOfNotNull("Network's filters ($filters) hid nothing here", unjudged).joinToString(" · ")
        val head = "$hidden network row${if (hidden == 1) "" else "s"} hidden by Network's filters ($filters)"
        return listOfNotNull(head, unjudged, "$optOut shows them").joinToString(" · ")
    }
}
