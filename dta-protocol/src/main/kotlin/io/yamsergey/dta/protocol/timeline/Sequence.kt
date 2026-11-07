package io.yamsergey.dta.protocol.timeline

import kotlinx.serialization.Serializable

/**
 * Feature 048: the timeline as one flat sequence diagram. A step is a row of it, in time order;
 * nothing is grouped by any rule (replaces 045's episodes).
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
)
