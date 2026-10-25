package io.yamsergey.dta.sidekick.network

import io.yamsergey.dta.protocol.network.BodyCodec
import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.Direction
import io.yamsergey.dta.protocol.network.EntryChange
import io.yamsergey.dta.protocol.network.Header
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpRequestSnapshot
import io.yamsergey.dta.protocol.network.HttpResponseSnapshot
import io.yamsergey.dta.protocol.network.InterventionRecord
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.Initiator
import io.yamsergey.dta.protocol.network.MessageOpcode
import io.yamsergey.dta.protocol.network.NetworkEntry
import io.yamsergey.dta.protocol.network.NetworkEntryEvent
import io.yamsergey.dta.protocol.network.WsConnectionEntry
import io.yamsergey.dta.protocol.network.WsMessageEntry
import io.yamsergey.dta.protocol.network.WsState
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.sidekick.network.script.InterventionDiff
import io.yamsergey.dta.sidekick.network.store.NetworkStore
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.atomic.AtomicLong

/**
 * The single sink of network capture: library adapters report lifecycle steps here,
 * and every change is stored and announced as a `network.entry` event (constitution VI).
 * Adapters stay thin and library-specific; entry shapes, ids and state transitions live
 * here only. Never throws into the adapter (and so never into the app): failures are
 * counted in [errorCount].
 */
class NetworkRecorder(
    private val store: NetworkStore,
    private val clock: () -> Long = System::currentTimeMillis,
    val bodyBound: Long = store.bounds.bodyBytes,
    private val publish: (String, JsonElement?) -> Unit,
) {
    private companion object {
        /** Decoded bytes read for sniffing and the ≤256-char preview (UTF-8 needs up to 4 per char). */
        const val PREVIEW_HEAD_BYTES = 1024
    }

    private val errors = AtomicLong()
    private val lock = Any()

    @Volatile
    var lastError: Throwable? = null
        private set

    val errorCount: Long get() = errors.get()

    // ---- HTTP -------------------------------------------------------------------------

    fun httpStarted(library: String, method: String, url: String, headers: List<Header>, initiator: Initiator?): String {
        val id = store.nextId()
        guard {
            put(HttpEntry(id, store.runId, clock(), library, initiator, method, url, HttpState.PENDING, requestHeaders = headers))
        }
        return id
    }

    fun httpRequestBody(id: String, contentType: String?, declaredBytes: Long?, encoding: String? = null): BodyRecorder =
        bodyRecorder(id, BodyPart.REQUEST, contentType, encoding, declaredBytes) { info ->
            updateHttp(id) { it.copy(requestBody = info) }
        }

    fun httpResponse(id: String, status: Int, headers: List<Header>, protocol: String?) = guard {
        updateHttp(id) { it.copy(status = status, responseHeaders = headers, protocol = protocol ?: it.protocol) }
    }

    /** Completes the exchange when the returned recorder finishes. */
    fun httpResponseBody(id: String, contentType: String?, encoding: String?, declaredBytes: Long?): BodyRecorder =
        bodyRecorder(id, BodyPart.RESPONSE, contentType, encoding, declaredBytes) { info ->
            updateHttp(id) { it.copy(responseBody = info, state = terminal(it), durationMs = clock() - it.timeMs) }
        }

    /** For responses without a body. */
    fun httpComplete(id: String) = guard {
        updateHttp(id) { it.copy(state = terminal(it), durationMs = clock() - it.timeMs) }
    }

    fun httpFailed(id: String, error: Throwable) = guard {
        updateHttp(id) { it.copy(state = HttpState.FAILED, failure = describe(error), durationMs = clock() - it.timeMs) }
    }

    /** A failure recorded earlier wins over a later body end. */
    private fun terminal(entry: HttpEntry) = if (entry.state == HttpState.FAILED) HttpState.FAILED else HttpState.COMPLETE

    // ---- WebSocket --------------------------------------------------------------------

    fun wsStarted(library: String, url: String, handshakeHeaders: List<Header>, initiator: Initiator?): String {
        val id = store.nextId()
        guard {
            put(WsConnectionEntry(id, store.runId, clock(), library, initiator, url, WsState.CONNECTING, handshakeHeaders = handshakeHeaders))
        }
        return id
    }

    /** The server's handshake response headers replace the request's. */
    fun wsHandshake(id: String, responseHeaders: List<Header>) = guard {
        updateWs(id) { it.copy(handshakeHeaders = responseHeaders) }
    }

    fun wsOpened(id: String, handshakeHeaders: List<Header>? = null) = guard {
        updateWs(id) { it.copy(state = WsState.OPEN, openedAtMs = clock(), handshakeHeaders = handshakeHeaders ?: it.handshakeHeaders) }
    }

    /** Stores the payload (bounded) and returns the message id; null if the connection is unknown. */
    fun wsMessage(connectionId: String, direction: Direction, opcode: MessageOpcode, payload: ByteArray, initiator: Initiator?): String? =
        guardOrNull {
            val connection = store.get(connectionId) as? WsConnectionEntry ?: return@guardOrNull null
            val id = store.nextId()
            val time = clock()
            var info: BodyInfo? = null
            BodyRecorder(store.openBodySink(id, BodyPart.MESSAGE), bodyBound) { capture ->
                val head = payload.copyOf(minOf(payload.size, PREVIEW_HEAD_BYTES))
                info = bodyInfo(BodyPart.MESSAGE, capture, contentType = null, encoding = null, declaredBytes = payload.size.toLong())
                    .copy(
                        binary = opcode == MessageOpcode.BINARY,
                        preview = if (opcode == MessageOpcode.TEXT) BodyCodec.preview(head, "text/plain") else null,
                    )
            }.apply {
                accept(payload, 0, payload.size)
                finish(endOfStream = true)
            }
            put(WsMessageEntry(id, store.runId, time, connection.library, initiator, connectionId, direction, opcode, info!!))
            updateWs(connectionId) { it.copy(messageCount = it.messageCount + 1) }
            id
        }

    fun wsClosed(id: String, code: Int?, reason: String?) = guard {
        updateWs(id) {
            if (it.state == WsState.CLOSED || it.state == WsState.FAILED) it
            else it.copy(state = WsState.CLOSED, closedAtMs = clock(), closeCode = code, closeReason = reason)
        }
    }

    fun wsFailed(id: String, error: Throwable) = guard {
        updateWs(id) {
            if (it.state == WsState.CLOSED) it
            else it.copy(state = WsState.FAILED, closedAtMs = clock(), failure = describe(error))
        }
    }

    /** The library a connection id belongs to, for adapters that map sockets to ids. */
    fun connection(id: String): WsConnectionEntry? = store.get(id) as? WsConnectionEntry

    // ---- interventions (feature 005) ----------------------------------------------------

    /** Merges what the script did into an entry of any kind. */
    fun recordIntervention(id: String, record: InterventionRecord) = guard {
        synchronized(lock) {
            val current = store.get(id) ?: return@guard
            val merged = InterventionDiff.merge(current.intervention, record)
            put(
                when (current) {
                    is HttpEntry -> current.copy(intervention = merged)
                    is WsConnectionEntry -> current.copy(intervention = merged)
                    is WsMessageEntry -> current.copy(intervention = merged)
                },
            )
        }
    }

    /** The request as the app built it, kept when the script changed it (FR-017). */
    fun httpAppRequest(id: String, url: String, method: String, headers: List<Header>, body: ByteArray?, contentType: String?) = guard {
        val info = body?.let { storeBytes(id, BodyPart.APP_REQUEST, it, contentType, encoding = null) }
        updateHttp(id) { it.copy(appRequest = HttpRequestSnapshot(url, method, headers, info)) }
    }

    /** The response as delivered to the app, kept when the script changed it (FR-017). */
    fun httpAppResponse(id: String, status: Int, headers: List<Header>, body: ByteArray?, contentType: String?) = guard {
        val info = body?.let { storeBytes(id, BodyPart.APP_RESPONSE, it, contentType, encoding = null) }
        updateHttp(id) { it.copy(appResponse = HttpResponseSnapshot(status, headers, info)) }
    }

    fun wsAppPayload(id: String, payload: ByteArray) = guard {
        val info = storeBytes(id, BodyPart.APP_MESSAGE, payload, contentType = null, encoding = null)
        synchronized(lock) {
            val current = store.get(id) as? WsMessageEntry ?: return@guard
            put(current.copy(appPayload = info))
        }
    }

    /** A request the script answered itself: nothing went on the wire. */
    fun httpMocked(
        library: String, method: String, url: String, headers: List<Header>, initiator: Initiator?,
        status: Int, responseHeaders: List<Header>, body: ByteArray, record: InterventionRecord,
    ): String {
        val id = store.nextId()
        guard {
            val contentType = responseHeaders.firstOrNull { it.name.equals("Content-Type", ignoreCase = true) }?.value
            val info = storeBytes(id, BodyPart.RESPONSE, body, contentType, encoding = null)
            put(
                HttpEntry(
                    id, store.runId, clock(), library, initiator, method, url, HttpState.COMPLETE, status = status, durationMs = 0,
                    requestHeaders = headers, responseHeaders = responseHeaders, responseBody = info, intervention = record,
                ),
            )
        }
        return id
    }

    /** A request the script dropped: the app saw a failure, nothing went on the wire. */
    fun httpDropped(
        library: String, method: String, url: String, headers: List<Header>, initiator: Initiator?,
        failure: String, record: InterventionRecord,
    ): String {
        val id = store.nextId()
        guard {
            put(
                HttpEntry(
                    id, store.runId, clock(), library, initiator, method, url, HttpState.FAILED, failure = failure, durationMs = 0,
                    requestHeaders = headers, intervention = record,
                ),
            )
        }
        return id
    }

    private fun storeBytes(id: String, part: BodyPart, bytes: ByteArray, contentType: String?, encoding: String?): BodyInfo {
        var info: BodyInfo? = null
        BodyRecorder(store.openBodySink(id, part), bodyBound) { capture ->
            val head = bytes.copyOf(minOf(bytes.size, PREVIEW_HEAD_BYTES)).takeIf { it.isNotEmpty() }
            info = bodyInfo(part, capture, contentType, encoding, bytes.size.toLong()).copy(
                binary = BodyCodec.isBinary(contentType, head ?: ByteArray(0)),
                preview = head?.let { BodyCodec.preview(it, contentType) },
            )
        }.apply {
            accept(bytes, 0, bytes.size)
            finish(endOfStream = true)
        }
        return info!!
    }

    // ---- internals --------------------------------------------------------------------

    private fun bodyRecorder(
        id: String,
        part: BodyPart,
        contentType: String?,
        encoding: String?,
        declaredBytes: Long?,
        onInfo: (BodyInfo) -> Unit,
    ): BodyRecorder {
        val sink = try {
            store.openBodySink(id, part)
        } catch (t: Throwable) {
            record(t)
            null
        }
        return BodyRecorder(sink, bodyBound, declaredBytes?.takeIf { it >= 0 }) { capture ->
            guard {
                val head = decodedHead(id, part, encoding)?.takeIf { it.isNotEmpty() }
                val info = bodyInfo(part, capture, contentType, encoding, declaredBytes)
                onInfo(
                    info.copy(
                        binary = BodyCodec.isBinary(contentType, head ?: ByteArray(0)),
                        preview = head?.let { BodyCodec.preview(it, contentType) },
                    ),
                )
            }
        }
    }

    /** The first decoded bytes of a stored body, for sniffing and the preview; null if not stored. */
    private fun decodedHead(id: String, part: BodyPart, encoding: String?): ByteArray? = try {
        store.openBody(id, part)?.use { raw ->
            val decoded = BodyCodec.decodingStream(raw, encoding) ?: return null
            val buffer = ByteArray(PREVIEW_HEAD_BYTES)
            var filled = 0
            try {
                while (filled < buffer.size) {
                    val n = decoded.read(buffer, filled, buffer.size - filled)
                    if (n < 0) break
                    filled += n
                }
            } catch (_: java.io.IOException) {
                // A truncated or partial body ends mid-stream; the head read so far is enough.
            }
            buffer.copyOf(filled)
        }
    } catch (_: Throwable) {
        null
    }

    private fun bodyInfo(part: BodyPart, capture: BodyCapture, contentType: String?, encoding: String?, declaredBytes: Long?) =
        BodyInfo(
            part = part,
            storedBytes = capture.storedBytes,
            declaredBytes = declaredBytes?.takeIf { it >= 0 },
            contentType = contentType,
            encoding = encoding,
            completeness = capture.completeness,
            completenessReason = capture.reason,
        )

    private fun updateHttp(id: String, change: (HttpEntry) -> HttpEntry) {
        synchronized(lock) {
            val current = store.get(id) as? HttpEntry ?: return
            put(change(current))
        }
    }

    private fun updateWs(id: String, change: (WsConnectionEntry) -> WsConnectionEntry) {
        synchronized(lock) {
            val current = store.get(id) as? WsConnectionEntry ?: return
            val next = change(current)
            if (next != current) put(next)
        }
    }

    private fun put(entry: NetworkEntry) {
        val change = store.upsert(entry)
        try {
            publish(
                NetworkEntryEvent.NAME,
                DtaJson.encodeToJsonElement(NetworkEntryEvent.serializer(), NetworkEntryEvent(entry.summary(), change)),
            )
        } catch (t: Throwable) {
            record(t)
        }
    }

    private fun describe(error: Throwable) = "${error.javaClass.name}: ${error.message}"

    private fun record(t: Throwable) {
        errors.incrementAndGet()
        lastError = t
    }

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            record(t)
        }
    }

    private inline fun <T> guardOrNull(block: () -> T?): T? =
        try {
            block()
        } catch (t: Throwable) {
            record(t)
            null
        }
}
