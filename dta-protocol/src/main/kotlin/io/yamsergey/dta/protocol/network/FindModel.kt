package io.yamsergey.dta.protocol.network

import io.yamsergey.dta.protocol.rpc.DegradationMarker
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Feature 071 (contracts/find.md): one row of a streamed search — the entry as `network.list` shows it,
 * with its hits and where its filters matched, and its epoch when it is archived.
 */
@Serializable
data class FoundItem(
    val entry: NetworkEntry,
    val hits: List<SearchHit>? = null,
    val matches: List<MatchLocation>? = null,
    val fields: List<String>? = null,
    val archived: ArchivedMark? = null,
)

/** Feature 071: where a streamed search is. Only [DONE] says its whole scope was read. */
@Serializable
enum class FindState {
    /** Over the concurrent limit: it starts when one before it ends. */
    @SerialName("waiting") WAITING,
    @SerialName("searching") SEARCHING,
    /** The whole scope was read. */
    @SerialName("done") DONE,
    /** The caller's limit was reached; `next` continues. */
    @SerialName("limit") LIMIT,
    /** Stopped before the scope was read (`reason`): never complete. */
    @SerialName("stopped") STOPPED,
    @SerialName("failed") FAILED,
}

/** Feature 071: why a streamed search was stopped. */
@Serializable
enum class StopReason {
    /** `network.find.stop`: the stop control, a changed or cleared query, a closed tab. */
    @SerialName("stop") STOP,
    /** The client went away. */
    @SerialName("closed") CLOSED,
    /** The call was cancelled (an agent's cancel, Ctrl-C). */
    @SerialName("cancelled") CANCELLED,
}

/** Feature 071: one `network.found` event of a stream; the last one has a final [state]. */
@Serializable
data class FoundEvent(
    val stream: String,
    val state: FindState,
    val items: List<FoundItem>? = null,
    /** While [FindState.WAITING]: searches ahead of this one, this one included. */
    val position: Int? = null,
    /** With [FindState.LIMIT]: the cursor of the next page. */
    val next: String? = null,
    val reason: StopReason? = null,
    val error: String? = null,
    /** What the search could not read, for the entries it read (content, never time). */
    val notSearched: List<String>? = null,
    val scope: HistoryScope? = null,
    val degraded: List<DegradationMarker>? = null,
    val saved: SavedFiltersApplied? = null,
    val redaction: RedactionInfo? = null,
) {
    val final: Boolean get() = state != FindState.WAITING && state != FindState.SEARCHING

    companion object {
        const val NAME = "network.found"
    }
}

/** Feature 071: how a walk of [NetworkHistoryView.find] ended: [next] when it reached its limit. */
data class FindEnd(val next: String?, val notSearched: List<String>?)

/**
 * Feature 071: a streamed search gathered into one answer — the `network.search` page of what it found,
 * with how it ended — for callers that want one document (MCP, `--json`). Equal to `network.search`'s
 * page for the same query and scope (FR-003), plus [state].
 */
class FindCollector {
    private val entries = ArrayList<EntryHits>()
    var final: FoundEvent? = null
        private set
    private var scope: HistoryScope? = null
    private var saved: SavedFiltersApplied? = null

    val found: Int get() = entries.size

    /** Takes one event; true when it was the final one. */
    fun take(e: FoundEvent): Boolean {
        e.items?.forEach { entries += EntryHits(it.entry.id, it.entry.timeMs, it.hits.orEmpty()) }
        e.scope?.let { scope = it }
        e.saved?.let { saved = it }
        if (e.final) final = e
        return e.final
    }

    fun page(): SearchPage = SearchPage(entries.toList(), next = final?.next, notSearched = final?.notSearched, degraded = final?.degraded, saved = saved, scope = final?.scope ?: scope)

    /** The page as JSON with the stream's final `state` (and `reason` or `error`). */
    fun json(): kotlinx.serialization.json.JsonObject {
        val page = io.yamsergey.dta.protocol.rpc.DtaJson.encodeToJsonElement(SearchPage.serializer(), page()) as kotlinx.serialization.json.JsonObject
        val end = final
        return kotlinx.serialization.json.JsonObject(page + listOfNotNull(
            "state" to io.yamsergey.dta.protocol.rpc.DtaJson.encodeToJsonElement(FindState.serializer(), end?.state ?: FindState.STOPPED),
            end?.reason?.let { "reason" to io.yamsergey.dta.protocol.rpc.DtaJson.encodeToJsonElement(StopReason.serializer(), it) },
            end?.error?.let { "error" to kotlinx.serialization.json.JsonPrimitive(it) },
        ))
    }
}
