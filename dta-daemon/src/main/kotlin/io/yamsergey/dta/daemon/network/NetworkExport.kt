package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.NetworkEntry
import io.yamsergey.dta.protocol.network.NetworkEntryDetail
import io.yamsergey.dta.protocol.network.NetworkExportResult
import io.yamsergey.dta.protocol.network.NetworkPage
import io.yamsergey.dta.protocol.network.NetworkQuery
import io.yamsergey.dta.protocol.network.Order
import io.yamsergey.dta.protocol.network.WsConnectionEntry
import io.yamsergey.dta.protocol.network.WsMessageEntry
import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Files
import java.util.Base64

/**
 * `network.export` (feature 026): the entries a `network.list` query selects, all pages, as one
 * HAR file. Full entries and bodies come through the same paths as `network.entry` and
 * `network.body`, so redaction and archive scopes apply alike. A WebSocket connection carries
 * all its messages; messages listed without their connection bring it along.
 */
class NetworkExport(
    private val page: suspend (NetworkQuery) -> NetworkPage,
    private val entry: suspend (String) -> NetworkEntryDetail,
    /** Writes the body's decoded bytes to the file; throws when it cannot. */
    private val body: suspend (String, BodyPart, File) -> Unit,
    private val version: String,
    private val pageSize: Int = NetworkQuery.MAX_LIMIT,
) {
    suspend fun export(query: NetworkQuery, output: File): NetworkExportResult {
        val listed = mutableListOf<NetworkEntry>()
        var cursor: String? = null
        var saved: io.yamsergey.dta.protocol.network.SavedFiltersApplied? = null
        do {
            val p = page(query.copy(limit = pageSize, order = Order.OLDEST_FIRST, before = cursor))
            if (cursor == null) saved = p.saved
            listed += p.entries
            cursor = p.next
        } while (cursor != null && p.entries.isNotEmpty())

        val scratch = Files.createTempDirectory("dta-export").toFile()
        var bodies = 0
        var omitted = 0
        var messages = 0
        var notExported = 0
        suspend fun content(id: String, part: BodyPart, info: BodyInfo?): Har.Content? {
            info ?: return null
            val note = if (info.completeness == Completeness.COMPLETE) null else "${wire(info.completeness)}: ${info.completenessReason}"
            if (info.storedBytes == 0L && info.completeness != Completeness.COMPLETE) {
                omitted++
                return Har.Content(info.contentType, 0, null, comment = "body not stored ($note)")
            }
            return try {
                val file = File(scratch, "${id.replace(Regex("[^A-Za-z0-9]"), "_")}-${part.name}")
                body(id, part, file)
                val bytes = file.readBytes().also { file.delete() }
                bodies++
                if (info.binary) Har.Content(info.contentType, bytes.size.toLong(), Base64.getEncoder().encodeToString(bytes), base64 = true, comment = note)
                else Har.Content(info.contentType, bytes.size.toLong(), String(bytes, Charsets.UTF_8), comment = note)
            } catch (e: Exception) {
                omitted++
                Har.Content(info.contentType, 0, null, comment = "body not exported: ${e.message ?: e.javaClass.simpleName}")
            }
        }

        val out = mutableListOf<Pair<Long, JsonObject>>()
        val sockets = linkedSetOf<String>()
        try {
            for (e in listed) {
                when (e) {
                    is HttpEntry -> {
                        val full = entry(e.id).entry as? HttpEntry ?: e
                        out += full.timeMs to Har.http(full, content(full.id, BodyPart.REQUEST, full.requestBody), content(full.id, BodyPart.RESPONSE, full.responseBody))
                    }
                    is WsConnectionEntry -> sockets += e.id
                    is WsMessageEntry -> sockets += e.connectionId
                    else -> Unit
                }
            }
            for (id in sockets) {
                val detail = runCatching { entry(id) }.getOrNull() ?: continue
                val socket = detail.entry as? WsConnectionEntry ?: continue
                val carried = detail.messages.orEmpty().map { m -> m to content(m.id, BodyPart.MESSAGE, m.payload) }
                messages += carried.size
                notExported += detail.messagesTruncated ?: 0
                out += socket.timeMs to Har.webSocket(socket, carried)
            }
        } finally {
            scratch.deleteRecursively()
        }

        output.absoluteFile.parentFile?.mkdirs()
        output.writeText(DtaJson.encodeToString(JsonObject.serializer(), Har.document(out.sortedBy { it.first }.map { it.second }, version)))
        return NetworkExportResult(output.path, entries = out.size, webSocketMessages = messages, bodies = bodies, bodiesOmitted = omitted, messagesNotExported = notExported, saved = saved)
    }

    private fun wire(c: Completeness) = c.name.lowercase().replace('_', '-')
}
