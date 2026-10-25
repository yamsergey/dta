package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.daemon.connection.AppConnection
import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.network.BodyCodec
import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.ClearResult
import io.yamsergey.dta.protocol.network.Completeness
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
import kotlinx.serialization.json.put

/**
 * Daemon side of the `network` capability (contract §7). There is no fallback: without
 * a reachable sidekick the connection error propagates (nothing could stand in for
 * capture, so nothing is substituted silently).
 */
class NetworkService(private val connection: suspend () -> AppConnection) {

    suspend fun list(query: NetworkQuery = NetworkQuery()): NetworkPage {
        val page = DtaJson.decodeFromJsonElement(NetworkPage.serializer(), connection().request("network_list", query.toJson()))
        if (!query.redact) return page
        val (entries, info) = redactAll(page.entries)
        return page.copy(entries = entries, redaction = info, degraded = withheldMarker(page.degraded, info))
    }

    suspend fun entry(id: String, redact: Boolean = false): NetworkEntryDetail {
        val detail = DtaJson.decodeFromJsonElement(
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
        val response = connection().requestFull(
            "network_body",
            buildJsonObject {
                put("id", id)
                put("part", part.name.lowercase())
            },
            BODY_TIMEOUT,
        )
        val info = DtaJson.decodeFromJsonElement(NetworkBodyResult.serializer(), response.result).body
        val wire = ByteArrayOutputStream().apply { response.binaryPayloads.forEach { write(it) } }.toByteArray()
        val degraded = if (info.completeness == Completeness.COMPLETE) null
        else listOf(DegradationMarker("network", DegradationCodes.NETWORK_BODY_INCOMPLETE))

        if (redact && !allowUnredacted) return redactedBody(id, part, info, wire, output, degraded)
        val overridden = if (redact) RedactionInfo(applied = false) else null

        return when (output) {
            BodyOutput.Text -> {
                if (info.binary) {
                    throw BinaryBodyException(
                        "binary body (${info.contentType ?: "unknown type"}, ${humanBytes(info.storedBytes)}) — " +
                            "fetch it with BodyOutput.File(path) to get the bytes",
                    )
                }
                val decoded = BodyCodec.decode(wire, info.encoding)
                BodyResult(info, text = String(decoded.bytes, Charsets.UTF_8), bytes = decoded.bytes.size.toLong(), note = decoded.note, degraded = degraded, redaction = overridden)
            }
            is BodyOutput.File -> {
                val (bytes, note) = if (output.decoded) BodyCodec.decode(wire, info.encoding).let { it.bytes to it.note } else wire to null
                val file = File(output.path)
                file.absoluteFile.parentFile?.mkdirs()
                file.writeBytes(bytes)
                BodyResult(info, file = file.absolutePath, bytes = bytes.size.toLong(), sha256 = sha256(bytes), note = note, degraded = degraded, redaction = overridden)
            }
        }
    }

    /** Binary and uninspectable bodies are withheld (FR-032); inspectable ones come back masked. */
    private fun redactedBody(
        id: String,
        part: BodyPart,
        info: BodyInfo,
        wire: ByteArray,
        output: BodyOutput,
        degraded: List<DegradationMarker>?,
    ): BodyResult {
        val location = "entry $id ${part.name.lowercase()} body"
        fun withheld(why: String) = BodyResult(
            info, bytes = 0,
            redaction = RedactionInfo(withheld = listOf(why)),
            degraded = degraded.orEmpty() + DegradationMarker("network", DegradationCodes.NETWORK_REDACTION_WITHHELD),
            note = "withheld: it cannot be redacted; pass allowUnredacted = true to get it as is",
        )
        if (info.binary) return withheld("$location (binary)")
        val decoded = BodyCodec.decode(wire, info.encoding)
        if (!decoded.decoded) return withheld("$location (${info.encoding} not decoded)")
        val r = Redactor.body(String(decoded.bytes, Charsets.UTF_8), info.contentType, location)
        val text = r.text ?: return withheld(r.withheld!!)
        val redaction = RedactionInfo(masked = r.masked)
        return when (output) {
            BodyOutput.Text -> BodyResult(info, text = text, bytes = text.length.toLong(), degraded = degraded, redaction = redaction)
            is BodyOutput.File -> {
                val bytes = text.toByteArray(Charsets.UTF_8)
                val file = File(output.path).also { it.absoluteFile.parentFile?.mkdirs(); it.writeBytes(bytes) }
                BodyResult(info, file = file.absolutePath, bytes = bytes.size.toLong(), sha256 = sha256(bytes), degraded = degraded, redaction = redaction)
            }
        }
    }

    private companion object {
        /** Bodies go up to the 32 MB bound; the default request timeout is sized for metadata. */
        val BODY_TIMEOUT = 120.seconds
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun humanBytes(n: Long): String = when {
        n >= 1_000_000 -> "%.1f MB".format(n / 1_000_000.0)
        n >= 1_000 -> "%.1f KB".format(n / 1_000.0)
        else -> "$n B"
    }

    suspend fun clear(): ClearResult =
        DtaJson.decodeFromJsonElement(ClearResult.serializer(), connection().request("network_clear"))

    /** Live `network.entry` events pushed by the sidekick; not replayed (use [list] for history). */
    fun events(): Flow<NetworkEntryEvent> = flow {
        emitAll(
            connection().events
                .filter { it.name == NetworkEntryEvent.NAME }
                .mapNotNull { event ->
                    event.data?.let { runCatching { DtaJson.decodeFromJsonElement(NetworkEntryEvent.serializer(), it) }.getOrNull() }
                },
        )
    }
}
