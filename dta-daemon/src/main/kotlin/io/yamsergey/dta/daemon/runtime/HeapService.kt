package io.yamsergey.dta.daemon.runtime

import io.yamsergey.dta.protocol.data.HeapCompareAnswer
import io.yamsergey.dta.protocol.data.HeapCompareResult
import io.yamsergey.dta.protocol.data.HeapDroppedInfo
import io.yamsergey.dta.protocol.data.HeapFilter
import io.yamsergey.dta.protocol.data.HeapLimits
import io.yamsergey.dta.protocol.data.HeapListAnswer
import io.yamsergey.dta.protocol.data.HeapOrigin
import io.yamsergey.dta.protocol.data.HeapRunInfo
import io.yamsergey.dta.protocol.data.HeapSnapshotAnswer
import io.yamsergey.dta.protocol.data.HeapSnapshotInfo
import io.yamsergey.dta.protocol.data.HeapSnapshotSummary
import io.yamsergey.dta.protocol.data.HeapWalk

/** The sidekick's two heap methods, as the service needs them. */
interface HeapSidekick {
    suspend fun run(): HeapRunInfo
    suspend fun walk(maxPauseMs: Long, gc: Boolean): HeapWalk
}

/** A refusal with its reason (the client sees it as the operation's error). */
class HeapRefusal(message: String) : Exception(message)

/**
 * Feature 074 (C1): `runtime.heap.*` over the [HeapSnapshotStore] and the sidekick. Only [snapshot] asks the
 * sidekick for a walk; [list] and [compare] never do (FR-000: nothing takes a snapshot as a side effect).
 */
class HeapService(private val store: HeapSnapshotStore = HeapSnapshotStore()) {

    suspend fun snapshot(app: String, sidekick: HeapSidekick, name: String?, gc: Boolean, maxPauseMs: Long?): HeapSnapshotAnswer {
        require(name == null || name.isNotBlank()) { "name must not be blank" }
        require(maxPauseMs == null || maxPauseMs > 0) { "maxPauseMs must be positive" }
        val run = sidekick.run().run
        val ticket = when (val b = store.begin(app, run, name)) {
            is HeapSnapshotStore.Begin.Allowed -> b.ticket
            is HeapSnapshotStore.Begin.Refused -> throw HeapRefusal(b.reason)
        }
        val walk = try {
            sidekick.walk(maxPauseMs ?: HeapLimits.DEFAULT_MAX_PAUSE_MS, gc)
        } catch (e: Throwable) {
            store.abort(ticket)
            throw e
        }
        if (walk.refusal != null) { store.abort(ticket); throw HeapRefusal(walk.refusal!!) }
        if (walk.stopped) {
            store.abort(ticket)
            throw HeapRefusal(
                "the heap walk stopped itself after ${walk.pauseMs} ms, at the bound of ${walk.maxPauseMs} ms; nothing was kept " +
                    "(the app's threads were stopped about that long). Raise the bound with maxPauseMs to allow a longer pause.",
            )
        }
        if (walk.run != run) { store.abort(ticket); throw HeapRefusal("the app restarted during the walk (run ${walk.run}, not $run); take the snapshot again") }
        val stored = when (val c = store.complete(ticket, walk.pauseMs, walk.gcBefore, walk.classes, walk.notes)) {
            is HeapSnapshotStore.Completion.Stored -> c.result
            is HeapSnapshotStore.Completion.Refused -> throw HeapRefusal(c.reason)
        }
        val s = stored.snapshot
        return HeapSnapshotAnswer(
            snapshot = HeapSnapshotSummary(
                id = s.id, name = s.name, run = s.run, takenAtMs = s.takenAtMs, pauseMs = s.pauseMs, gcBefore = s.gcBefore,
                totals = s.totals, classCount = s.classes.size,
                top = s.classes.sortedWith(compareByDescending<io.yamsergey.dta.protocol.data.HeapClassRow> { it.bytes }.thenBy { it.className }).take(HeapLimits.SUMMARY_TOP),
                notes = s.notes,
            ),
            pauseMs = s.pauseMs,
            warning = if (s.pauseMs > HeapLimits.WARN_PAUSE_MS) "The app's threads were stopped for ${s.pauseMs} ms by this walk." else null,
            dropped = stored.dropped?.let { "Snapshot ${it.id} was dropped to stay within 20 snapshots (the oldest unnamed one); name snapshots to keep them." },
        )
    }

    fun list(app: String): HeapListAnswer {
        val l = store.list(app) ?: return HeapListAnswer(note = "No snapshot has been taken for this app; nothing takes one unless asked.")
        return HeapListAnswer(
            run = l.run,
            snapshots = l.snapshots.map { HeapSnapshotInfo(it.id, it.name, it.takenAtMs, it.pauseMs, it.objects, it.bytes) },
            dropped = l.dropped.map { HeapDroppedInfo(it.id, it.name) },
            note = if (l.currentRun != null && l.currentRun != l.run) "These snapshots are of an earlier run of the app (${l.run}); it runs as ${l.currentRun} now, and they cannot be compared with new ones." else null,
        )
    }

    fun compare(app: String, a: String, b: String, origin: String?, packagePrefix: String?, top: Int?): HeapCompareAnswer {
        val o: HeapOrigin? = when (origin) {
            null -> HeapOrigin.APP
            "all" -> null
            else -> HeapOrigin.entries.firstOrNull { it.name.equals(origin, ignoreCase = true) && it != HeapOrigin.UNKNOWN }
                ?: throw IllegalArgumentException("origin is app, library, platform or all; got '$origin'")
        }
        require(top == null || top >= 0) { "top must not be negative" }
        val filter = HeapFilter(o, packagePrefix?.takeIf { it.isNotBlank() }, top ?: 30)
        return when (val r = store.compare(app, a, b, filter)) {
            is HeapCompareResult.Ok -> HeapCompareAnswer(r.comparison, r.comparison.leftOut.describe())
            is HeapCompareResult.Refused -> throw HeapRefusal(r.reason)
        }
    }
}
