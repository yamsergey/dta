package io.yamsergey.dta.sidekick.network

import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.NetworkEntry
import io.yamsergey.dta.protocol.timeline.Basis
import io.yamsergey.dta.protocol.timeline.CauseLink
import io.yamsergey.dta.protocol.timeline.CauseRules
import io.yamsergey.dta.protocol.timeline.Kinds
import io.yamsergey.dta.protocol.timeline.NetworkPayload
import io.yamsergey.dta.protocol.timeline.NetworkPhase
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import io.yamsergey.dta.sidekick.timeline.TimelineBuffer

/**
 * HTTP exchanges in the timeline (feature 016, research R4): a request event, caused by what
 * the app was handling when it issued the call (remembered with the initiator), and a
 * response or failure linked to it. No bodies, no query values: `network.entry` has those.
 */
class NetworkTimeline(
    private val buffer: TimelineBuffer,
    private val mono: () -> Long = { android.os.SystemClock.uptimeMillis() },
) {
    private val requests = object : LinkedHashMap<String, String>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > KEPT
    }
    private val ended = HashSet<String>()

    @Synchronized
    fun observe(entry: NetworkEntry) {
        val http = entry as? HttpEntry ?: return
        if (http.state == HttpState.PROCESS_ENDED) return
        val requestId = requests[http.id] ?: request(http).id.also { requests[http.id] = it }
        if (http.state != HttpState.PENDING && http.id !in ended) {
            ended += http.id
            if (ended.size > KEPT) ended.remove(ended.first())
            end(http, requestId)
        }
    }

    private fun request(http: HttpEntry): TimelineEvent {
        val (host, path) = split(http.url)
        return buffer.append { id, seq ->
            TimelineEvent(
                id, seq, mono(), http.timeMs, Sources.NETWORK, Kinds.NETWORK_REQUEST,
                title = "${http.method} $path",
                network = NetworkPayload(http.id, NetworkPhase.REQUEST, http.method, host, path),
                causes = listOfNotNull(http.initiator?.cause),
            )
        }
    }

    private fun end(http: HttpEntry, requestId: String) {
        val (host, path) = split(http.url)
        val failed = http.state == HttpState.FAILED
        buffer.append { id, seq ->
            TimelineEvent(
                id, seq, mono(), http.timeMs + (http.durationMs ?: 0), Sources.NETWORK,
                if (failed) Kinds.NETWORK_FAILED else Kinds.NETWORK_RESPONSE,
                title = "${http.method} $path " + if (failed) "failed" else "→ ${http.status ?: "?"}",
                network = NetworkPayload(
                    http.id, if (failed) NetworkPhase.FAILED else NetworkPhase.RESPONSE, http.method, host, path,
                    status = http.status, durationMs = http.durationMs, error = http.failure,
                ),
                causes = listOf(CauseLink(requestId, Basis.EXACT, CauseRules.SAME_EXCHANGE)),
            )
        }
    }

    private fun split(url: String): Pair<String?, String> {
        val noScheme = url.substringAfter("://", url)
        val host = noScheme.substringBefore('/').substringBefore('?').takeIf { "://" in url }
        val path = "/" + noScheme.substringAfter('/', "").substringBefore('?').substringBefore('#')
        return host to path
    }

    private companion object {
        const val KEPT = 2_000
    }
}
