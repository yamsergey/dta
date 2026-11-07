package io.yamsergey.dta.protocol.timeline

import kotlinx.serialization.Serializable

/** Where a flow comes from; 050 adds a source, not a model. */
object FlowSources {
    const val MANUAL = "manual"
}

/**
 * Feature 049: a named interval of a session. Shaped like a span, with no dependency on OTel. A row
 * is in a flow by its time only: this says nothing about what caused the row.
 */
@Serializable
data class Flow(
    /** `f1`; never reused for this device and app. */
    val id: String,
    val name: String,
    val source: String = FlowSources.MANUAL,
    /** Device time; a row at this moment is in the flow. */
    val startMs: Long,
    /** Device time; a row at this moment is not in the flow. Null while recording. */
    val endMs: Long? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    val recording: Boolean get() = endMs == null

    fun holds(ms: Long): Boolean = ms >= startMs && (endMs == null || ms < endMs)

    companion object {
        const val MAX_NAME = 80
    }
}

/** A flow as a sequence sees it. */
@Serializable
data class SequenceFlow(
    val flow: Flow,
    /** Kept rows in its interval. */
    val rows: Int = 0,
    /** Of them, what the page's start moment and filter words leave. */
    val shown: Int = rows,
    /** Rows of its interval are older than what is kept. */
    val rowsGone: Boolean = false,
)

@Serializable
data class FlowStarted(
    val flow: Flow,
    /** The flow this start ended. */
    val ended: Flow? = null,
)

@Serializable
data class FlowList(
    val flows: List<SequenceFlow>,
    /** Why stored flows could not be read. */
    val note: String? = null,
)
