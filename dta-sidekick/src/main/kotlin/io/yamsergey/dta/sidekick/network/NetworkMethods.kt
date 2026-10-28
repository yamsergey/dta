package io.yamsergey.dta.sidekick.network

import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.network.BodyCodec
import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.ClearResult
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.Coverage
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.MatchLocation
import io.yamsergey.dta.protocol.network.NetworkBodyResult
import io.yamsergey.dta.protocol.network.NetworkEntry
import io.yamsergey.dta.protocol.network.NetworkEntryDetail
import io.yamsergey.dta.protocol.network.NetworkFilterEval
import io.yamsergey.dta.protocol.network.NetworkPage
import io.yamsergey.dta.protocol.network.NetworkQuery
import io.yamsergey.dta.protocol.network.WsConnectionEntry
import io.yamsergey.dta.protocol.network.WsMessageEntry
import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import io.yamsergey.dta.protocol.rpc.DegradationMarker
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.InvalidParamsException
import io.yamsergey.dta.protocol.rpc.MethodRegistry
import io.yamsergey.dta.sidekick.network.store.NetworkStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `network_list`, `network_entry`, `network_clear` (contract §2, §3, §5). Results are
 * raw: decoding and redaction are the daemon's job (one decoder, one redactor — VI).
 * Each result states its own degradations (III).
 */
class NetworkMethods(
    private val store: NetworkStore,
    /** Codes the network script contributes (feature 005: unavailable, not persisted). */
    private val scriptDegradations: () -> List<String> = { emptyList() },
    private val captureStatus: () -> CapabilityStatus,
) {
    fun install(registry: MethodRegistry) {
        registry.register("network_list") { ctx -> DtaJson.encodeToJsonElement(NetworkPage.serializer(), list(ctx.params)) }
        registry.register("network_entry") { ctx -> DtaJson.encodeToJsonElement(NetworkEntryDetail.serializer(), entry(ctx.params)) }
        registry.register("network_clear") { DtaJson.encodeToJsonElement(ClearResult.serializer(), clear()) }
        registry.register("network_body") { ctx ->
            DtaJson.encodeToJsonElement(NetworkBodyResult.serializer(), body(ctx.params, ctx::attachBinary))
        }
    }

    fun list(params: JsonObject?): NetworkPage {
        val query = NetworkQuery.parse(params)
        val eval = query.filter?.let { filter ->
            NetworkFilterEval(
                filter = filter,
                liveRunId = store.runId,
                connectionUrl = { id -> (store.get(id) as? WsConnectionEntry)?.url },
                bodyBytes = ::bodyBytes,
            )
        }
        val locations = HashMap<String, List<MatchLocation>>()
        val page = store.list(order = query.order, limit = query.limit, before = query.before) { entry ->
            if (eval == null) return@list true
            val matched = eval.matches(entry) ?: return@list false
            if (matched.isNotEmpty()) locations[entry.id] = matched
            true
        }
        val notSearched = eval?.binaryNotSearched?.takeIf { it > 0 }?.let { listOf("binary bodies whose encoding cannot be undone ($it)") }
        val coverage = store.coverage().copy(searched = page.searched, notSearched = notSearched)
        return NetworkPage(
            entries = page.entries.map { it.summary() },
            matches = page.entries.mapNotNull { e -> locations[e.id]?.let { e.id to it } }.toMap().ifEmpty { null },
            total = page.total,
            next = page.next,
            coverage = coverage,
            degraded = degradations(dropped = coverage.dropped, entries = page.entries)
                .plusHistory(coverage, reachesEvicted = coverage.evicted > 0 && page.entries.any { it.id == store.earliestId() }),
        )
    }

    /** Eviction (FR-026) and lost persistence (FR-029) as markers on the page. */
    private fun List<DegradationMarker>?.plusHistory(coverage: Coverage, reachesEvicted: Boolean): List<DegradationMarker>? {
        val extra = buildList {
            if (reachesEvicted) add(DegradationCodes.NETWORK_HISTORY_EVICTED)
            if (!coverage.persisted) add(DegradationCodes.NETWORK_HISTORY_NOT_PERSISTED)
        }.map { DegradationMarker(NetworkCapability.NAME, it) }
        return (orEmpty() + extra).ifEmpty { null }
    }

    fun entry(params: JsonObject?): NetworkEntryDetail {
        val id = (params?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw InvalidParamsException("network_entry requires a string 'id'")
        val entry = store.get(id) ?: throw InvalidParamsException(
            "unknown entry id $id; it may have been evicted (earliest stored: ${earliest()})",
        )
        if (entry !is WsConnectionEntry) {
            return NetworkEntryDetail(entry = entry, degraded = degradations(dropped = store.coverage().dropped, entries = listOf(entry)))
        }
        val messages = store.snapshot().filter { it is WsMessageEntry && it.connectionId == id }.map { it as WsMessageEntry }
        return NetworkEntryDetail(
            entry = entry,
            messages = messages.take(NetworkEntryDetail.MAX_MESSAGES),
            messagesTruncated = (messages.size - NetworkEntryDetail.MAX_MESSAGES).takeIf { it > 0 },
            degraded = degradations(dropped = store.coverage().dropped, entries = listOf(entry) + messages),
        )
    }

    /**
     * The stored wire bytes of one body, as binary continuation frames of [CHUNK_BYTES]
     * (contract §4). Never decoded or redacted here. A body whose file is gone comes back
     * as `dropped` with no frames — stated, not an error.
     */
    fun body(params: JsonObject?, attach: (ByteArray) -> Unit): NetworkBodyResult {
        val id = (params?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw InvalidParamsException("network_body requires a string 'id'")
        val partName = (params["part"] as? JsonPrimitive)?.content
        val part = BodyPart.entries.firstOrNull { wireName(it) == partName }
            ?: throw InvalidParamsException("unknown part '$partName'; valid values: ${BodyPart.entries.joinToString(", ") { wireName(it) }}")
        val entry = store.get(id) ?: throw InvalidParamsException(
            "unknown entry id $id; it may have been evicted (earliest stored: ${earliest()})",
        )
        val info = bodyOf(entry, part) ?: throw InvalidParamsException("entry $id has no ${wireName(part)} body")

        val input = store.openBody(id, part)
        if (input == null) {
            if (info.storedBytes == 0L || info.completeness == Completeness.DROPPED) return NetworkBodyResult(info)
            return NetworkBodyResult(
                info.copy(completeness = Completeness.DROPPED, completenessReason = "body file is no longer stored (evicted or cleared)"),
            )
        }
        input.use { stream ->
            val buffer = ByteArray(CHUNK_BYTES)
            while (true) {
                var filled = 0
                while (filled < buffer.size) {
                    val n = stream.read(buffer, filled, buffer.size - filled)
                    if (n < 0) break
                    filled += n
                }
                if (filled > 0) attach(buffer.copyOf(filled))
                if (filled < buffer.size) break
            }
        }
        return NetworkBodyResult(info)
    }

    fun clear(): ClearResult {
        val removed = store.clear()
        return ClearResult(clearedEntries = removed, clearedAtMs = store.coverage().clearedAtMs ?: 0)
    }

    private fun bodiesOf(entry: NetworkEntry): List<BodyInfo> = BodyPart.entries.mapNotNull { bodyOf(entry, it) }

    /** The body info of one part of an entry, wire or app side; null when absent. */
    private fun bodyOf(entry: NetworkEntry, part: BodyPart): BodyInfo? = when (part) {
        BodyPart.REQUEST -> (entry as? HttpEntry)?.requestBody
        BodyPart.RESPONSE -> (entry as? HttpEntry)?.responseBody
        BodyPart.MESSAGE -> (entry as? WsMessageEntry)?.payload
        BodyPart.APP_REQUEST -> (entry as? HttpEntry)?.appRequest?.body
        BodyPart.APP_RESPONSE -> (entry as? HttpEntry)?.appResponse?.body
        BodyPart.APP_MESSAGE -> (entry as? WsMessageEntry)?.appPayload
    }

    private fun wireName(part: BodyPart): String = DtaJson.encodeToJsonElement(BodyPart.serializer(), part).let { (it as JsonPrimitive).content }

    /** A stored body for `bodyContains`, Content-Encoding undone; raw bytes (decoded = false) when it cannot be. */
    private fun bodyBytes(entry: NetworkEntry, part: BodyPart): NetworkFilterEval.BodyBytes? {
        val info = bodyOf(entry, part) ?: return null
        val raw = store.openBody(entry.id, part)?.use { it.readBytes() } ?: return null
        val decoded = BodyCodec.decode(raw, info.encoding)
        return NetworkFilterEval.BodyBytes(decoded.bytes, decoded.decoded)
    }

    companion object {
        const val CHUNK_BYTES = 256 * 1024
    }

    private fun earliest(): String = store.coverage().earliestMs?.let { io.yamsergey.dta.sidekick.util.IsoTime.format(it) } ?: "nothing stored"

    private fun degradations(dropped: Int, entries: List<NetworkEntry>): List<DegradationMarker>? {
        val codes = buildList {
            when (captureStatus().status) {
                CapabilityStatus.Status.UNAVAILABLE -> add(DegradationCodes.NETWORK_HOOKS_UNAVAILABLE)
                CapabilityStatus.Status.DEGRADED -> add(DegradationCodes.NETWORK_LIBRARY_UNAVAILABLE)
                CapabilityStatus.Status.AVAILABLE -> Unit
            }
            if (dropped > 0) add(DegradationCodes.NETWORK_EVENTS_DROPPED)
            if (entries.any { it.initiator?.missing != null }) add(DegradationCodes.NETWORK_INITIATOR_MISSING)
            if (entries.any { e -> bodiesOf(e).any { it.completeness != Completeness.COMPLETE } }) add(DegradationCodes.NETWORK_BODY_INCOMPLETE)
            addAll(scriptDegradations())
        }
        return codes.map { DegradationMarker(NetworkCapability.NAME, it) }.ifEmpty { null }
    }
}
