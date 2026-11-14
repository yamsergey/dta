package io.yamsergey.dta.daemon.timeline

import io.yamsergey.dta.daemon.network.Judgement
import io.yamsergey.dta.protocol.timeline.CauseChain
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import io.yamsergey.dta.protocol.timeline.TimelineHidden
import io.yamsergey.dta.protocol.timeline.TimelinePage

/**
 * Feature 065: Network's saved filters on timeline answers. Only network events are judged, by
 * entry id (a request and its response share one); every other event stays. What is hidden is
 * always said in the answer's `saved`.
 */
object TimelineJudging {
    /** A page without its hidden network events; the cursor still moves past them. */
    fun page(page: TimelinePage, judgement: Judgement): TimelinePage {
        if (!judgement.active) return page.copy(saved = judgement.summary(0, 0))
        var hidden = 0
        var notJudged = 0
        val kept = page.events.filter { e ->
            when (e.network?.let { judgement.verdict(it.entryId) }) {
                Judgement.Verdict.HIDDEN -> { hidden++; false }
                Judgement.Verdict.NOT_JUDGED -> { notJudged++; true }
                else -> true
            }
        }
        return page.copy(events = kept, saved = judgement.summary(hidden, notJudged))
    }

    /** A cause chain keeps every node (it tells one event's story) and names those hidden elsewhere. */
    fun chain(chain: CauseChain, judgement: Judgement): CauseChain {
        val ids = (listOf(chain.root) + chain.nodes).distinctBy { it.id }
            .filter { e -> e.network?.let { judgement.verdict(it.entryId) } == Judgement.Verdict.HIDDEN }
            .map { it.id }
        return chain.copy(saved = judgement.summary(ids.size, 0, hiddenIds = ids))
    }

    /** For a view following the timeline live: its network entries hidden now, and those not judged. */
    fun hidden(events: List<TimelineEvent>, judgement: Judgement): TimelineHidden {
        val entries = events.mapNotNull { it.network?.entryId }.distinct()
        val hidden = entries.filter { judgement.verdict(it) == Judgement.Verdict.HIDDEN }
        val notJudged = entries.filter { judgement.verdict(it) == Judgement.Verdict.NOT_JUDGED }
        return TimelineHidden(judgement.summary(hidden.size, notJudged.size), hidden, notJudged)
    }
}
