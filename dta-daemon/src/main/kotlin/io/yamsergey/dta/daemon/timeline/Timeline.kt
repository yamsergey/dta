package io.yamsergey.dta.daemon.timeline

import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.rpc.DegradationMarker
import io.yamsergey.dta.protocol.timeline.Kinds
import io.yamsergey.dta.protocol.timeline.SourceStatus
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.TimelineBoundsInfo
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import io.yamsergey.dta.protocol.timeline.TimelinePage
import io.yamsergey.dta.protocol.timeline.TimelineRead
import io.yamsergey.dta.protocol.ui.UiEvent
import io.yamsergey.dta.protocol.ui.UiEventKind
import io.yamsergey.dta.protocol.ui.UiSource
import io.yamsergey.dta.protocol.ui.WindowRef

/**
 * One app's timeline on the host (features 015/016, research R1): what the app's sidekick
 * recorded from every source, caught up by seq and kept across reconnections and process
 * deaths, within [bound] events. Readers hold their own cursors (`<session>:<hostSeq>`);
 * reading never consumes. Losses — the app's ring overflowing, old events evicted here, a
 * cursor from another timeline — are gaps in place (constitution III). The facets the events
 * change are kept in [facets], apart from eviction.
 */
class Timeline(
    private val session: String,
    private val bound: Int = BOUND,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Kept(val hostSeq: Long, val event: TimelineEvent)

    private val kept = ArrayDeque<Kept>()
    private var hostSeq = 0L
    private var epoch: Long? = null
    private var lastSeq = 0L
    private var lostAt: Long? = null
    private var endedRecorded = false

    val facets = FacetTable()

    /** The sources the app reported last; empty until it was read. */
    @Volatile
    var sources: List<SourceStatus> = emptyList()

    /** The app process the timeline follows (its sidekick's epoch), null before the first read. */
    @get:Synchronized
    val followedEpoch: Long? get() = epoch

    /** The app's seq to read after, for the epoch the timeline follows (0 for a new one). */
    @get:Synchronized
    val afterSeq: Long get() = lastSeq

    /** Takes in a catch-up read from the app. */
    @Synchronized
    fun ingest(read: TimelineRead) {
        if (read.sources.isNotEmpty()) sources = read.sources
        if (read.epoch == 0L) return
        if (epoch != read.epoch) {
            if (epoch != null && !endedRecorded) appendEnd(lostAt ?: clock())
            epoch = read.epoch
            lastSeq = 0
            endedRecorded = false
        }
        lostAt = null
        val fresh = read.events.filter { it.seq > lastSeq }
        val firstSeq = fresh.firstOrNull()?.seq
        if (read.dropped > 0 && firstSeq != null && firstSeq > lastSeq + 1) {
            appendGap(read.dropped, "${DegradationCodes.UI_EVENTS_DROPPED}: ${read.dropped} events lost in the app's buffer before DTA read them")
        }
        fresh.forEach(::append)
        facets.seed(read.epoch, read.facets)
    }

    /** Takes in a live event; false when it does not follow the last one (catch up instead). */
    @Synchronized
    fun pushed(event: TimelineEvent): Boolean {
        val eventEpoch = event.id.substringBefore(':').toLongOrNull() ?: return false
        if (eventEpoch != epoch || event.seq != lastSeq + 1) return false
        lostAt = null
        append(event)
        return true
    }

    /** The link to the app went away; if the process turns out to have ended, that is when. */
    @Synchronized
    fun connectionLost() {
        if (lostAt == null) lostAt = clock()
    }

    /** The app's process is gone (its link was lost and it no longer runs). */
    @Synchronized
    fun processEnded() {
        if (epoch == null || endedRecorded) return
        appendEnd(lostAt ?: clock())
    }

    /** A window that is not the app's, seen by the host (015 US5); returns the event's id. */
    @Synchronized
    fun hostWindow(appeared: Boolean, window: WindowRef, at: Long, durationMs: Long?, appearedId: String?): String {
        val id = "host:${hostSeq + 1}"
        val kind = if (appeared) UiEventKind.WINDOW_APPEARED else UiEventKind.WINDOW_DISAPPEARED
        val ui = UiEvent(id, 0, 0, at, kind, UiSource.SCREEN_WINDOW, window = window, durationMs = durationMs, appearedId = appearedId)
        keep(
            TimelineEvent(
                id, 0, monoMs = 0, wallMs = at, source = Sources.UI,
                kind = Kinds.ui(UiEventKind.serializer().descriptor.getElementName(kind.ordinal)), ui = ui,
            ),
        )
        return id
    }

    /** A cursor after everything kept: what an agent reads from on its first call. */
    @Synchronized
    fun end(): String = "$session:$hostSeq"

    /** Every kept event, oldest first (all processes). */
    @Synchronized
    fun kept(): List<TimelineEvent> = kept.map { it.event }

    /** The kept events of the process the timeline follows now. */
    @Synchronized
    fun currentEpochEvents(): List<TimelineEvent> {
        val prefix = "${epoch ?: return emptyList()}:"
        return kept.map { it.event }.filter { it.id.startsWith(prefix) }
    }

    /** True when events of the followed process were evicted (its oldest seq is not 1). */
    @Synchronized
    fun currentEpochPartial(): Boolean = currentEpochEvents().firstOrNull()?.let { it.seq > 1 } ?: (lastSeq > 0)

    @Synchronized
    fun page(
        since: String?,
        limit: Int,
        sinceMs: Long? = null,
        sources: Set<String> = emptySet(),
        kinds: Set<String> = emptySet(),
    ): TimelinePage {
        val oldest = kept.firstOrNull()?.hostSeq ?: (hostSeq + 1)
        val (after, gap) = when {
            since == null -> 0L to null
            since.substringBefore(':') != session ->
                0L to "${DegradationCodes.UI_HISTORY_EVICTED}: the cursor belongs to an earlier timeline (DTA restarted); reading from the oldest kept event"
            else -> {
                val at = since.substringAfter(':').toLongOrNull() ?: 0L
                at to if (at < oldest - 1) "${DegradationCodes.UI_HISTORY_EVICTED}: ${oldest - 1 - at} events were dropped from this computer's timeline before this read" else null
            }
        }
        val matching = kept.filter { it.hostSeq > after && matches(it.event, sinceMs, sources, kinds) }
        val page = matching.take(limit)
        val events = buildList {
            gap?.let { add(synthetic(Kinds.GAP, clock(), it)) }
            page.forEach { add(it.event) }
        }
        val next = page.lastOrNull()?.hostSeq ?: maxOf(after, if (gap != null) oldest - 1 else after).coerceAtMost(hostSeq)
        return TimelinePage(
            events,
            "$session:${if (page.isEmpty() && matching.isEmpty()) hostSeq else next}",
            more = matching.size > page.size,
            sources = this.sources,
            bounds = TimelineBoundsInfo(hostBound = bound),
        )
    }

    private fun matches(e: TimelineEvent, sinceMs: Long?, sources: Set<String>, kinds: Set<String>): Boolean {
        if (sinceMs != null && e.wallMs < sinceMs) return false
        if (e.source == Sources.TIMELINE) return true
        if (sources.isNotEmpty() && e.source !in sources) return false
        return kinds.isEmpty() || e.kind in kinds
    }

    private fun append(event: TimelineEvent) {
        lastSeq = maxOf(lastSeq, event.seq)
        keep(event)
        facets.apply(event)
    }

    private fun keep(event: TimelineEvent) {
        kept.addLast(Kept(++hostSeq, event))
        while (kept.size > bound) kept.removeFirst()
    }

    private fun appendGap(count: Int, reason: String) {
        keep(synthetic(Kinds.GAP, clock(), reason).copy(gapCount = count))
    }

    private fun appendEnd(at: Long) {
        endedRecorded = true
        keep(synthetic(Kinds.PROCESS_ENDED, at, null))
        facets.processEnded(at)
    }

    private fun synthetic(kind: String, at: Long, reason: String?) = TimelineEvent(
        id = "host:${hostSeq + 1}",
        seq = 0,
        monoMs = 0,
        wallMs = at,
        source = Sources.TIMELINE,
        kind = kind,
        completeness = listOfNotNull(reason?.let { DegradationMarker("timeline", it) }),
    )

    companion object {
        const val BOUND = 10_000
    }
}
