package io.yamsergey.dta.protocol.network

import kotlinx.serialization.Serializable

/**
 * Feature 067: a query's decision for one entry — what a full pass over the history would say about
 * it. [byPreview]: a body was searched by its preview only (not copied yet), so the decision may
 * change once the copy arrives.
 */
@Serializable
data class EntryDecision(
    val match: Boolean,
    val locations: List<MatchLocation>? = null,
    val hits: List<SearchHit>? = null,
    val byPreview: Boolean = false,
) {
    companion object {
        val NO = EntryDecision(false)
    }
}
