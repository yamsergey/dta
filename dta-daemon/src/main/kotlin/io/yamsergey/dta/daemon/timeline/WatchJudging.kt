package io.yamsergey.dta.daemon.timeline

import io.yamsergey.dta.daemon.network.Judgement
import io.yamsergey.dta.protocol.network.SavedFiltersApplied
import io.yamsergey.dta.protocol.timeline.TimelineEvent

/**
 * Feature 065: Network's saved filters on `timeline watch`, one batch of live events at a time.
 * Only network events are judged, by entry id, with Network's own evaluator ([judge]); a verdict
 * is kept until the saved words change ([words] is cheap, [judge] reads the history). An entry the
 * history does not hold yet is shown and counted as not judged, and asked again next time. What a
 * batch hid is said in [Batch.saved] (constitution III); a store problem is said once.
 */
class WatchJudging(
    private val words: suspend () -> List<String>,
    private val judge: suspend () -> Judgement,
) {
    class Batch(val events: List<TimelineEvent>, val saved: SavedFiltersApplied?)

    private val known = HashMap<String, Judgement.Verdict>()
    private var knownFor: List<String>? = null
    private var said: String? = null

    suspend fun judge(events: List<TimelineEvent>): Batch {
        val entries = events.mapNotNull { it.network?.entryId }.distinct()
        if (entries.isEmpty()) return Batch(events, null)
        val now = words()
        if (now.isEmpty()) {
            known.clear()
            knownFor = now
            return Batch(events, null)
        }
        if (now != knownFor) {
            known.clear()
            knownFor = now
        }
        if (entries.any { it !in known }) {
            val j = judge()
            if (j.words != knownFor) {
                known.clear()
                knownFor = j.words
            }
            for (id in entries) {
                if (id in known) continue
                val v = j.verdict(id)
                if (v != Judgement.Verdict.NOT_JUDGED && j.active) known[id] = v
                else if (!j.active) known[id] = Judgement.Verdict.SHOWN
            }
            val problem = j.problem ?: j.unavailable
            if (problem != null && problem != said) {
                said = problem
                return Batch(events, j.summary(0, 0))
            }
        }
        var hidden = 0
        var notJudged = 0
        val kept = events.filter { e ->
            when (e.network?.let { known[it.entryId] ?: Judgement.Verdict.NOT_JUDGED }) {
                Judgement.Verdict.HIDDEN -> { hidden++; false }
                Judgement.Verdict.NOT_JUDGED -> { notJudged++; true }
                else -> true
            }
        }
        if (hidden == 0 && notJudged == 0) return Batch(kept, null)
        return Batch(kept, SavedFiltersApplied(words = knownFor.orEmpty(), hidden = hidden, notJudged = notJudged.takeIf { it > 0 }))
    }
}

/** What `timeline watch` sends: an event, or what Network's saved filters hid from the last batch (065). */
sealed interface TimelineWatchItem {
    data class Event(val event: TimelineEvent) : TimelineWatchItem
    data class Hidden(val saved: SavedFiltersApplied) : TimelineWatchItem
}
