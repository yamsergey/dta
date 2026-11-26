package io.yamsergey.dta.daemon.runtime

import io.yamsergey.dta.protocol.data.HeapClassRow
import io.yamsergey.dta.protocol.data.HeapCompareResult
import io.yamsergey.dta.protocol.data.HeapFilter
import io.yamsergey.dta.protocol.data.HeapSnapshot
import io.yamsergey.dta.protocol.data.HeapTotals
import io.yamsergey.dta.protocol.data.compareHeap

/**
 * Feature 074 (A2): heap snapshots kept in the daemon, per app and app run, in memory only.
 *
 * - at most [maxSnapshots] per run; named ones are kept over unnamed, the oldest unnamed is dropped and the result
 *   says which; when all are named the next snapshot is refused with the reason
 * - names unique within the run
 * - at most one walk in flight per app, and one walk start per [minIntervalMs] per app
 * - a new run replaces the earlier run's snapshots when its first snapshot is stored; until then the earlier run's
 *   snapshots stay listable (for that run); snapshots of different runs are never compared
 */
class HeapSnapshotStore(
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxSnapshots: Int = 20,
    private val minIntervalMs: Long = 2_000,
    /** The zone refusal times are shown in. */
    private val zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
) {
    data class Ticket internal constructor(val app: String, val run: String, val name: String?, internal val id: Long)

    sealed class Begin {
        data class Allowed(val ticket: Ticket) : Begin()
        data class Refused(val reason: String, val nextAllowedAtMs: Long? = null) : Begin()
    }

    data class SnapshotInfo(val id: String, val name: String?, val takenAtMs: Long, val pauseMs: Long, val objects: Long, val bytes: Long, val gcBefore: Boolean = false)
    data class DroppedInfo(val id: String, val name: String?)
    data class Listing(val run: String, val currentRun: String?, val snapshots: List<SnapshotInfo>, val dropped: List<DroppedInfo>)
    data class Completed(val snapshot: HeapSnapshot, val dropped: DroppedInfo?)

    sealed class Completion {
        data class Stored(val result: Completed) : Completion()
        data class Refused(val reason: String) : Completion()
    }

    private class AppState {
        var run: String? = null
        var knownRun: String? = null
        var nextSeq = 1
        val snapshots = ArrayList<HeapSnapshot>()
        val dropped = ArrayList<DroppedInfo>()
        var inFlight: Long? = null
        var lastStartMs: Long? = null
    }

    private val apps = HashMap<String, AppState>()
    private var ticketSeq = 0L

    /** The app is running in [run] now; earlier snapshots stay listable until the first snapshot of [run] is stored. */
    @Synchronized fun runStarted(app: String, run: String) {
        apps.getOrPut(app) { AppState() }.knownRun = run
    }

    /** Checks everything that can be refused before the walk: walk in flight, rate limit, name, capacity. */
    @Synchronized fun begin(app: String, run: String, name: String?): Begin {
        val s = apps.getOrPut(app) { AppState() }
        val now = clock()
        s.knownRun = run
        if (s.inFlight != null) return Begin.Refused("a heap walk is already in progress for $app; wait for it to finish")
        s.lastStartMs?.let { last ->
            val next = last + minIntervalMs
            if (now < next) {
                return Begin.Refused(
                    "too soon: one heap walk per ${minIntervalMs} ms per app; next allowed at ${clock(next)}, in ${"%.1f".format(java.util.Locale.ROOT, (next - now) / 1000.0)} s",
                    nextAllowedAtMs = next,
                )
            }
        }
        if (s.run == run) {
            if (name != null && s.snapshots.any { it.name == name }) {
                return Begin.Refused("a snapshot named '$name' already exists in this run; names are unique")
            }
            if (s.snapshots.size >= maxSnapshots && s.snapshots.all { it.name != null }) {
                return Begin.Refused(
                    "all $maxSnapshots snapshots of this run are named and kept; nothing can be dropped to make room — remove one first",
                )
            }
        }
        val id = ++ticketSeq
        s.inFlight = id
        s.lastStartMs = now
        return Begin.Allowed(Ticket(app, run, name, id))
    }

    /** The walk failed or was stopped: nothing is stored; the rate limit still counts the start. */
    @Synchronized fun abort(ticket: Ticket) {
        val s = apps[ticket.app] ?: return
        if (s.inFlight == ticket.id) s.inFlight = null
    }

    @Synchronized fun complete(
        ticket: Ticket,
        pauseMs: Long,
        gcBefore: Boolean,
        classes: List<HeapClassRow>,
        notes: List<String>,
    ): Completion {
        val s = apps[ticket.app] ?: return Completion.Refused("no walk in flight for ${ticket.app}")
        if (s.inFlight != ticket.id) return Completion.Refused("this walk is no longer the one in flight for ${ticket.app}")
        s.inFlight = null
        if (s.run != ticket.run) {
            s.run = ticket.run
            s.snapshots.clear()
            s.dropped.clear()
            s.nextSeq = 1
        }
        var dropped: DroppedInfo? = null
        if (s.snapshots.size >= maxSnapshots) {
            val victim = s.snapshots.firstOrNull { it.name == null }
                ?: return Completion.Refused("all $maxSnapshots snapshots of this run are named and kept")
            s.snapshots.remove(victim)
            dropped = DroppedInfo(victim.id, victim.name)
            s.dropped += dropped
        }
        val snapshot = HeapSnapshot(
            id = "s${s.nextSeq++}",
            name = ticket.name,
            run = ticket.run,
            takenAtMs = clock(),
            pauseMs = pauseMs,
            gcBefore = gcBefore,
            classes = classes,
            totals = HeapTotals(classes.sumOf { it.count }, classes.sumOf { it.bytes }),
            notes = notes,
        )
        s.snapshots += snapshot
        return Completion.Stored(Completed(snapshot, dropped))
    }

    @Synchronized fun list(app: String): Listing? {
        val s = apps[app] ?: return null
        val run = s.run ?: return null
        return Listing(
            run = run,
            currentRun = s.knownRun,
            snapshots = s.snapshots.map { SnapshotInfo(it.id, it.name, it.takenAtMs, it.pauseMs, it.totals.objects, it.totals.bytes, it.gcBefore) },
            dropped = s.dropped.toList(),
        )
    }

    /** By id (`s3`) or by name. */
    @Synchronized fun get(app: String, idOrName: String): HeapSnapshot? {
        val s = apps[app] ?: return null
        return s.snapshots.firstOrNull { it.id == idOrName } ?: s.snapshots.firstOrNull { it.name == idOrName }
    }

    @Synchronized fun compare(app: String, a: String, b: String, filter: HeapFilter = HeapFilter()): HeapCompareResult {
        val s = apps[app]
        if (s == null || s.run == null) return HeapCompareResult.Refused("no snapshot has been taken for $app; take one first")
        val sa = get(app, a) ?: if (a == EMPTY) emptyOf(s) else return HeapCompareResult.Refused(unknown(s, a))
        val sb = get(app, b) ?: return HeapCompareResult.Refused(unknown(s, b))
        return compareHeap(sa, sb, filter)
    }

    private fun clock(ms: Long): String = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(zone).format(java.time.Instant.ofEpochMilli(ms))

    /** `empty` as the earlier side: no classes, so the later snapshot's classes all appear (a histogram with the comparison's filters). */
    private fun emptyOf(s: AppState) = HeapSnapshot(id = EMPTY, run = s.run!!, takenAtMs = 0, pauseMs = 0, classes = emptyList(), totals = HeapTotals(0, 0))

    private fun unknown(s: AppState, ref: String): String {
        val dropped = s.dropped.firstOrNull { it.id == ref || it.name == ref }
        return if (dropped != null) "snapshot '$ref' was dropped to stay within ${maxSnapshots} snapshots; name snapshots to keep them"
        else "no snapshot '$ref' in run ${s.run}; list them to see the ids and names"
    }

    companion object {
        /** Not a snapshot: the key the plugin's histogram uses; a snapshot with this name is found first. */
        const val EMPTY = "empty"
    }
}
