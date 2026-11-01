package io.yamsergey.dta.daemon.timeline

import io.yamsergey.dta.protocol.timeline.Basis
import io.yamsergey.dta.protocol.timeline.CauseLink
import io.yamsergey.dta.protocol.timeline.CauseRules
import io.yamsergey.dta.protocol.timeline.Kinds
import io.yamsergey.dta.protocol.timeline.NetworkPhase
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import io.yamsergey.dta.protocol.ui.UiEventKind

/**
 * Inferred causes (feature 016, research R11): candidates from timing and matching data, only
 * between events of one process, each naming the rule and what it saw. They are offered only
 * on request and always marked inferred; they are never stored.
 */
class InferredRules(
    events: List<TimelineEvent>,
    /** A response body kept on the host, by network entry id; null when it is not available. */
    private val body: (String) -> String? = { null },
    private val windowMs: Long = WINDOW_MS,
) {
    private val byEpoch = events.groupBy { epochOf(it.id) }

    fun candidates(event: TimelineEvent): List<CauseLink> {
        if (event.source != Sources.UI) return emptyList()
        val same = byEpoch[epochOf(event.id)].orEmpty()
        return afterResponse(event, same) + whileInFlight(event, same) + textMatch(event, same)
    }

    private fun ended(e: TimelineEvent) = e.network != null && e.network!!.phase != NetworkPhase.REQUEST

    private fun afterResponse(event: TimelineEvent, same: List<TimelineEvent>): List<CauseLink> {
        if (event.ui?.kind == UiEventKind.WINDOW_DISAPPEARED || event.ui?.kind == UiEventKind.WINDOW_SETTLED) return emptyList()
        val response = same.lastOrNull { ended(it) && it.monoMs <= event.monoMs && event.monoMs - it.monoMs <= windowMs } ?: return emptyList()
        return listOf(CauseLink(response.id, Basis.INFERRED, CauseRules.AFTER_RESPONSE, "shown ${event.monoMs - response.monoMs} ms after the response ended"))
    }

    private fun whileInFlight(event: TimelineEvent, same: List<TimelineEvent>): List<CauseLink> {
        if (event.ui?.kind != UiEventKind.WINDOW_APPEARED) return emptyList()
        val closed = same.firstOrNull { it.ui?.appearedId == event.id && it.ui?.kind == UiEventKind.WINDOW_DISAPPEARED } ?: return emptyList()
        return same.filter { it.kind == Kinds.NETWORK_REQUEST && it.monoMs <= event.monoMs }.mapNotNull { request ->
            val end = same.firstOrNull { ended(it) && it.network?.entryId == request.network?.entryId && it.monoMs >= event.monoMs } ?: return@mapNotNull null
            val after = closed.monoMs - end.monoMs
            if (after < 0 || after > windowMs) return@mapNotNull null
            CauseLink(request.id, Basis.INFERRED, CauseRules.WHILE_IN_FLIGHT, "opened while the request was in flight and closed $after ms after it ended")
        }
    }

    private fun textMatch(event: TimelineEvent, same: List<TimelineEvent>): List<CauseLink> {
        val ui = event.ui ?: return emptyList()
        val texts = (listOfNotNull(ui.text) + ui.content?.elements.orEmpty().mapNotNull { it.text }).filter { it.length >= MIN_TEXT }.distinct()
        if (texts.isEmpty()) return emptyList()
        val responses = same.filter { it.kind == Kinds.NETWORK_RESPONSE && it.monoMs <= event.monoMs }.takeLast(RECENT_RESPONSES).reversed()
        for (response in responses) {
            val text = body(response.network!!.entryId) ?: continue
            val values = stringValues(text)
            val hit = texts.firstOrNull { it in values } ?: continue
            val what = listOfNotNull(response.network!!.method, response.network!!.path).joinToString(" ")
            return listOf(CauseLink(response.id, Basis.INFERRED, CauseRules.TEXT_MATCH, "the text \"$hit\" is in the response of $what"))
        }
        return emptyList()
    }

    /** The string values of a JSON body (or the whole body when it is not JSON). */
    private fun stringValues(body: String): Set<String> =
        runCatching {
            val out = HashSet<String>()
            fun walk(e: kotlinx.serialization.json.JsonElement) {
                when (e) {
                    is kotlinx.serialization.json.JsonObject -> e.values.forEach(::walk)
                    is kotlinx.serialization.json.JsonArray -> e.forEach(::walk)
                    is kotlinx.serialization.json.JsonPrimitive -> if (e.isString) out += e.content
                }
            }
            walk(kotlinx.serialization.json.Json.parseToJsonElement(body))
            out
        }.getOrElse { setOf(body.trim()) }

    companion object {
        const val WINDOW_MS = 300L
        const val MIN_TEXT = 4
        const val RECENT_RESPONSES = 20

        fun epochOf(id: String) = id.substringBefore(':')
    }
}
