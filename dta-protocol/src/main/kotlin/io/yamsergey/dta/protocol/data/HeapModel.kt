@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.yamsergey.dta.protocol.data

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
    @EncodeDefault val gcBefore: Boolean = false,
    /** All classes with at least one instance. */
    val classes: List<HeapClassRow>,
    val totals: HeapTotals,
    @EncodeDefault val notes: List<String> = emptyList(),
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

/** Which classes a comparison shows; [ALL] is every origin. */
@Serializable
enum class HeapOriginFilter(val origin: HeapOrigin?) {
    @SerialName("app") APP(HeapOrigin.APP),
    @SerialName("library") LIBRARY(HeapOrigin.LIBRARY),
    @SerialName("platform") PLATFORM(HeapOrigin.PLATFORM),
    @SerialName("all") ALL(null),
}

/** Filters of a comparison; the default is the app's own classes. */
@Serializable
data class HeapFilter(
    @EncodeDefault val origin: HeapOriginFilter = HeapOriginFilter.APP,
    val packagePrefix: String? = null,
    @EncodeDefault val top: Int = 30,
)

/** What the filters left out, so the answer never cuts silently. */
@Serializable
data class HeapLeftOut(
    @EncodeDefault val byOrigin: Int = 0,
    @EncodeDefault val byPackage: Int = 0,
    @EncodeDefault val byTop: Int = 0,
    /** Sum of the bytes change of all left-out rows. */
    @EncodeDefault val bytesDelta: Long = 0,
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
    @EncodeDefault val static: Boolean = false,
    val value: JsonElement? = null,
    /** For strings and arrays when values were not asked for. */
    val length: Int? = null,
)

@Serializable
data class HeapFields(
    val instance: Long,
    val className: String,
    val fields: List<HeapField>,
    /** What the read left out (long collections, deep objects). */
    @EncodeDefault val notes: List<String> = emptyList(),
)

/** What the sidekick answers to `runtime_heap_instances`. */
@Serializable
data class HeapInstancesPage(
    val className: String,
    val total: Int,
    val offset: Int,
    val instances: List<HeapInstance>,
    val refusal: String? = null,
)

/** What the sidekick answers to `runtime_heap_fields`: the fields, or why not. */
@Serializable
data class HeapFieldsRead(val fields: HeapFields? = null, val refusal: String? = null)

@Serializable
data class HeapInstancesAnswer(
    val className: String,
    val total: Int,
    val offset: Int,
    val instances: List<HeapInstance>,
    /** Said every time: live objects at call time, not at any snapshot. */
    val note: String,
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault val notKnown: List<String> = HeapLimits.NOT_KNOWN,
)

@Serializable
data class HeapFieldsAnswer(
    val fields: HeapFields,
    /** False: names, types and lengths only. */
    val valuesShown: Boolean,
    val note: String,
    /** Feature 072: for an agent, what was masked (or that nothing needed it); the daemon adds `forAgents` as for the other answers. */
    val redaction: io.yamsergey.dta.protocol.network.RedactionInfo? = null,
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault val notKnown: List<String> = HeapLimits.NOT_KNOWN,
)

/** What the sidekick answers to `runtime_heap_run`: the app run, and the namespaces the origin rule uses. */
@Serializable
data class HeapRunInfo(
    /** Identifies the app process: its pid and start time. Snapshots of two runs are never compared. */
    val run: String,
    @EncodeDefault val namespaces: List<String> = emptyList(),
)

/** What the sidekick answers to `runtime_heap_walk`: rows, or why there are none. Never a partial snapshot. */
@Serializable
data class HeapWalk(
    val run: String,
    @EncodeDefault val classes: List<HeapClassRow> = emptyList(),
    /** The walk's wall time (the app's threads were stopped that long). */
    @EncodeDefault val pauseMs: Long = 0,
    @EncodeDefault val gcBefore: Boolean = false,
    @EncodeDefault val notes: List<String> = emptyList(),
    /** The walk aborted itself at the pause bound: nothing was counted. */
    @EncodeDefault val stopped: Boolean = false,
    @EncodeDefault val maxPauseMs: Long = HeapLimits.DEFAULT_MAX_PAUSE_MS,
    /** Why there is no walk (agent not attached, not debuggable, runtime refused). */
    val refusal: String? = null,
)

object HeapLimits {
    const val DEFAULT_MAX_PAUSE_MS = 1_000L
    /** Above this the answer warns that the app was stopped that long. */
    const val WARN_PAUSE_MS = 100L
    const val SUMMARY_TOP = 20

    /** `3s`, `500ms`, `1.5s`, `1m` or plain milliseconds; positive. */
    fun parseDurationMs(text: String): Long {
        val m = Regex("""\s*(\d+(?:\.\d+)?)\s*(ms|s|m)?\s*""").matchEntire(text)
            ?: throw IllegalArgumentException("not a duration: '$text' (use 500ms, 3s, 1m or milliseconds)")
        val n = m.groupValues[1].toDouble()
        val ms = Math.round(n * when (m.groupValues[2]) { "s" -> 1000.0; "m" -> 60_000.0; else -> 1.0 })
        require(ms > 0) { "a duration must be positive: '$text'" }
        return ms
    }

    /** The daemon waits this much beyond the pause bound for the walk's answer (GC, row assembly, transport). */
    const val REQUEST_MARGIN_MS = 10_000L

    /** Said in every answer: what step 1 does not know. */
    val NOT_KNOWN = listOf(
        "Who keeps an object alive is not known (no reference paths); retained size is not known (shallow sizes only). A heap dump answers those, later.",
        "The walk counts every object in the process, boot image and zygote included, so platform rows include objects the app did not allocate (the default view shows the app's own classes).",
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
    @EncodeDefault val notes: List<String> = emptyList(),
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
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault val notKnown: List<String> = HeapLimits.NOT_KNOWN,
)

@Serializable
data class HeapSnapshotInfo(val id: String, val name: String? = null, val takenAtMs: Long, val pauseMs: Long, val objects: Long, val bytes: Long, @EncodeDefault val gcBefore: Boolean = false)

@Serializable
data class HeapDroppedInfo(val id: String, val name: String? = null)

@Serializable
data class HeapListAnswer(
    val run: String? = null,
    @EncodeDefault val snapshots: List<HeapSnapshotInfo> = emptyList(),
    @EncodeDefault val dropped: List<HeapDroppedInfo> = emptyList(),
    /** Set when the snapshots listed are of an earlier run than the app's current one. */
    val note: String? = null,
    /** The run the app is in now, when the daemon could ask; differs from [run] after the app's process was replaced. */
    val currentRun: String? = null,
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault val notKnown: List<String> = HeapLimits.NOT_KNOWN,
)

@Serializable
data class HeapCompareAnswer(
    val comparison: HeapComparison,
    /** What the filters left out, in words. */
    val leftOut: String,
    /** The notes of snapshot b (pause, boot image, "N objects of DTA itself left out"): what the rows do not show. */
    @EncodeDefault val notes: List<String> = emptyList(),
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault val notKnown: List<String> = HeapLimits.NOT_KNOWN,
)
