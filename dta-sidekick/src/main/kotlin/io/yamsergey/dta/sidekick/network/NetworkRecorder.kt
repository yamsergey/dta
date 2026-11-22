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
    val bodyBound: Long? = store.bounds.bodyBytes,
    /** Sees every entry as it is stored (the UI journal's network links, feature 015). */
    private val observe: (NetworkEntry) -> Unit = {},
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

    /** [startedAtMs]: when the library says the request started (Ktor gives it), else now. */
    fun httpStarted(library: String, method: String, url: String, headers: List<Header>, initiator: Initiator?, startedAtMs: Long? = null): String {
        val id = store.nextId()
        guard {
            put(HttpEntry(id, store.runId, startedAtMs ?: clock(), library, initiator, method, url, HttpState.PENDING, requestHeaders = headers))
        }
        return id
    }

    fun httpRequestBody(id: String, contentType: String?, declaredBytes: Long?, encoding: String? = null): BodyRecorder =
        bodyRecorder(id, BodyPart.REQUEST, contentType, encoding, declaredBytes) { info, _ ->
            updateHttp(id) { it.copy(requestBody = info) }
        }

    fun httpResponse(id: String, status: Int, headers: List<Header>, protocol: String?) = guard {
        updateHttp(id) { it.copy(status = status, responseHeaders = headers, protocol = protocol ?: it.protocol) }
    }

    /**
     * Completes the exchange when the returned recorder finishes; a read of the body that failed
     * (the connection cut mid-body) fails it with that error, as the app got it (FR-006).
     */
    fun httpResponseBody(id: String, contentType: String?, encoding: String?, declaredBytes: Long?, endedAtMs: Long? = null): BodyRecorder =
        bodyRecorder(id, BodyPart.RESPONSE, contentType, encoding, declaredBytes) { info, capture ->
            updateHttp(id) {
                val ended = (endedAtMs ?: clock()) - it.timeMs
                val error = capture.error
                if (error != null && it.state != HttpState.FAILED) it.copy(responseBody = info, state = HttpState.FAILED, failure = describe(error), durationMs = ended)
                else it.copy(responseBody = info, state = terminal(it), durationMs = ended)
            }
        }

    /** For responses without a body. */
    fun httpComplete(id: String, endedAtMs: Long? = null) = guard {
        updateHttp(id) { it.copy(state = terminal(it), durationMs = (endedAtMs ?: clock()) - it.timeMs) }
    }

    fun httpFailed(id: String, error: Throwable, endedAtMs: Long? = null) = guard {
        updateHttp(id) { it.copy(state = HttpState.FAILED, failure = describe(error), durationMs = (endedAtMs ?: clock()) - it.timeMs) }
    }

    /**
     * The error the library raised to the app for an exchange that already failed on the connection,
     * when it differs (OkHttp turns the socket a cancel closed into "Canceled"): the failure names what
     * the app got first, then what happened on the connection. Timing stays the connection's.
     */
    fun httpAppFailure(id: String, error: Throwable) = guard {
        updateHttp(id) {
            val app = describe(error)
            val wire = it.failure
            if (it.state != HttpState.FAILED || wire == null || wire == app || wire.startsWith(app)) it
            else it.copy(failure = "$app (on the connection: $wire)")
        }
    }

    /** Feature 060: a failure told as text (a web page's request: there is no exception). */
    fun httpFailed(id: String, failure: String, endedAtMs: Long? = null) = guard {
        updateHttp(id) { it.copy(state = HttpState.FAILED, failure = failure, durationMs = (endedAtMs ?: clock()) - it.timeMs) }
    }

    /** A failure recorded earlier wins over a later body end. */
    private fun terminal(entry: HttpEntry) = if (entry.state == HttpState.FAILED) HttpState.FAILED else HttpState.COMPLETE

    // ---- WebSocket --------------------------------------------------------------------

    fun wsStarted(library: String, url: String, handshakeHeaders: List<Header>, initiator: Initiator?, startedAtMs: Long? = null): String {
        val id = store.nextId()
        guard {
            put(WsConnectionEntry(id, store.runId, startedAtMs ?: clock(), library, initiator, url, WsState.CONNECTING, handshakeHeaders = handshakeHeaders))
        }
        return id
    }

    /** The server's handshake response headers replace the request's. */
    fun wsHandshake(id: String, responseHeaders: List<Header>) = guard {
        updateWs(id) { it.copy(handshakeHeaders = responseHeaders) }
    }

    fun wsOpened(id: String, handshakeHeaders: List<Header>? = null, atMs: Long? = null) = guard {
        updateWs(id) { it.copy(state = WsState.OPEN, openedAtMs = atMs ?: clock(), handshakeHeaders = handshakeHeaders ?: it.handshakeHeaders) }
    }

    /** Stores the payload (bounded) and returns the message id; null if the connection is unknown. */
    fun wsMessage(
        connectionId: String,
        direction: Direction,
        opcode: MessageOpcode,
        payload: ByteArray,
        initiator: Initiator?,
        injected: io.yamsergey.dta.protocol.network.WsInjection? = null,
        atMs: Long? = null,
    ): String? =
        guardOrNull {
            val connection = store.get(connectionId) as? WsConnectionEntry ?: return@guardOrNull null
            val id = store.nextId()
            val time = atMs ?: clock()
            var info: BodyInfo? = inRecord(opcode, payload)
            // A small text message is all in its preview: its record holds it, no file is kept
            // (found under load, 2026-11-19: a file per message was most of the device's disk).
            if (info == null) BodyRecorder(store.openBodySink(id, BodyPart.MESSAGE), bodyBound) { capture ->
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
            put(WsMessageEntry(id, store.runId, time, connection.library, initiator, connectionId, direction, opcode, info!!, url = connection.url, injected = injected))
            // The count alone: written to the journal at most once a second (the store's upsertCount).
            updateWs(connectionId, countOnly = true) { it.copy(messageCount = it.messageCount + 1) }
            id
        }

    /**
     * The app sent a close frame: the closing handshake started on this side. Its code and reason are
     * the connection's close (the first close frame on the wire); the state stays until [wsClosed].
     */
    fun wsClosing(id: String, code: Int?, reason: String?) = guard {
        updateWs(id) {
            if (it.state != WsState.OPEN && it.state != WsState.CONNECTING || it.closeCode != null) it
            else it.copy(closeCode = code, closeReason = reason)
        }
    }

    /** The closing handshake ended; a close frame the app sent first ([wsClosing]) stays the close. */
    fun wsClosed(id: String, code: Int?, reason: String?, atMs: Long? = null) = guard {
        updateWs(id) {
            if (it.state == WsState.CLOSED || it.state == WsState.FAILED) it
            else if (it.closeCode != null) it.copy(state = WsState.CLOSED, closedAtMs = atMs ?: clock())
            else it.copy(state = WsState.CLOSED, closedAtMs = atMs ?: clock(), closeCode = code, closeReason = reason)
        }
    }

    fun wsFailed(id: String, error: Throwable) = guard {
        updateWs(id) {
            if (it.state == WsState.CLOSED) it
            else it.copy(state = WsState.FAILED, closedAtMs = clock(), failure = describe(error))
        }
    }

    /** Feature 060: a web page's socket failed, told as text. */
    fun wsFailed(id: String, failure: String, atMs: Long? = null) = guard {
        updateWs(id) {
            if (it.state == WsState.CLOSED) it
            else it.copy(state = WsState.FAILED, closedAtMs = atMs ?: clock(), failure = failure)
        }
    }

    /** The library a connection id belongs to, for adapters that map sockets to ids. */
    fun connection(id: String): WsConnectionEntry? = store.get(id) as? WsConnectionEntry

    /** The history as the push injector sees it (feature 020): pushes are incoming messages, marked. */
    fun pushRecorder(): PushRecorder = object : PushRecorder {
        override val runId: String get() = store.runId
        override fun connection(id: String) = this@NetworkRecorder.connection(id)
        override fun record(connectionId: String, message: PushMessage, injection: io.yamsergey.dta.protocol.network.WsInjection) =
            wsMessage(connectionId, Direction.IN, message.opcode, message.payload, initiator = null, injected = injection)
    }

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
                    // Scripts act on network traffic only.
                    is io.yamsergey.dta.protocol.network.LinkEntry -> return@guard
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
        onInfo: (BodyInfo, BodyCapture) -> Unit,
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
                    capture,
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

    /** The payload as its record holds it, when the preview is all of it; else null (it is stored as a file). */
    private fun inRecord(opcode: MessageOpcode, payload: ByteArray): BodyInfo? {
        if (opcode != MessageOpcode.TEXT || payload.size > BodyInfo.PREVIEW_CHARS) return null
        if (bodyBound != null && payload.size > bodyBound) return null
        val text = BodyCodec.preview(payload, "text/plain") ?: return null
        val info = BodyInfo(BodyPart.MESSAGE, storedBytes = payload.size.toLong(), declaredBytes = payload.size.toLong(), completeness = io.yamsergey.dta.protocol.network.Completeness.COMPLETE, preview = text)
        return info.takeIf { it.wholeText() != null && text.toByteArray(Charsets.UTF_8).contentEquals(payload) }
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

    private fun updateWs(id: String, countOnly: Boolean = false, change: (WsConnectionEntry) -> WsConnectionEntry) {
        synchronized(lock) {
            val current = store.get(id) as? WsConnectionEntry ?: return
            val next = change(current)
            if (next != current) put(next, countOnly)
        }
    }

    /** A link that reached an activity (feature 012), built with the next id of this run. */
    fun link(build: (id: String, runId: String, timeMs: Long) -> io.yamsergey.dta.protocol.network.LinkEntry) = guard {
        put(build(store.nextId(), store.runId, clock()))
    }

    private fun put(entry: NetworkEntry, countOnly: Boolean = false) {
        val change = if (countOnly) store.upsertCount(entry) else store.upsert(entry)
        try {
            observe(entry)
            // Feature 068: a journal reaches this computer as its feed; only a history kept in memory is sent entry by entry.
            if (!store.isPersisted) publish(
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
