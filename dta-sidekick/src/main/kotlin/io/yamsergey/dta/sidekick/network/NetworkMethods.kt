package io.yamsergey.dta.sidekick.network

import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.network.BodyCodec
import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.BodyRangeResult
import io.yamsergey.dta.protocol.network.JournalIdentityResult
import io.yamsergey.dta.protocol.network.JournalPageResult
import io.yamsergey.dta.protocol.network.ClearResult
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.Coverage
import io.yamsergey.dta.protocol.network.HistoryContext
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.NetworkHistoryView
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
        // The host's side (feature 011): which journal, its records after a cursor, bodies by range.
        registry.register("network_identity") { DtaJson.encodeToJsonElement(JournalIdentityResult.serializer(), identity()) }
        registry.register("network_journal") { ctx -> DtaJson.encodeToJsonElement(JournalPageResult.serializer(), journal(ctx.params)) }
        registry.register("network_body_range") { ctx ->
            DtaJson.encodeToJsonElement(BodyRangeResult.serializer(), bodyRange(ctx.params, ctx::attachBinary))
        }
    }

    fun identity(): JournalIdentityResult = store.identity().let { JournalIdentityResult(it.journalId, it.reason, it.entries, it.persisted, context()) }

    fun journal(params: JsonObject?): JournalPageResult {
        val after = (params?.get("after") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val limit = ((params?.get("limit") as? JsonPrimitive)?.content?.toIntOrNull() ?: JOURNAL_PAGE).coerceIn(1, MAX_JOURNAL_PAGE)
        val page = store.journal(after, limit)
        return JournalPageResult(page.journalId, page.records, page.next, page.end)
    }

    fun bodyRange(params: JsonObject?, attach: (ByteArray) -> Unit): BodyRangeResult {
        val id = (params?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw InvalidParamsException("network_body_range requires a string 'id'")
        val partName = (params["part"] as? JsonPrimitive)?.content
        val part = BodyPart.entries.firstOrNull { wireName(it) == partName }
            ?: throw InvalidParamsException("unknown part '$partName'; valid values: ${BodyPart.entries.joinToString(", ") { wireName(it) }}")
        val offset = (params["offset"] as? JsonPrimitive)?.content?.toLongOrNull()?.takeIf { it >= 0 }
            ?: throw InvalidParamsException("network_body_range requires an 'offset' of 0 or more")
        val length = (params["length"] as? JsonPrimitive)?.content?.toLongOrNull()?.takeIf { it > 0 }
            ?: throw InvalidParamsException("network_body_range requires a positive 'length'")
        val total = store.bodySize(id, part) ?: throw InvalidParamsException("no stored ${wireName(part)} body for $id (it may have been evicted or cleared)")
        var sent = 0L
        store.openBodyRange(id, part, offset, length)?.use { stream ->
            val buffer = ByteArray(CHUNK_BYTES)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                if (n > 0) {
                    attach(buffer.copyOf(n))
                    sent += n
                }
            }
        }
        return BodyRangeResult(total, offset, sent)
    }

    /** The answers over the whole journal: the memory-only fallback, and the reference the host is checked against. */
    private val view = NetworkHistoryView(entries = store::snapshot, byId = store::get, context = ::context, bodyBytes = ::bodyBytes)

    /** What only the app knows, for whoever answers queries over its history (feature 011). */
    fun context(): HistoryContext = HistoryContext(store.runId, store.coverage(), captureStatus(), scriptDegradations())

    fun list(params: JsonObject?): NetworkPage = view.list(NetworkQuery.parse(params))

    fun entry(params: JsonObject?): NetworkEntryDetail {
        val id = (params?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw InvalidParamsException("network_entry requires a string 'id'")
        return view.entry(id)
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
            "unknown entry id $id; it may have been evicted (earliest stored: ${store.coverage().earliestMs?.let(NetworkHistoryView::iso) ?: "nothing stored"})",
        )
        val info = NetworkHistoryView.bodyOf(entry, part) ?: throw InvalidParamsException("entry $id has no ${wireName(part)} body")

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


    private fun wireName(part: BodyPart): String = DtaJson.encodeToJsonElement(BodyPart.serializer(), part).let { (it as JsonPrimitive).content }

    /** A stored body for `bodyContains`, Content-Encoding undone; raw bytes (decoded = false) when it cannot be. */
    private fun bodyBytes(entry: NetworkEntry, part: BodyPart): NetworkFilterEval.BodyBytes? {
        val info = NetworkHistoryView.bodyOf(entry, part) ?: return null
        val raw = store.openBody(entry.id, part)?.use { it.readBytes() } ?: return null
        val decoded = BodyCodec.decode(raw, info.encoding)
        return NetworkFilterEval.BodyBytes(decoded.bytes, decoded.decoded)
    }

    companion object {
        const val CHUNK_BYTES = 256 * 1024
        const val JOURNAL_PAGE = 500
        const val MAX_JOURNAL_PAGE = 5_000
    }


}
