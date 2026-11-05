package io.yamsergey.dta.daemon.network

import kotlinx.coroutines.flow.onSubscription
import io.yamsergey.dta.daemon.connection.AppConnection
import io.yamsergey.dta.daemon.network.proto.BodyDecoding
import io.yamsergey.dta.daemon.network.proto.BodyTarget
import io.yamsergey.dta.daemon.network.proto.Decoded
import io.yamsergey.dta.daemon.network.proto.MappingPart
import io.yamsergey.dta.protocol.network.DecodingInfo
import io.yamsergey.dta.protocol.network.DecodingOutcome
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.WsConnectionEntry
import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.network.BodyCodec
import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.ClearResult
import io.yamsergey.dta.protocol.network.NetworkBodyResult
import io.yamsergey.dta.protocol.network.NetworkEntry
import io.yamsergey.dta.protocol.network.RedactionInfo
import io.yamsergey.dta.protocol.network.Redactor
import io.yamsergey.dta.protocol.network.WsMessageEntry
import io.yamsergey.dta.protocol.network.NetworkEntryDetail
import io.yamsergey.dta.protocol.network.NetworkEntryEvent
import io.yamsergey.dta.protocol.network.NetworkPage
import io.yamsergey.dta.protocol.network.NetworkQuery
import io.yamsergey.dta.protocol.rpc.DegradationMarker
import io.yamsergey.dta.protocol.rpc.DtaJson
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Daemon side of the `network` capability (contract §7). There is no fallback: without
 * a reachable sidekick the connection error propagates (nothing could stand in for
 * capture, so nothing is substituted silently).
 */
class NetworkService(
    private val decoding: BodyDecoding = BodyDecoding.NONE,
    /** The host's index of the app's history (feature 011); null asks the app itself. */
    private val host: HostIndex? = null,
    private val connection: suspend () -> AppConnection,
) {

    suspend fun list(query: NetworkQuery = NetworkQuery()): NetworkPage {
        val page = host?.list(query) ?: HostIndex.appAnswers(connection(), query)
        if (!query.redact) return page
        val (entries, info) = redactAll(page.entries)
        return page.copy(entries = entries, redaction = info, degraded = withheldMarker(page.degraded, info))
    }

    /** Feature 040: entries with search hits; the host copy searches (the app alone cannot). */
    suspend fun search(query: NetworkQuery): io.yamsergey.dta.protocol.network.SearchPage =
        host?.search(query) ?: io.yamsergey.dta.protocol.network.SearchPage(
            emptyList(), 0, searched = 0,
            degraded = listOf(DegradationMarker("network", DegradationCodes.NETWORK_SEARCH_UNAVAILABLE)),
        ).also { query.resolved() }

    suspend fun entry(id: String, redact: Boolean = false): NetworkEntryDetail {
        val detail = host?.entry(id) ?: DtaJson.decodeFromJsonElement(
            NetworkEntryDetail.serializer(),
            connection().request("network_entry", buildJsonObject { put("id", id) }),
        )
        if (!redact) return detail
        val (entries, info) = redactAll(listOf(detail.entry) + detail.messages.orEmpty())
        return detail.copy(
            entry = entries.first(),
            messages = detail.messages?.let { entries.drop(1).map { it as WsMessageEntry } },
            redaction = info,
            degraded = withheldMarker(detail.degraded, info),
        )
    }

    /**
     * Feature 041: whole texts of text messages for format rules — the host's copy, else (when
     * [fetch]) the app's, at most [FULL_TEXT_FETCHES] asked of the app per call and [FULL_TEXT_MAX]
     * bytes each. Messages [needed] says are whole in their preview are not read.
     */
    suspend fun messageTexts(messages: List<WsMessageEntry>, needed: (WsMessageEntry) -> Boolean, fetch: Boolean): Map<String, String> {
        val out = HashMap<String, String>()
        var asked = 0
        for (m in messages.asReversed()) {
            if (m.payload.binary || m.opcode == io.yamsergey.dta.protocol.network.MessageOpcode.BINARY || !needed(m)) continue
            if (m.payload.storedBytes > FULL_TEXT_MAX) continue
            val stored = host?.storedBody(m.id, BodyPart.MESSAGE)
            val pair = stored ?: if (fetch && asked++ < FULL_TEXT_FETCHES) runCatching {
                val response = connection().requestFull(
                    "network_body",
                    buildJsonObject { put("id", m.id); put("part", "message") },
                    BODY_TIMEOUT,
                )
                DtaJson.decodeFromJsonElement(NetworkBodyResult.serializer(), response.result).body to
                    ByteArrayOutputStream().apply { response.binaryPayloads.forEach { write(it) } }.toByteArray()
            }.getOrNull() else null
            val (info, wire) = pair ?: continue
            val decoded = BodyCodec.decode(wire, info.encoding)
            if (decoded.decoded) out[m.id] = String(decoded.bytes, Charsets.UTF_8)
        }
        return out
    }

    /** Redaction happens here and only here (one redactor, applied once — VI). */
    private fun redactAll(entries: List<NetworkEntry>): Pair<List<NetworkEntry>, RedactionInfo> {
        val masked = mutableListOf<String>()
        val withheld = mutableListOf<String>()
        val redacted = entries.map { entry ->
            val (r, info) = Redactor.entry(entry)
            masked += info.masked
            withheld += info.withheld
            r
        }
        return redacted to RedactionInfo(masked = masked, withheld = withheld)
    }

    private fun withheldMarker(degraded: List<DegradationMarker>?, info: RedactionInfo): List<DegradationMarker>? =
        if (info.withheld.isEmpty()) degraded
        else degraded.orEmpty() + DegradationMarker("network", DegradationCodes.NETWORK_REDACTION_WITHHELD)

    /**
     * One body, decoded once here (the sidekick only stores wire bytes). Text output
     * refuses binary bodies; file output writes decoded or raw bytes and their sha256.
     */
    suspend fun body(
        id: String,
        part: BodyPart,
        output: BodyOutput = BodyOutput.Text,
        redact: Boolean = false,
        allowUnredacted: Boolean = false,
    ): BodyResult {
        // The host's copy first (archived bodies exist nowhere else, feature 011), else the app's.
        val (info, wire) = host?.storedBody(id, part) ?: run {
            val response = connection().requestFull(
                "network_body",
                buildJsonObject {
                    put("id", id)
                    put("part", DtaJson.encodeToJsonElement(BodyPart.serializer(), part).jsonPrimitive.content)
                },
                BODY_TIMEOUT,
            )
            DtaJson.decodeFromJsonElement(NetworkBodyResult.serializer(), response.result).body to
                ByteArrayOutputStream().apply { response.binaryPayloads.forEach { write(it) } }.toByteArray()
        }
        val degraded = if (!info.completeness.isGap) null
        else listOf(DegradationMarker("network", DegradationCodes.NETWORK_BODY_INCOMPLETE))

        val structured = structured(id, part, info, wire)
        val decoding = structured.info
        if (redact && !allowUnredacted) return redactedBody(id, part, info, wire, structured, output, degraded)
        val overridden = if (redact) RedactionInfo(applied = false) else null

        return when (output) {
            BodyOutput.Text -> when (decoding.outcome) {
                DecodingOutcome.DECODED, DecodingOutcome.DECODED_WITH_WARNINGS -> {
                    val json = structured.json!!
                    BodyResult(info, text = json, bytes = json.length.toLong(), degraded = degraded, redaction = overridden, decoding = decoding)
                }
                DecodingOutcome.NOT_DECODED -> BodyResult(
                    info, bytes = 0, degraded = degraded, redaction = overridden, decoding = decoding,
                    note = "not decoded: ${decoding.reason}; fetch the bytes with the output parameter (dta network body --output <file>)",
                )
                DecodingOutcome.NOT_APPLICABLE -> {
                    if (info.binary) {
                        throw BinaryBodyException(
                            "binary body (${info.contentType ?: "unknown type"}, ${humanBytes(info.storedBytes)}) — " +
                                "fetch the bytes with the output parameter (dta network body --output <file>), or add its " +
                                "protobuf schema (network.schema.add, then network.mapping.add) to read it as JSON",
                        )
                    }
                    val decoded = BodyCodec.decode(wire, info.encoding)
                    BodyResult(info, text = String(decoded.bytes, Charsets.UTF_8), bytes = decoded.bytes.size.toLong(), note = decoded.note, degraded = degraded, redaction = overridden, decoding = decoding)
                }
            }
            is BodyOutput.File -> {
                val (bytes, note) = when {
                    output.structured -> (structured.json ?: throw BodyNotDecodedException(decoding)).toByteArray(Charsets.UTF_8) to null
                    output.decoded -> BodyCodec.decode(wire, info.encoding).let { it.bytes to it.note }
                    else -> wire to null
                }
                val file = File(output.path)
                file.absoluteFile.parentFile?.mkdirs()
                file.writeBytes(bytes)
                BodyResult(info, file = file.absolutePath, bytes = bytes.size.toLong(), sha256 = sha256(bytes), note = note, degraded = degraded, redaction = overridden, decoding = decoding)
            }
        }
    }

    /** The one decoding of a body (FR-021): the same for text, file and redaction. */
    private suspend fun structured(id: String, part: BodyPart, info: BodyInfo, wire: ByteArray): Decoded {
        val notApplicable = Decoded(null, DecodingInfo(DecodingOutcome.NOT_APPLICABLE))
        if (decoding === BodyDecoding.NONE) return notApplicable
        val target = target(id, part, info) ?: return notApplicable
        return decoding.decode(target, wire, info)
    }

    /** What the rules match: the exchange's URL and method, or the socket's URL and the message direction. */
    private suspend fun target(id: String, part: BodyPart, info: BodyInfo): BodyTarget? {
        val mappingPart = when (part) {
            BodyPart.REQUEST, BodyPart.APP_REQUEST -> MappingPart.REQUEST
            BodyPart.RESPONSE, BodyPart.APP_RESPONSE -> MappingPart.RESPONSE
            BodyPart.MESSAGE, BodyPart.APP_MESSAGE -> MappingPart.MESSAGE
        }
        return when (val entry = entry(id).entry) {
            is HttpEntry -> BodyTarget(entry.url, entry.method, mappingPart, null, info.contentType)
            is WsMessageEntry -> {
                val socket = entry(entry.connectionId).entry as? WsConnectionEntry ?: return null
                BodyTarget(socket.url, null, mappingPart, entry.direction, info.contentType)
            }
            else -> null
        }
    }

    /**
     * Decoded protobuf is redacted as JSON (FR-016, the same decoding as text output);
     * other binary and uninspectable bodies are withheld (FR-032, FR-018); inspectable
     * text comes back masked.
     */
    private fun redactedBody(
        id: String,
        part: BodyPart,
        info: BodyInfo,
        wire: ByteArray,
        structured: Decoded,
        output: BodyOutput,
        degraded: List<DegradationMarker>?,
    ): BodyResult {
        val location = "entry $id ${part.name.lowercase()} body"
        val decoding = structured.info
        fun withheld(why: String) = BodyResult(
            info, bytes = 0,
            redaction = RedactionInfo(withheld = listOf(why)),
            degraded = degraded.orEmpty() + DegradationMarker("network", DegradationCodes.NETWORK_REDACTION_WITHHELD),
            note = "withheld: it cannot be redacted; pass allowUnredacted = true to get it as is",
            decoding = decoding,
        )
        val r = when {
            structured.json != null -> Redactor.body(structured.json, "application/json", location)
            info.binary -> return withheld("$location (binary${decoding.reason?.let { ", not decoded: $it" } ?: ""})")
            else -> {
                val decoded = BodyCodec.decode(wire, info.encoding)
                if (!decoded.decoded) return withheld("$location (${info.encoding} not decoded)")
                Redactor.body(String(decoded.bytes, Charsets.UTF_8), info.contentType, location)
            }
        }
        val text = r.text ?: return withheld(r.withheld!!)
        val redaction = RedactionInfo(masked = r.masked)
        return when (output) {
            BodyOutput.Text -> BodyResult(info, text = text, bytes = text.length.toLong(), degraded = degraded, redaction = redaction, decoding = decoding)
            is BodyOutput.File -> {
                val bytes = text.toByteArray(Charsets.UTF_8)
                val file = File(output.path).also { it.absoluteFile.parentFile?.mkdirs(); it.writeBytes(bytes) }
                BodyResult(info, file = file.absolutePath, bytes = bytes.size.toLong(), sha256 = sha256(bytes), degraded = degraded, redaction = redaction, decoding = decoding)
            }
        }
    }

    private companion object {
        /** Bodies go up to the 32 MB bound; the default request timeout is sized for metadata. */
        val BODY_TIMEOUT = 120.seconds
        const val FULL_TEXT_FETCHES = 200
        const val FULL_TEXT_MAX = 1L shl 20
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun humanBytes(n: Long): String = when {
        n >= 1_000_000 -> "%.1f MB".format(n / 1_000_000.0)
        n >= 1_000 -> "%.1f KB".format(n / 1_000.0)
        else -> "$n B"
    }

    /** Clears the app's history; the host keeps what it had as an archived epoch (feature 011). */
    suspend fun clear(): ClearResult =
        DtaJson.decodeFromJsonElement(ClearResult.serializer(), connection().request("network_clear")).also { host?.cleared(it.clearedEntries) }

    /** Live `network.entry` events pushed by the sidekick; not replayed (use [list] for history). */
    fun events(onSubscribed: suspend () -> Unit = {}): Flow<NetworkEntryEvent> = flow {
        emitAll(
            connection().events
                .onSubscription { onSubscribed() }
                .filter { it.name == NetworkEntryEvent.NAME }
                .mapNotNull { event ->
                    event.data?.let { runCatching { DtaJson.decodeFromJsonElement(NetworkEntryEvent.serializer(), it) }.getOrNull() }
                },
        )
    }
}
