package io.yamsergey.dta.protocol.timeline

import kotlinx.serialization.Serializable

/**
 * Feature 045: the timeline as episodes. An episode is what the app did from one trigger (a tap,
 * back, navigation, process start) or after a pause; a step is one line of its sequence diagram.
 */
object EpisodeLanes {
    const val USER = "user"
    const val APP = "app"
    const val OTHER = "other"
    const val SOCKET = "socket"

    fun host(name: String) = "host:$name"
}

/** A column of the diagram, in drawing order. */
@Serializable
data class EpisodeLane(val id: String, val title: String)

@Serializable
data class EpisodeStep(
    val wallMs: Long,
    /** The lane it starts from; a step with no [to] is a note on this lane. */
    val from: String,
    val to: String? = null,
    val label: String,
    /** A return: a response, a message received. */
    val back: Boolean = false,
    /** It has an exact or declared cause inside this episode. Never set from timing. */
    val linked: Boolean = false,
    val failed: Boolean = false,
    val eventId: String? = null,
    /** The Network tab's entry. */
    val entryId: String? = null,
)

/** Received socket messages not drawn as steps, by what they call themselves. */
@Serializable
data class SocketCount(val name: String, val count: Int)

@Serializable
data class Episode(
    /** The id of its first event. */
    val id: String,
    val wallMs: Long,
    val endWallMs: Long,
    val title: String,
    val requests: Int = 0,
    val failures: Int = 0,
    val screenChanged: Boolean = false,
    val socketOpened: Boolean = false,
    val socketSent: Int = 0,
    val socketCounts: List<SocketCount> = emptyList(),
    /** Timeline gaps inside it: events may be missing here. */
    val gaps: Int = 0,
    /** Empty unless steps were asked for. */
    val steps: List<EpisodeStep> = emptyList(),
)

@Serializable
data class EpisodesPage(
    /** Oldest first; the newest ones when [total] is more. */
    val episodes: List<Episode>,
    val total: Int,
    val lanes: List<EpisodeLane>,
    val socketMessages: Int = 0,
    /** Times are told from here. */
    val startWallMs: Long = 0,
    val sources: List<SourceStatus> = emptyList(),
    /** Why socket messages are missing or incomplete; null when they are all here. */
    val socketsNote: String? = null,
)
