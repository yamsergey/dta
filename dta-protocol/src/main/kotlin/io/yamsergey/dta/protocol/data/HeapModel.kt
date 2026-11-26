package io.yamsergey.dta.protocol.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Feature 074: heap snapshot by class, its comparison, instances and fields. Snapshots are taken on request only. */

@Serializable
enum class HeapOrigin {
    @SerialName("app") APP,
    @SerialName("library") LIBRARY,
    @SerialName("platform") PLATFORM,
    /** The app's namespaces are not known: never guessed. */
    @SerialName("unknown") UNKNOWN,
}

@Serializable
data class HeapClassRow(
    /** Binary name; arrays as `byte[]`. */
    val className: String,
    /** Set when names collide across class loaders. */
    val loader: String? = null,
    val origin: HeapOrigin,
    val count: Long,
    /** Shallow bytes. */
    val bytes: Long,
)

@Serializable
data class HeapTotals(val objects: Long, val bytes: Long)

@Serializable
data class HeapSnapshot(
    /** `s1`, `s2`… per run. */
    val id: String,
    /** Unique within the run. */
    val name: String? = null,
    val run: String,
    val takenAtMs: Long,
    /** Measured walk time. */
    val pauseMs: Long,
    val gcBefore: Boolean = false,
    /** All classes with at least one instance. */
    val classes: List<HeapClassRow>,
    val totals: HeapTotals,
    val notes: List<String> = emptyList(),
)

@Serializable
enum class HeapChange {
    @SerialName("appeared") APPEARED,
    @SerialName("gone") GONE,
    @SerialName("grew") GREW,
    @SerialName("shrank") SHRANK,
}

@Serializable
data class HeapComparisonRow(
    val className: String,
    val loader: String? = null,
    val origin: HeapOrigin,
    val countA: Long,
    val countB: Long,
    val bytesA: Long,
    val bytesB: Long,
    val change: HeapChange,
) {
    val deltaCount: Long get() = countB - countA
    val deltaBytes: Long get() = bytesB - bytesA
}

/** Filters of a comparison. [origin] null means all origins; the default is the app's own classes. */
@Serializable
data class HeapFilter(
    val origin: HeapOrigin? = HeapOrigin.APP,
    val packagePrefix: String? = null,
    val top: Int = 30,
)

/** What the filters left out, so the answer never cuts silently. */
@Serializable
data class HeapLeftOut(
    val byOrigin: Int = 0,
    val byPackage: Int = 0,
    val byTop: Int = 0,
    /** Sum of the bytes change of all left-out rows. */
    val bytesDelta: Long = 0,
) {
    val total: Int get() = byOrigin + byPackage + byTop

    fun describe(): String {
        if (total == 0) return "no changed class left out by the filters"
        val parts = buildList {
            if (byOrigin > 0) add("$byOrigin by origin")
            if (byPackage > 0) add("$byPackage by package")
            if (byTop > 0) add("$byTop by top")
        }
        return "$total changed classes left out (${parts.joinToString(", ")}), together ${if (bytesDelta >= 0) "+" else ""}$bytesDelta bytes"
    }
}

@Serializable
data class HeapComparison(
    val a: String,
    val b: String,
    val run: String,
    val rows: List<HeapComparisonRow>,
    val filters: HeapFilter,
    val leftOut: HeapLeftOut,
    /** Classes present in both with the same count and bytes. */
    val unchangedClasses: Int,
    val totalsDeltaObjects: Long,
    val totalsDeltaBytes: Long,
)

sealed class HeapCompareResult {
    data class Ok(val comparison: HeapComparison) : HeapCompareResult()
    data class Refused(val reason: String) : HeapCompareResult()
}

@Serializable
data class HeapLink(
    /** activity, viewmodel, navcontroller, di-binding. */
    val kind: String,
    val ref: String,
)

@Serializable
data class HeapInstance(
    /** JVMTI tag, valid while the object lives. */
    val id: Long,
    val className: String,
    val bytes: Long,
    val link: HeapLink? = null,
    /** Where DTA knows it, e.g. an activity that is destroyed. */
    val state: String? = null,
)

@Serializable
data class HeapField(
    val name: String,
    val declaredIn: String,
    val type: String,
    val static: Boolean = false,
    val value: JsonElement? = null,
    /** For strings and arrays when values were not asked for. */
    val length: Int? = null,
)

@Serializable
data class HeapFields(
    val instance: Long,
    val className: String,
    val fields: List<HeapField>,
    /** Agents: what the redactor did (feature 072), stated in the answer. */
    val redaction: String? = null,
)

/** What the sidekick answers to `runtime_heap_run`: the app run, and the namespaces the origin rule uses. */
@Serializable
data class HeapRunInfo(
    /** Identifies the app process: its pid and start time. Snapshots of two runs are never compared. */
    val run: String,
    val namespaces: List<String> = emptyList(),
)

/** What the sidekick answers to `runtime_heap_walk`: rows, or why there are none. Never a partial snapshot. */
@Serializable
data class HeapWalk(
    val run: String,
    val classes: List<HeapClassRow> = emptyList(),
    /** The walk's wall time (the app's threads were stopped that long). */
    val pauseMs: Long = 0,
    val gcBefore: Boolean = false,
    val notes: List<String> = emptyList(),
    /** The walk aborted itself at the pause bound: nothing was counted. */
    val stopped: Boolean = false,
    val maxPauseMs: Long = HeapLimits.DEFAULT_MAX_PAUSE_MS,
    /** Why there is no walk (agent not attached, not debuggable, runtime refused). */
    val refusal: String? = null,
)

object HeapLimits {
    const val DEFAULT_MAX_PAUSE_MS = 1_000L
    /** Above this the answer warns that the app was stopped that long. */
    const val WARN_PAUSE_MS = 100L
    const val SUMMARY_TOP = 20

    /** Said in every answer: what step 1 does not know. */
    val NOT_KNOWN = listOf(
        "Who keeps an object alive is not known (no reference paths); retained size is not known (shallow sizes only). A heap dump answers those, later.",
        "Counts are of objects present at the walk, reachable or not, unless a garbage collection ran first (gc).",
    )
}

@Serializable
data class HeapSnapshotSummary(
    val id: String,
    val name: String? = null,
    val run: String,
    val takenAtMs: Long,
    val pauseMs: Long,
    val gcBefore: Boolean,
    val totals: HeapTotals,
    val classCount: Int,
    /** The [HeapLimits.SUMMARY_TOP] classes with most bytes. */
    val top: List<HeapClassRow>,
    val notes: List<String> = emptyList(),
)

@Serializable
data class HeapSnapshotAnswer(
    val snapshot: HeapSnapshotSummary,
    /** Always stated. */
    val pauseMs: Long,
    /** Set above [HeapLimits.WARN_PAUSE_MS]. */
    val warning: String? = null,
    /** The unnamed snapshot dropped to make room, when one was. */
    val dropped: String? = null,
    val notKnown: List<String> = HeapLimits.NOT_KNOWN,
)

@Serializable
data class HeapSnapshotInfo(val id: String, val name: String? = null, val takenAtMs: Long, val pauseMs: Long, val objects: Long, val bytes: Long)

@Serializable
data class HeapDroppedInfo(val id: String, val name: String? = null)

@Serializable
data class HeapListAnswer(
    val run: String? = null,
    val snapshots: List<HeapSnapshotInfo> = emptyList(),
    val dropped: List<HeapDroppedInfo> = emptyList(),
    /** Set when the snapshots listed are of an earlier run than the app's current one. */
    val note: String? = null,
    val notKnown: List<String> = HeapLimits.NOT_KNOWN,
)

@Serializable
data class HeapCompareAnswer(
    val comparison: HeapComparison,
    /** What the filters left out, in words. */
    val leftOut: String,
    val notKnown: List<String> = HeapLimits.NOT_KNOWN,
)
