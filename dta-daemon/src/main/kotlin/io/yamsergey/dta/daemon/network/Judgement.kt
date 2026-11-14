package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.protocol.network.NetworkHistoryView
import io.yamsergey.dta.protocol.network.SavedFiltersApplied

/**
 * Feature 065: how Network's saved filters judge the Timeline's network rows, by entry id. A row
 * passes when Network's list keeps its entry for the saved words, is hidden when the history holds
 * the entry and the list leaves it out, and is not judged when the history no longer holds it
 * (shown, counted). With nothing saved, the opt-out, a store problem or no history to judge with,
 * nothing is hidden; [summary] says which.
 */
class Judgement(
    val words: List<String>,
    val ignored: Boolean = false,
    val problem: String? = null,
    val unavailable: String? = null,
    private val passing: NetworkHistoryView.Passing? = null,
) {
    enum class Verdict { SHOWN, HIDDEN, NOT_JUDGED }

    /** Whether any row can be hidden. */
    val active: Boolean get() = passing != null && words.isNotEmpty() && !ignored

    fun verdict(entryId: String): Verdict {
        val p = passing ?: return Verdict.SHOWN
        if (!active) return Verdict.SHOWN
        return when (entryId) {
            in p.ids -> Verdict.SHOWN
            in p.seen -> Verdict.HIDDEN
            else -> Verdict.NOT_JUDGED
        }
    }

    /** The answer's `saved` field; null when no saved filter was in play and nothing is to be said. */
    fun summary(hidden: Int, notJudged: Int, hiddenIds: List<String>? = null): SavedFiltersApplied? {
        if (words.isEmpty() && problem == null) return null
        return SavedFiltersApplied(
            words = words,
            hidden = if (active) hidden else null,
            ignored = ignored,
            notJudged = notJudged.takeIf { active && it > 0 },
            problem = problem,
            unavailable = unavailable,
            hiddenIds = hiddenIds?.takeIf { active && it.isNotEmpty() },
        )
    }

    companion object {
        val NONE = Judgement(emptyList())
    }
}
