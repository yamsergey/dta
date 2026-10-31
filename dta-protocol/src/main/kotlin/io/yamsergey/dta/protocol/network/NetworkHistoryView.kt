package io.yamsergey.dta.protocol.network

import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import io.yamsergey.dta.protocol.rpc.DegradationMarker
import io.yamsergey.dta.protocol.rpc.InvalidParamsException
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * What only the app knows about its history, sent to whoever answers queries over it
 * (feature 011): the live run, the coverage counters, the capture's status and the script's
 * degradation codes.
 */
@Serializable
data class HistoryContext(
    val runId: String,
    val coverage: Coverage,
    val capture: CapabilityStatus,
    val scriptCodes: List<String> = emptyList(),
)

/**
 * The answers of `network_list` and `network_entry` over a history: paging, filters and match
 * locations, coverage and degradations. One implementation (constitution VI) for the daemon's
 * host index and the sidekick's memory-only fallback, so their answers cannot differ.
 *
 * @param entries the stored entries in chronological order (first record order)
 * @param byId one entry, or null when it is not stored
 * @param bodyBytes a stored body with its Content-Encoding undone where possible
 */
class NetworkHistoryView(
    private val entries: () -> List<NetworkEntry>,
    private val byId: (String) -> NetworkEntry?,
    private val context: () -> HistoryContext,
    private val bodyBytes: (NetworkEntry, BodyPart) -> NetworkFilterEval.BodyBytes?,
) {
    fun list(query: NetworkQuery): NetworkPage {
        val ctx = context()
        val eval = query.filter?.let { filter ->
            NetworkFilterEval(
                filter = filter,
                liveRunId = ctx.runId,
                connectionUrl = { id -> (byId(id) as? WsConnectionEntry)?.url },
                bodyBytes = bodyBytes,
            )
        }
        val chronological = entries()
        val all = if (query.order == Order.NEWEST_FIRST) chronological.asReversed() else chronological
        val start = if (query.before == null) 0 else {
            val at = all.indexOfFirst { it.id == query.before }
            if (at < 0) throw InvalidParamsException("unknown cursor '${query.before}'; it may have been evicted or cleared")
            at + 1
        }
        val locations = HashMap<String, List<MatchLocation>>()
        var total = 0
        val page = ArrayList<NetworkEntry>(minOf(query.limit, all.size))
        var more = false
        all.forEachIndexed { index, entry ->
            if (eval != null) {
                val matched = eval.matches(entry) ?: return@forEachIndexed
                if (matched.isNotEmpty()) locations[entry.id] = matched
            }
            total++
            if (index < start) return@forEachIndexed
            if (page.size < query.limit) page += entry else more = true
        }
        val notSearched = eval?.binaryNotSearched?.takeIf { it > 0 }?.let { listOf("binary bodies whose encoding cannot be undone ($it)") }
        val coverage = ctx.coverage.copy(searched = all.size, notSearched = notSearched)
        val earliest = chronological.firstOrNull()?.id
        return NetworkPage(
            entries = page.map { it.withSocketUrl().summary() },
            matches = page.mapNotNull { e -> locations[e.id]?.let { e.id to it } }.toMap().ifEmpty { null },
            total = total,
            next = if (more) page.last().id else null,
            coverage = coverage,
            degraded = degradations(ctx, page).plusHistory(coverage, reachesEvicted = coverage.evicted > 0 && page.any { it.id == earliest }),
        )
    }

    fun entry(id: String): NetworkEntryDetail {
        val ctx = context()
        val entry = byId(id) ?: throw InvalidParamsException(
            "unknown entry id $id; it may have been evicted (earliest stored: ${ctx.coverage.earliestMs?.let(::iso) ?: "nothing stored"})",
        )
        if (entry !is WsConnectionEntry) {
            return NetworkEntryDetail(entry = entry.withSocketUrl(), degraded = degradations(ctx, listOf(entry)))
        }
        val messages = entries().filter { it is WsMessageEntry && it.connectionId == id }.map { (it as WsMessageEntry).copy(url = it.url ?: entry.url) }
        return NetworkEntryDetail(
            entry = entry,
            messages = messages.take(NetworkEntryDetail.MAX_MESSAGES),
            messagesTruncated = (messages.size - NetworkEntryDetail.MAX_MESSAGES).takeIf { it > 0 },
            degraded = degradations(ctx, listOf(entry) + messages),
        )
    }

    /** A message stored before messages carried their socket's URL takes its connection's, while that is stored. */
    private fun NetworkEntry.withSocketUrl(): NetworkEntry =
        if (this is WsMessageEntry && url == null) copy(url = (byId(connectionId) as? WsConnectionEntry)?.url) else this

    private fun degradations(ctx: HistoryContext, entries: List<NetworkEntry>): List<DegradationMarker>? {
        val codes = buildList {
            when (ctx.capture.status) {
                CapabilityStatus.Status.UNAVAILABLE -> add(DegradationCodes.NETWORK_HOOKS_UNAVAILABLE)
                CapabilityStatus.Status.DEGRADED -> add(DegradationCodes.NETWORK_LIBRARY_UNAVAILABLE)
                CapabilityStatus.Status.AVAILABLE -> Unit
            }
            if (ctx.coverage.dropped > 0) add(DegradationCodes.NETWORK_EVENTS_DROPPED)
            if (entries.any { it.initiator?.missing != null }) add(DegradationCodes.NETWORK_INITIATOR_MISSING)
            if (entries.any { e -> bodiesOf(e).any { it.completeness != Completeness.COMPLETE } }) add(DegradationCodes.NETWORK_BODY_INCOMPLETE)
            addAll(ctx.scriptCodes)
        }
        return codes.map { DegradationMarker(CAPABILITY, it) }.ifEmpty { null }
    }

    /** Eviction (FR-026) and lost persistence (FR-029) as markers on the page. */
    private fun List<DegradationMarker>?.plusHistory(coverage: Coverage, reachesEvicted: Boolean): List<DegradationMarker>? {
        val extra = buildList {
            if (reachesEvicted) add(DegradationCodes.NETWORK_HISTORY_EVICTED)
            if (!coverage.persisted) add(DegradationCodes.NETWORK_HISTORY_NOT_PERSISTED)
        }.map { DegradationMarker(CAPABILITY, it) }
        return (orEmpty() + extra).ifEmpty { null }
    }

    companion object {
        const val CAPABILITY = "network"

        /** The body info of one part of an entry, wire or app side; null when absent. */
        fun bodyOf(entry: NetworkEntry, part: BodyPart): BodyInfo? = when (part) {
            BodyPart.REQUEST -> (entry as? HttpEntry)?.requestBody
            BodyPart.RESPONSE -> (entry as? HttpEntry)?.responseBody
            BodyPart.MESSAGE -> (entry as? WsMessageEntry)?.payload
            BodyPart.APP_REQUEST -> (entry as? HttpEntry)?.appRequest?.body
            BodyPart.APP_RESPONSE -> (entry as? HttpEntry)?.appResponse?.body
            BodyPart.APP_MESSAGE -> (entry as? WsMessageEntry)?.appPayload
        }

        fun bodiesOf(entry: NetworkEntry): List<BodyInfo> = BodyPart.entries.mapNotNull { bodyOf(entry, it) }

        /** ISO-8601 UTC without java.time (the sidekick runs on API 24 without desugaring). */
        fun iso(epochMs: Long): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(epochMs))
    }
}
