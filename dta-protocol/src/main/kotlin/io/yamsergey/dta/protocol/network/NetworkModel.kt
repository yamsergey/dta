package io.yamsergey.dta.protocol.network

import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import io.yamsergey.dta.protocol.rpc.DegradationMarker
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shapes of the `network` capability (feature 004, data-model.md). HTTP
 * exchanges, WebSocket connections and WebSocket messages are kinds of one entry,
 * listed together (FR-001). Ids are strings, stable across process runs.
 */

@Serializable
data class Header(val name: String, val value: String)

@Serializable
enum class HttpState {
    @SerialName("pending") PENDING,
    @SerialName("complete") COMPLETE,
    @SerialName("failed") FAILED,
    @SerialName("process-ended") PROCESS_ENDED,
}

@Serializable
enum class WsState {
    @SerialName("connecting") CONNECTING,
    @SerialName("open") OPEN,
    @SerialName("closed") CLOSED,
    @SerialName("failed") FAILED,
    @SerialName("process-ended") PROCESS_ENDED,
}

@Serializable
enum class Direction {
    @SerialName("out") OUT,
    @SerialName("in") IN,
}

@Serializable
enum class MessageOpcode {
    @SerialName("text") TEXT,
    @SerialName("binary") BINARY,
}

@Serializable
enum class BodyPart {
    @SerialName("request") REQUEST,
    @SerialName("response") RESPONSE,
    @SerialName("message") MESSAGE,
    /** Feature 005: the request as the app built it, when a script changed it. */
    @SerialName("app-request") APP_REQUEST,
    /** Feature 005: the response as delivered to the app, when a script changed it. */
    @SerialName("app-response") APP_RESPONSE,
    /** Feature 005: a message as the app sent or received it, when a script changed it. */
    @SerialName("app-message") APP_MESSAGE,
}

/** How much of a body is stored; anything but [COMPLETE] carries a reason (FR-017). */
@Serializable
enum class Completeness {
    /** Every byte that crossed the app boundary is stored. */
    @SerialName("complete") COMPLETE,
    /** The per-body bound was hit; storedBytes is the bound. */
    @SerialName("truncated") TRUNCATED,
    /** The app stopped reading, or the stream closed early. */
    @SerialName("partially-observed") PARTIALLY_OBSERVED,
    /** Not observable without changing app behavior (FR-018). */
    @SerialName("omitted") OMITTED,
    /** Stored bytes were lost (eviction, I/O failure, storage unavailable). */
    @SerialName("dropped") DROPPED,
}

@Serializable
data class BodyInfo(
    val part: BodyPart,
    /** Wire bytes on the device (still Content-Encoded). */
    val storedBytes: Long,
    val declaredBytes: Long? = null,
    val contentType: String? = null,
    /** Wire Content-Encoding (gzip, deflate, br, …). */
    val encoding: String? = null,
    val binary: Boolean = false,
    val completeness: Completeness,
    val completenessReason: String? = null,
    /** At most [PREVIEW_CHARS] of decoded text; null for binary. */
    val preview: String? = null,
) {
    init {
        require(completeness == Completeness.COMPLETE || completenessReason != null) {
            "BodyInfo $part with completeness $completeness requires a reason (no silent degradation)"
        }
    }

    companion object {
        const val PREVIEW_CHARS = 256
    }
}

@Serializable
enum class InitiatorSite {
    /** Synchronous call (execute, URLConnection use). */
    @SerialName("call") CALL,
    /** Where an asynchronous call was scheduled, not the worker that ran it. */
    @SerialName("enqueue") ENQUEUE,
    /** An outgoing WebSocket message. */
    @SerialName("send") SEND,
    /** A WebSocket connection or URLConnection being opened. */
    @SerialName("connect") CONNECT,
}

@Serializable
data class StackFrame(
    val className: String,
    val method: String,
    val file: String? = null,
    val line: Int? = null,
    /** App code (true) or library/framework code (false). */
    val app: Boolean,
)

/** The stack at the moment app code issued the request (FR-009..012). */
@Serializable
data class Initiator(
    val thread: String,
    val site: InitiatorSite,
    val frames: List<StackFrame>? = null,
    /** Index into [frames] of the first app frame. */
    val firstAppFrame: Int? = null,
    /** Frames cut by the frame cap. */
    val truncatedFrames: Int? = null,
    /** Why there are no frames (FR-012); set iff [frames] is null. */
    val missing: String? = null,
) {
    init {
        require(frames != null || missing != null) { "Initiator without frames must state why (FR-012)" }
    }

    companion object {
        const val MAX_FRAMES = 64
    }
}

/** One captured unit of network activity; `type` is http | ws-connection | ws-message. */
@Serializable
sealed interface NetworkEntry {
    /** `<runId>:<seq>`. */
    val id: String
    val runId: String
    /** Start time: request start, connect, or message time. */
    val timeMs: Long
    /** okhttp3 | urlconnection | okhttp3-ws | nv-websocket | java-websocket */
    val library: String
    /** Null only on incoming WebSocket messages. */
    val initiator: Initiator?
    /** What the network script did to this entry (feature 005); null when untouched. */
    val intervention: InterventionRecord?

    /** List form: everything but headers. */
    fun summary(): NetworkEntry
}

@Serializable
@SerialName("http")
data class HttpEntry(
    override val id: String,
    override val runId: String,
    override val timeMs: Long,
    override val library: String,
    override val initiator: Initiator? = null,
    val method: String,
    val url: String,
    val state: HttpState,
    val status: Int? = null,
    /** Exception class and message when [state] is failed. */
    val failure: String? = null,
    val durationMs: Long? = null,
    val requestHeaders: List<Header>? = null,
    val responseHeaders: List<Header>? = null,
    val requestBody: BodyInfo? = null,
    val responseBody: BodyInfo? = null,
    /** h2, http/1.1 when known. */
    val protocol: String? = null,
    override val intervention: InterventionRecord? = null,
    /** The request as the app built it, when the script changed it; the fields above are the wire view. */
    val appRequest: HttpRequestSnapshot? = null,
    /** The response as delivered to the app, when the script changed it. */
    val appResponse: HttpResponseSnapshot? = null,
) : NetworkEntry {
    override fun summary() = copy(
        requestHeaders = null,
        responseHeaders = null,
        appRequest = appRequest?.copy(headers = emptyList()),
        appResponse = appResponse?.copy(headers = emptyList()),
    )
}

@Serializable
@SerialName("ws-connection")
data class WsConnectionEntry(
    override val id: String,
    override val runId: String,
    override val timeMs: Long,
    override val library: String,
    override val initiator: Initiator? = null,
    val url: String,
    val state: WsState,
    val openedAtMs: Long? = null,
    val closedAtMs: Long? = null,
    val closeCode: Int? = null,
    val closeReason: String? = null,
    val failure: String? = null,
    val handshakeHeaders: List<Header>? = null,
    /** Sent + received. */
    val messageCount: Int = 0,
    override val intervention: InterventionRecord? = null,
) : NetworkEntry {
    override fun summary() = copy(handshakeHeaders = null)
}

@Serializable
@SerialName("ws-message")
data class WsMessageEntry(
    override val id: String,
    override val runId: String,
    override val timeMs: Long,
    override val library: String,
    override val initiator: Initiator? = null,
    val connectionId: String,
    val direction: Direction,
    val opcode: MessageOpcode,
    /** What went on (or came off) the wire. */
    val payload: BodyInfo,
    override val intervention: InterventionRecord? = null,
    /** What the app sent or received, when the script changed it. */
    val appPayload: BodyInfo? = null,
    /** The connection's URL, so a message names its socket without the connection entry. */
    val url: String? = null,
) : NetworkEntry {
    override fun summary() = this
}

// ---- feature 005: intervention -------------------------------------------------------

@Serializable
enum class ScriptHandler {
    @SerialName("request") REQUEST,
    @SerialName("response") RESPONSE,
    @SerialName("ws-send") WS_SEND,
    @SerialName("ws-receive") WS_RECEIVE,
}

@Serializable
enum class ChangedField {
    @SerialName("url") URL,
    @SerialName("method") METHOD,
    @SerialName("headers") HEADERS,
    @SerialName("body") BODY,
    @SerialName("status") STATUS,
    @SerialName("payload") PAYLOAD,
    /** Feature 006: the needle's UTF-8 bytes were found inside a binary body. */
    @SerialName("request-body-binary") REQUEST_BODY_BINARY,
    @SerialName("response-body-binary") RESPONSE_BODY_BINARY,
    @SerialName("payload-binary") PAYLOAD_BINARY,
}

/** What the network script did to one entry (FR-016): never a silent intervention. */
@Serializable
data class InterventionRecord(
    val scriptId: String,
    /** Handlers that ran and changed something or had an effect. */
    val handlers: List<ScriptHandler> = emptyList(),
    val changed: List<ChangedField> = emptyList(),
    /** Time spent in `sleep()`. */
    val delayMs: Long? = null,
    /** Time spent in the script, excluding `sleep()`. */
    val scriptMs: Long = 0,
    val throttleBytesPerSecond: Long? = null,
    /** Answered by the script: nothing went on the wire. */
    val mocked: Boolean = false,
    /** The app saw a failure (HTTP) or the message was not delivered (WebSocket). */
    val dropped: Boolean = false,
    /** A handler error; the traffic passed unchanged. */
    val error: String? = null,
    /** What happened, in the script's own words (`description()`); handlers' words joined by "; ". */
    val description: String? = null,
)

@Serializable
data class HttpRequestSnapshot(
    val url: String,
    val method: String,
    val headers: List<Header>,
    val body: BodyInfo? = null,
)

@Serializable
data class HttpResponseSnapshot(
    val status: Int,
    val headers: List<Header>,
    val body: BodyInfo? = null,
)

@Serializable
enum class RunEnd {
    /** The process died without the run being closed; inferred at the next start. */
    @SerialName("process-ended") PROCESS_ENDED,
}

@Serializable
data class ProcessRun(
    /** `r<startEpochMs>-<pid>`. */
    val runId: String,
    val pid: Int,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    /** Null for the live run. */
    val ended: RunEnd? = null,
)

/** What a page was computed over (FR-022, FR-026, FR-029, FR-035). Always present. */
@Serializable
data class Coverage(
    val searched: Int,
    val stored: Int,
    val earliestMs: Long? = null,
    val evicted: Int = 0,
    val dropped: Int = 0,
    val persisted: Boolean,
    val runs: List<ProcessRun> = emptyList(),
    /** e.g. `binary bodies (12)`. */
    val notSearched: List<String>? = null,
    val clearedAtMs: Long? = null,
)

@Serializable
data class RedactionInfo(
    val applied: Boolean = true,
    /** Locations masked, e.g. `entry r..:4 request.header Authorization`. */
    val masked: List<String> = emptyList(),
    /** Bodies/payloads not returned because they could not be redacted. */
    val withheld: List<String> = emptyList(),
)

@Serializable
enum class MatchLocation {
    @SerialName("url") URL,
    @SerialName("request-body") REQUEST_BODY,
    @SerialName("response-body") RESPONSE_BODY,
    @SerialName("payload") PAYLOAD,
    /** Feature 006: the needle's UTF-8 bytes were found inside a binary body. */
    @SerialName("request-body-binary") REQUEST_BODY_BINARY,
    @SerialName("response-body-binary") RESPONSE_BODY_BINARY,
    @SerialName("payload-binary") PAYLOAD_BINARY,
}

/** Result of `network_list`. */
@Serializable
data class NetworkPage(
    val entries: List<NetworkEntry>,
    val matches: Map<String, List<MatchLocation>>? = null,
    /** Matching entries before the limit. */
    val total: Int,
    /** Cursor for the next page when [total] exceeds what was returned. */
    val next: String? = null,
    val coverage: Coverage,
    val redaction: RedactionInfo? = null,
    val degraded: List<DegradationMarker>? = null,
)

/** Result of `network_entry`. */
@Serializable
data class NetworkEntryDetail(
    val entry: NetworkEntry,
    /** ws-connection only, chronological, summary form. */
    val messages: List<WsMessageEntry>? = null,
    /** Messages beyond [MAX_MESSAGES] not included. */
    val messagesTruncated: Int? = null,
    val redaction: RedactionInfo? = null,
    val degraded: List<DegradationMarker>? = null,
) {
    companion object {
        const val MAX_MESSAGES = 1000
    }
}

/** JSON part of `network_body`; the wire bytes follow as binary frames. */
@Serializable
data class NetworkBodyResult(val body: BodyInfo)

/** Result of `network_clear`. */
@Serializable
data class ClearResult(val clearedEntries: Int, val clearedAtMs: Long)

@Serializable
enum class EntryChange {
    @SerialName("created") CREATED,
    @SerialName("updated") UPDATED,
}

/** Data of the `network.entry` event. */
@Serializable
data class NetworkEntryEvent(val entry: NetworkEntry, val change: EntryChange) {
    companion object {
        const val NAME = "network.entry"
        const val DROPPED = "network.events-dropped"
    }
}

/** Per-library capture status in the `network` capability. */
@Serializable
data class LibraryStatus(
    val name: String,
    val status: CapabilityStatus.Status,
    val reason: String? = null,
)

/** Storage bounds stated by the `network` capability. */
@Serializable
data class NetworkBounds(val historyBytes: Long, val bodyBytes: Long)

object NetworkLibraries {
    const val OKHTTP3 = "okhttp3"
    const val URLCONNECTION = "urlconnection"
    const val OKHTTP3_WS = "okhttp3-ws"
    const val NV_WEBSOCKET = "nv-websocket"
    const val JAVA_WEBSOCKET = "java-websocket"
    /** Detected only: its traffic bypasses the captured HTTP stack (feature 006). */
    const val GRPC = "grpc"

    val ALL = listOf(OKHTTP3, URLCONNECTION, OKHTTP3_WS, NV_WEBSOCKET, JAVA_WEBSOCKET, GRPC)
}
