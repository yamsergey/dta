package io.yamsergey.dta.protocol.timeline

import kotlinx.serialization.Serializable

/**
 * Feature 048: the timeline as one flat sequence diagram. A step is a row of it, in time order;
 * nothing is grouped by any rule (replaces 045's episodes). Feature 049: named flows highlight it.
 */
object SequenceLanes {
    const val USER = "user"
    const val APP = "app"
    const val OTHER = "other"
    const val SOCKET = "socket"

    fun host(name: String) = "host:$name"
}

/** A column of the diagram, in drawing order. */
@Serializable
data class SequenceLane(val id: String, val title: String)

@Serializable
data class SequenceStep(
    val wallMs: Long,
    /** The lane it starts from; a step with no [to] is a note on this lane. */
    val from: String,
    val to: String? = null,
    val label: String,
    /** A return: a response, a message received. */
    val back: Boolean = false,
    /** It has an exact or declared cause among the kept events. Never set from timing. */
    val linked: Boolean = false,
    val failed: Boolean = false,
    val eventId: String? = null,
    /** The Network tab's entry. */
    val entryId: String? = null,
    /** The longer text for a hover: full names, what caused it. */
    val detail: String? = null,
    /** Every search word of the query is in it. */
    val hit: Boolean = false,
    /** A process start or end, or a gap: drawn so that it cannot be missed. */
    val boundary: Boolean = false,
    /** Feature 049: the flows whose interval holds it, by time only. */
    val flows: List<String> = emptyList(),
    /** Feature 064: a tap's composable chain, root → node, app frames only (shown on hover). */
    val chain: List<io.yamsergey.dta.protocol.layout.Frame>? = null,
    /** Feature 065: Network's saved filters could not judge it: its entry is no longer kept (shown). */
    val notJudged: Boolean = false,
)

@Serializable
data class SequencePage(
    /** Oldest first; the newest ones when [matched] is more. */
    val steps: List<SequenceStep>,
    /** Rows from the start moment, before the query's filter words. */
    val total: Int,
    val lanes: List<SequenceLane>,
    /** Rows left after the filter words. */
    val matched: Int = total,
    /** Rows before the start moment, left out. */
    val beforeStart: Int = 0,
    /** Rows among [steps] holding every search word. */
    val hits: Int = 0,
    val socketMessages: Int = 0,
    /** Times are told from here. */
    val startWallMs: Long = 0,
    val sources: List<SourceStatus> = emptyList(),
    /** Why socket messages are missing or incomplete; null when they are all here. */
    val socketsNote: String? = null,
    /** Feature 049: oldest first; only the one asked for when [onlyFlow] is set. */
    val flows: List<SequenceFlow> = emptyList(),
    /** The rows are this flow's only. */
    val onlyFlow: String? = null,
    /** Why stored flows could not be read. */
    val flowsNote: String? = null,
    /** Feature 065: what Network's saved filters hid (network rows only); null when none apply. */
    val saved: io.yamsergey.dta.protocol.network.SavedFiltersApplied? = null,
)
