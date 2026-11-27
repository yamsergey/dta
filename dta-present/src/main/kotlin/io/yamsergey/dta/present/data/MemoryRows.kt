package io.yamsergey.dta.present.data

import io.yamsergey.dta.present.Units
import io.yamsergey.dta.protocol.data.HeapComparison
import io.yamsergey.dta.protocol.data.HeapInstancesAnswer
import io.yamsergey.dta.protocol.data.HeapLimits
import io.yamsergey.dta.protocol.data.HeapSnapshotInfo

/**
 * Feature 074 (D1): what Runtime › Memory shows, as data the panel only lays out (boards Memory, MemoryNarrow,
 * MemoryEmpty): columns and rows of a comparison or a histogram, the picker labels, the statements that sit next to
 * what they are about (pause, boot image, what the filters left out) and the instances with their links into the
 * Runtime views.
 */
object MemoryRows {
    const val EMPTY = "No snapshot in this run. Take one to see what is in memory by class; take another after an action to see what grew."
    const val NOT_KNOWN = "A snapshot does not say who keeps an object alive or how much it holds; that needs a heap dump (later)."

    data class Row(val cells: List<String>, val className: String, val grew: Boolean)

    /** Which Runtime view a heap object links to. */
    enum class Target(val section: String) { ACTIVITIES("Activities"), NAVIGATION("Navigation"), VIEWMODELS("ViewModels"), DI("DI") }

    data class InstanceRow(val label: String, val state: String, val linkText: String?, val target: Target?, val ref: String?, val id: Long)

    /**
     * [name] in at most [max] characters, elided in the middle so the simple name stays: `io.yamsergey…OrderActivity`
     * (`…OrderActivity` when even the start of the package does not fit; a simple name too long for [max] is cut at its end).
     */
    fun elide(name: String, max: Int): String {
        if (name.length <= max) return name
        val simple = name.substringAfterLast('.')
        val pkg = name.substringBeforeLast('.', "")
        if (pkg.isEmpty()) return name.take((max - 1).coerceAtLeast(1)) + "…"
        if (simple.length >= max) return simple.take((max - 1).coerceAtLeast(1)) + "…"
        val keep = max - simple.length - 1
        return (if (keep > 0) pkg.take(keep) else "") + "…" + simple
    }

    fun columns(narrow: Boolean, histogram: Boolean): List<String> = when {
        histogram -> listOf("Class", "Count", "Size")
        narrow -> listOf("Class", "Δ count", "Δ size")
        else -> listOf("Class", "A", "B", "Δ count", "Δ size")
    }

    private fun signed(n: Long) = if (n > 0) "+$n" else "$n"
    private fun signedBytes(n: Long) = if (n > 0) "+" + Units.bytes(n) else if (n < 0) "-" + Units.bytes(-n) else "0 B"

    fun rows(c: HeapComparison, narrow: Boolean, histogram: Boolean): List<Row> = c.rows.map { r ->
        val cells = when {
            histogram -> listOf(r.className, r.countB.toString(), Units.bytes(r.bytesB))
            narrow -> listOf(r.className, signed(r.deltaCount), signedBytes(r.deltaBytes))
            else -> listOf(r.className, r.countA.toString(), r.countB.toString(), signed(r.deltaCount), signedBytes(r.deltaBytes))
        }
        Row(cells, r.className, r.deltaBytes > 0)
    }

    /** The snapshots of the run the app is in; none when the listed ones are of an earlier run (a new process). */
    fun currentSnapshots(l: io.yamsergey.dta.protocol.data.HeapListAnswer): List<HeapSnapshotInfo> =
        if (l.currentRun != null && l.currentRun != l.run) emptyList() else l.snapshots

    /** What stands in the empty state when snapshots of an earlier run are still held; null otherwise. */
    fun earlierRunLine(l: io.yamsergey.dta.protocol.data.HeapListAnswer): String? =
        if (l.currentRun != null && l.currentRun != l.run && l.snapshots.isNotEmpty())
            "${l.snapshots.size} snapshot${if (l.snapshots.size == 1) "" else "s"} of the previous run (${l.run}); this run has none yet" else null

    fun pickerLabel(i: HeapSnapshotInfo, time: (Long) -> String): String = listOfNotNull(i.id, i.name, time(i.takenAtMs)).joinToString(" · ")

    /** The pause of [side] (A or B) stated beside its numbers; the warning above 100 ms; the boot image when platform rows are shown. */
    fun statement(side: String, s: HeapSnapshotInfo, allOrigins: Boolean): String = buildString {
        append("$side paused the app ${s.pauseMs} ms")
        if (s.pauseMs > HeapLimits.WARN_PAUSE_MS) append(" (longer than ${HeapLimits.WARN_PAUSE_MS} ms: the app was stopped that long)")
        append(" · ")
        append(if (s.gcBefore) "after a garbage collection" else "no garbage collection before $side: objects no longer reachable may still be counted")
        if (allOrigins) append(" · platform rows include boot image and zygote objects the app did not allocate")
    }

    /** What the origin and package filters and the row limit left out; null when nothing. */
    fun leftOut(c: HeapComparison): String? = c.leftOut.takeIf { it.total > 0 }?.describe()

    fun instanceHeader(a: HeapInstancesAnswer): String = "${a.className.substringAfterLast('.')} · ${a.total} alive now"

    fun instanceSummary(a: HeapInstancesAnswer): String {
        val states = a.instances.mapNotNull { it.state }.groupingBy { it }.eachCount().entries.joinToString(", ") { "${it.value} ${it.key}" }
        return (if (states.isEmpty()) "" else "$states; ") + "listed now, not as they were in a snapshot"
    }

    fun instanceRows(a: HeapInstancesAnswer): List<InstanceRow> = a.instances.map { i ->
        val target = when (i.link?.kind) {
            "activity" -> Target.ACTIVITIES
            "viewmodel" -> Target.VIEWMODELS
            "navcontroller" -> Target.NAVIGATION
            "di-binding" -> Target.DI
            else -> null
        }
        InstanceRow("@" + java.lang.Long.toHexString(i.id), i.state ?: "", target?.let { "${it.section} ›" }, target, i.link?.ref, i.id)
    }

    /** A failure as the user reads it: the daemon's reason as it is (too soon with when, stopped with how to raise the bound, not debuggable). */
    fun refusal(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
}
