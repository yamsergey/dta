package io.yamsergey.dta.daemon.timeline

import io.yamsergey.dta.daemon.network.Judgement
import io.yamsergey.dta.protocol.network.SavedFiltersApplied
import io.yamsergey.dta.protocol.timeline.TimelineEvent

/**
 * Feature 065: Network's saved filters on `timeline watch`, one batch of live events at a time.
 * Only network events are judged, by entry id, with Network's own evaluator ([judge]); a verdict
 * is kept until the saved words change ([words] is cheap, [judge] reads the history). A network row
 * whose entry the history does not hold yet (the timeline got it first: a fresh daemon still catching
 * up the app's journal, a batch on its way) is held back, and what follows waits behind it, so the
 * order stays; it is judged again on each batch and shown after [holdMs] if still not judged, never
 * lost (D-068: shown at once, rows the filters hide reached the watch). Which filters apply is said once,
 * at the start ([start]) and when they change or cannot be read ([Batch.saved]); hidden rows are
 * not counted one by one: the person who saved the filters knows (2026-11-15).
 */
class WatchJudging(
    private val words: suspend () -> List<String>,
    private val judge: suspend () -> Judgement,
    private val clock: () -> Long = System::currentTimeMillis,
    /** How long a network row whose entry the history does not hold yet is held back (D-068). */
    private val holdMs: Long = HOLD_MS,
) {
    companion object {
        const val HOLD_MS = 10_000L
    }

    class Batch(val events: List<TimelineEvent>, val saved: SavedFiltersApplied?)

    private val known = HashMap<String, Judgement.Verdict>()
    private var knownFor: List<String>? = null
    private var said: List<String>? = null
    private var saidProblem: String? = null

    /** The filters in force when the watch starts; null when none are saved. */
    suspend fun start(): SavedFiltersApplied? {
        val now = words()
        knownFor = now
        said = now
        return now.takeIf { it.isNotEmpty() }?.let { SavedFiltersApplied(it) }
    }

    /** Events not given yet, oldest first, with when each was first seen (D-068). */
    private val held = ArrayList<Pair<TimelineEvent, Long>>()

    /**
     * [events] (the next live batch, may be empty) after the rows held from earlier batches: what can be
     * given now, in order, the rows the filters hide left out.
     */
    suspend fun judge(events: List<TimelineEvent>): Batch {
        val at = clock()
        events.forEach { held += it to at }
        val now = words()
        val entries = held.mapNotNull { it.first.network?.entryId }.distinct()
        if (now != knownFor) {
            known.clear()
            knownFor = now
        }
        var saved = announce(now)
        if (entries.isEmpty() || now.isEmpty()) return Batch(release(held.size), saved)
        if (entries.any { it !in known }) {
            val j = judge()
            if (j.words != knownFor) {
                known.clear()
                knownFor = j.words
            }
            for (id in entries) {
                if (id in known) continue
                if (!j.active) known[id] = Judgement.Verdict.SHOWN
                else j.verdict(id).takeIf { it != Judgement.Verdict.NOT_JUDGED }?.let { known[id] = it }
            }
            val problem = j.problem ?: j.unavailable
            if (problem != null && problem != saidProblem) {
                saidProblem = problem
                saved = j.summary(0, 0)?.copy(hidden = null)
            }
        }
        // Given up to the first row still not judged and held for less than the bound.
        val waiting = held.indexOfFirst { (e, since) -> e.network?.let { it.entryId !in known } == true && at - since < holdMs }
        val ready = release(if (waiting < 0) held.size else waiting)
        return Batch(ready.filter { e -> e.network?.let { known[it.entryId] } != Judgement.Verdict.HIDDEN }, saved)
    }

    private fun release(n: Int): List<TimelineEvent> {
        val out = held.subList(0, n).map { it.first }
        held.subList(0, n).clear()
        return out
    }

    private fun announce(now: List<String>): SavedFiltersApplied? {
        if (now == said) return null
        said = now
        return SavedFiltersApplied(now)
    }
}

/** What `timeline watch` sends: an event, or which of Network's saved filters apply now (065). */
sealed interface TimelineWatchItem {
    data class Event(val event: TimelineEvent) : TimelineWatchItem
    data class Filters(val saved: SavedFiltersApplied) : TimelineWatchItem
}
