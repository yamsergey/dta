package io.yamsergey.dta.daemon.ui

import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.rpc.DegradationMarker
import io.yamsergey.dta.protocol.ui.UiCapability
import io.yamsergey.dta.protocol.ui.UiEvent
import io.yamsergey.dta.protocol.ui.UiEventKind
import io.yamsergey.dta.protocol.ui.UiEventsPage
import io.yamsergey.dta.protocol.ui.UiJournalRead
import io.yamsergey.dta.protocol.ui.UiSource

/**
 * One app's journal of short-lived UI on the host (feature 015, research R8): what the app's
 * sidekick recorded, caught up by seq and kept across reconnections and process deaths, within
 * [bound] events. Readers hold their own cursors (`<session>:<hostSeq>`); reading never
 * consumes. Losses — the app's ring overflowing, old events evicted here, a cursor from another
 * journal — are gaps in place (constitution III).
 */
class UiJournal(
    private val session: String,
    private val bound: Int = BOUND,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Kept(val hostSeq: Long, val event: UiEvent)

    private val kept = ArrayDeque<Kept>()
    private var hostSeq = 0L
    private var epoch: Long? = null
    private var lastSeq = 0L
    private var lostAt: Long? = null
    private var endedRecorded = false

    /** The capability the app reported last; null until it was read. */
    @Volatile
    var capability: UiCapability? = null

    /** The app process the journal follows (its sidekick's epoch), null before the first read. */
    @get:Synchronized
    val followedEpoch: Long? get() = epoch

    /** The app's seq to read after, for the epoch the journal follows (0 for a new one). */
    @get:Synchronized
    val afterSeq: Long get() = lastSeq

    /** Takes in a catch-up read from the app. */
    @Synchronized
    fun ingest(read: UiJournalRead) {
        capability = read.capability
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
    }

    /** Takes in a live event; false when it does not follow the last one (catch up instead). */
    @Synchronized
    fun pushed(event: UiEvent): Boolean {
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

    /** A window that is not the app's, seen by the host (US5); returns the event's id. */
    @Synchronized
    fun hostWindow(appeared: Boolean, window: io.yamsergey.dta.protocol.ui.WindowRef, at: Long, durationMs: Long?, appearedId: String?): String {
        val event = synthetic(if (appeared) UiEventKind.WINDOW_APPEARED else UiEventKind.WINDOW_DISAPPEARED, at, null)
            .copy(source = UiSource.SCREEN_WINDOW, window = window, durationMs = durationMs, appearedId = appearedId)
        keep(event)
        return event.id
    }

    /** A cursor after everything kept: what an agent reads from on its first call. */
    @Synchronized
    fun end(): String = "$session:$hostSeq"

    @Synchronized
    fun page(since: String?, limit: Int, sinceMs: Long? = null, kinds: Set<String> = emptySet()): UiEventsPage {
        val oldest = kept.firstOrNull()?.hostSeq ?: (hostSeq + 1)
        val (after, gap) = when {
            since == null -> 0L to null
            since.substringBefore(':') != session ->
                0L to "${DegradationCodes.UI_HISTORY_EVICTED}: the cursor belongs to an earlier journal (DTA restarted); reading from the oldest kept event"
            else -> {
                val at = since.substringAfter(':').toLongOrNull() ?: 0L
                at to if (at < oldest - 1) "${DegradationCodes.UI_HISTORY_EVICTED}: ${oldest - 1 - at} events were dropped from this computer's journal before this read" else null
            }
        }
        val matching = kept.filter { it.hostSeq > after && matches(it.event, sinceMs, kinds) }
        val page = matching.take(limit)
        val events = buildList {
            gap?.let { add(synthetic(UiEventKind.GAP, clock(), it)) }
            page.forEach { add(it.event) }
        }
        val next = page.lastOrNull()?.hostSeq ?: maxOf(after, if (gap != null) oldest - 1 else after).coerceAtMost(hostSeq)
        return UiEventsPage(events, "$session:${if (page.isEmpty() && matching.isEmpty()) hostSeq else next}", more = matching.size > page.size, capability = capability ?: UiCapability())
    }

    private fun matches(e: UiEvent, sinceMs: Long?, kinds: Set<String>): Boolean {
        if (sinceMs != null && e.wallMs < sinceMs) return false
        if (kinds.isEmpty() || e.kind == UiEventKind.GAP) return true
        return kindName(e) in kinds
    }

    private fun kindName(e: UiEvent) = when {
        e.kind == UiEventKind.PROCESS_ENDED -> "process"
        e.source == UiSource.WINDOW -> "window"
        e.source == UiSource.TOAST -> "toast"
        e.source == UiSource.ELEMENT -> "element"
        e.source == UiSource.SCREEN_WINDOW -> "screen-window"
        else -> "other"
    }

    private fun append(event: UiEvent) {
        lastSeq = maxOf(lastSeq, event.seq)
        keep(event)
    }

    private fun keep(event: UiEvent) {
        kept.addLast(Kept(++hostSeq, event))
        while (kept.size > bound) kept.removeFirst()
    }

    private fun appendGap(count: Int, reason: String) {
        keep(synthetic(UiEventKind.GAP, clock(), reason).copy(gapCount = count))
    }

    private fun appendEnd(at: Long) {
        endedRecorded = true
        keep(synthetic(UiEventKind.PROCESS_ENDED, at, null))
    }

    private fun synthetic(kind: UiEventKind, at: Long, reason: String?) = UiEvent(
        id = "host:${hostSeq + 1}",
        seq = 0,
        monoMs = 0,
        wallMs = at,
        kind = kind,
        source = UiSource.JOURNAL,
        completeness = listOfNotNull(reason?.let { DegradationMarker("ui", it) }),
    )

    companion object {
        const val BOUND = 5_000
    }
}
