package io.yamsergey.dta.daemon.runtime

import io.yamsergey.dta.protocol.data.HeapCompareAnswer
import io.yamsergey.dta.protocol.data.HeapCompareResult
import io.yamsergey.dta.protocol.data.HeapDroppedInfo
import io.yamsergey.dta.protocol.data.HeapFilter
import io.yamsergey.dta.protocol.data.HeapLimits
import io.yamsergey.dta.protocol.data.HeapListAnswer
import io.yamsergey.dta.protocol.data.HeapOriginFilter
import io.yamsergey.dta.protocol.data.HeapRunInfo
import io.yamsergey.dta.protocol.data.HeapSnapshotAnswer
import io.yamsergey.dta.protocol.data.HeapSnapshotInfo
import io.yamsergey.dta.protocol.data.HeapSnapshotSummary
import io.yamsergey.dta.protocol.data.HeapFieldsAnswer
import io.yamsergey.dta.protocol.data.HeapFieldsRead
import io.yamsergey.dta.protocol.data.HeapInstancesAnswer
import io.yamsergey.dta.protocol.data.HeapInstancesPage
import io.yamsergey.dta.protocol.data.HeapRedaction
import io.yamsergey.dta.protocol.data.HeapWalk
import io.yamsergey.dta.protocol.network.RedactionInfo
import kotlin.time.Duration.Companion.milliseconds

/** The sidekick's two heap methods, as the service needs them. */
interface HeapSidekick {
    suspend fun run(): HeapRunInfo
    suspend fun instances(className: String, offset: Int, limit: Int): HeapInstancesPage
    suspend fun fields(instance: Long, values: Boolean): HeapFieldsRead
    suspend fun walk(maxPauseMs: Long, gc: Boolean, timeout: kotlin.time.Duration): HeapWalk
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
            val bound = maxPauseMs ?: HeapLimits.DEFAULT_MAX_PAUSE_MS
            sidekick.walk(bound, gc, (bound + HeapLimits.REQUEST_MARGIN_MS).milliseconds)
        } catch (e: Throwable) {
            store.abort(ticket)
            throw e
        }
        if (walk.refusal != null) { store.abort(ticket); throw HeapRefusal(walk.refusal!!) }
        if (walk.stopped) {
            store.abort(ticket)
            throw HeapRefusal(
                "the heap walk stopped itself after ${walk.pauseMs} ms, at the bound of ${walk.maxPauseMs} ms; nothing was kept " +
                    "(the app's threads were stopped about that long). Raise the bound with maxPause (e.g. 3s) to allow a longer pause.",
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
        val o = if (origin == null) HeapOriginFilter.APP else HeapOriginFilter.entries.firstOrNull { it.name.equals(origin, ignoreCase = true) }
            ?: throw IllegalArgumentException("origin is app, library, platform or all; got '$origin'")
        require(top == null || top >= 0) { "top must not be negative" }
        val filter = HeapFilter(o, packagePrefix?.takeIf { it.isNotBlank() }, top ?: 30)
        return when (val r = store.compare(app, a, b, filter)) {
            is HeapCompareResult.Ok -> HeapCompareAnswer(r.comparison, r.comparison.leftOut.describe())
            is HeapCompareResult.Refused -> throw HeapRefusal(r.reason)
        }
    }

    /** Live instances of [className] at this call; never takes a snapshot, never needs one. */
    suspend fun instances(app: String, sidekick: HeapSidekick, className: String, offset: Int?, limit: Int?): HeapInstancesAnswer {
        require(className.isNotBlank()) { "give a class name" }
        require(offset == null || offset >= 0) { "offset must not be negative" }
        require(limit == null || limit in 1..1000) { "limit is 1 to 1000" }
        val page = sidekick.instances(className, offset ?: 0, limit ?: 50)
        page.refusal?.let { throw HeapRefusal(it) }
        return HeapInstancesAnswer(
            page.className, page.total, page.offset, page.instances,
            "These are the objects alive now, not as they were at any snapshot; an id lasts while its object lives.",
        )
    }

    /** One instance's fields; values only when asked, masked by value when [redact] (the agents' setting, decided by the connection). */
    suspend fun fields(app: String, sidekick: HeapSidekick, instance: Long, values: Boolean, redact: Boolean): HeapFieldsAnswer {
        val read = sidekick.fields(instance, values)
        read.refusal?.let { throw HeapRefusal(it) }
        var f = read.fields ?: throw HeapRefusal("the app returned no fields for instance $instance")
        var info: RedactionInfo? = null
        if (redact) {
            if (values) HeapRedaction.apply(f).let { (masked, i) -> f = masked; info = i } else info = RedactionInfo()
        }
        val note = when {
            !values -> "Field values are not shown unless asked for (values); names, types and the lengths of strings and arrays are."
            redact -> "Values are redacted for agents: every string and primitive is masked as [redacted len=N #fingerprint]; names, types, lengths and nulls are shown as they are."
            else -> "Values are shown as they are in the app."
        }
        return HeapFieldsAnswer(f, values, note, info)
    }
}
