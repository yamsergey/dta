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
    /**
     * Feature 023: the app closed the body without reading it (SDKs reading only the status,
     * `Unit` API calls); what the close let DTA take is stored. Not a capture gap: DTA saw all
     * the app saw, so nothing marks it.
     */
    @SerialName("not-read") NOT_READ,
    ;

    /** Part of the body the app saw is missing from the capture: marked incomplete everywhere. */
    val isGap: Boolean get() = this != COMPLETE && this != NOT_READ
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

    /**
     * The body's text when the preview is all of it (complete, not encoded, not binary, and the
     * same size), else null: what a live event can match body filters against without the host copy.
     */
    fun wholeText(): String? {
        val text = preview ?: return null
        if (binary || completeness != Completeness.COMPLETE || !(encoding == null || encoding.equals("identity", true))) return null
        return text.takeIf { it.toByteArray(Charsets.UTF_8).size.toLong() == storedBytes }
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
    /** What the app was handling when it issued the call — a click, a back, an app scope (feature 016). */
    val cause: io.yamsergey.dta.protocol.timeline.CauseLink? = null,
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
    /** Pushed by DTA (feature 020): the server never sent it; [payload] is what the app received. */
    val injected: WsInjection? = null,
    /** Feature 039: what the message is (038 formats, or the app's own format rule), set by the daemon on text messages. */
    val envelope: Envelope? = null,
) : NetworkEntry {
    override fun summary() = this
}

// ---- feature 020: messages pushed to the app ---------------------------------------------

@Serializable
enum class InjectionSource {
    /** An agent or a person, through `network.ws.push`. */
    @SerialName("client") CLIENT,
    /** The network script (`push`, `conn.push`, a timer). */
    @SerialName("script") SCRIPT,
}

@Serializable
enum class InjectionDelivery {
    /** On the library's reading thread, where real messages arrive. */
    @SerialName("reading-thread") READING_THREAD,
    /** From DTA's thread: no frame arrived within the wait. */
    @SerialName("dta-thread") DTA_THREAD,
}

/** How a pushed message reached the app: never mistakable for a server message (FR-005). */
@Serializable
data class WsInjection(
    val by: InjectionSource,
    val scriptId: String? = null,
    /** Where in the script it was pushed; null for the script's top level. */
    val handler: ScriptHandler? = null,
    val delivery: InjectionDelivery,
    /** From the push (after any delay) to its delivery. */
    val waitedMs: Long,
)

/** Result of `network.ws.push`. */
@Serializable
data class WsPushResult(
    val entryId: String,
    val connectionId: String,
    val delivery: InjectionDelivery,
    val waitedMs: Long,
    /** The app's listener threw on the message (only seen when delivered from DTA's thread). */
    val listenerError: String? = null,
)

// ---- feature 012: a link that reached the app ------------------------------------------

/** How a link reached its activity. */
@Serializable
enum class LinkDelivery {
    /** The process started for it (the first activity of the process). */
    @SerialName("cold-start") COLD_START,
    /** A new activity was created for it in a running process. */
    @SerialName("created") CREATED,
    /** A running activity received it as a new intent. */
    @SerialName("new-intent") NEW_INTENT,
    /** The task was brought back from recents: the original intent again, not a new link. */
    @SerialName("from-recents") FROM_RECENTS,
}

/** One extra of the intent: its value as text, or why it was not read (feature 012 R2). */
@Serializable
data class IntentExtra(val key: String, val type: String? = null, val value: String? = null, val notRead: String? = null)

/**
 * An intent with a URI delivered to an activity of the app (feature 012): the first kind of the
 * app's history that is not network traffic; it shares the journal, runs, Clear and the archive.
 */
@Serializable
@SerialName("link")
data class LinkEntry(
    override val id: String,
    override val runId: String,
    override val timeMs: Long,
    val uri: String,
    val scheme: String? = null,
    val host: String? = null,
    val path: String? = null,
    val query: List<Header> = emptyList(),
    val action: String? = null,
    val categories: List<String> = emptyList(),
    /** Intent flags, hexadecimal. */
    val flags: String? = null,
    val extras: List<IntentExtra> = emptyList(),
    /** The receiving activity's class. */
    val activity: String,
    val delivery: LinkDelivery,
    /** Who sent it (Activity.getReferrer); null is unknown. */
    val referrer: String? = null,
) : NetworkEntry {
    override val library: String get() = "intent"
    override val initiator: Initiator? get() = null
    override val intervention: InterventionRecord? get() = null
    override fun summary() = this
}

/** What happened to a link the daemon opened (feature 012 US2). */
@Serializable
enum class LinkOpenOutcome {
    @SerialName("received") RECEIVED,
    @SerialName("other-app") OTHER_APP,
    @SerialName("chooser") CHOOSER,
    @SerialName("not-handled") NOT_HANDLED,
}

/** One host the app declares for App Links: the system's verification state and the user's choice. */
@Serializable
data class AppLinkHost(val host: String, val state: String, val reason: String? = null, val enabled: Boolean? = null)

/** `links.verification` (feature 012 US3); [reason] when the state is unavailable or there is none. */
@Serializable
data class AppLinksState(val available: Boolean, val hosts: List<AppLinkHost> = emptyList(), val linkHandlingAllowed: Boolean? = null, val reason: String? = null)

/** `links.open`: who received the link; [recordId] the link entry when the app recorded it. */
@Serializable
data class LinkOpenResult(
    val outcome: LinkOpenOutcome,
    val activity: String? = null,
    val packageName: String? = null,
    val recordId: String? = null,
    val reason: String? = null,
    /** For an http(s) link the app did not receive: its host's App Links state. */
    val appLinks: AppLinkHost? = null,
)

// ---- feature 005: intervention -------------------------------------------------------

@Serializable
enum class ScriptHandler {
    @SerialName("request") REQUEST,
    @SerialName("response") RESPONSE,
    @SerialName("ws-send") WS_SEND,
    @SerialName("ws-receive") WS_RECEIVE,
    /** A script timer (feature 020): log lines and pushes from `every`/`after`. */
    @SerialName("timer") TIMER,
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
    /** Feature 011: found in a field of the body as its protobuf schema decodes it; the page's `fields` name it. */
    @SerialName("request-body-decoded") REQUEST_BODY_DECODED,
    @SerialName("response-body-decoded") RESPONSE_BODY_DECODED,
    @SerialName("payload-decoded") PAYLOAD_DECODED,
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
    /** Journal records on the device the host has not indexed yet (feature 011). */
    val hostLagRecords: Long? = null,
    /** Finished bodies not yet copied to the host. */
    val bodiesPending: Int? = null,
    /** Bodies that will never be on the host (gone from the device before the copy). */
    val bodiesNotArchived: Int? = null,
    /** Closed epochs of this app outside the query's scope. */
    val archivedEpochs: Int? = null,
    /** Why archiving stopped (disk full, write failure); live history keeps working. */
    val archivingStopped: String? = null,
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
    /** Feature 011: found in a field of the body as its protobuf schema decodes it; the page's `fields` name it. */
    @SerialName("request-body-decoded") REQUEST_BODY_DECODED,
    @SerialName("response-body-decoded") RESPONSE_BODY_DECODED,
    @SerialName("payload-decoded") PAYLOAD_DECODED,
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
    /** Entries of the page that come from the archive, by id (feature 011). */
    val archived: Map<String, ArchivedMark>? = null,
    /** Decoded protobuf fields where a body search matched, by entry id, e.g. `items[0].name` (feature 011). */
    val fields: Map<String, List<String>>? = null,
    /** Feature 040: the page's rows that hold every search word of the query line, and where. */
    val hits: Map<String, List<SearchHit>>? = null,
    /** Feature 043: the saved filters behind this page and what they hid. */
    val saved: SavedFiltersApplied? = null,
)

/** One entry's search hits (feature 040). */
@Serializable
data class EntryHits(val id: String, val timeMs: Long, val hits: List<SearchHit>)

/** Result of `network_search` (feature 040): the entries with hits, in list order, paged. */
@Serializable
data class SearchPage(
    val entries: List<EntryHits>,
    /** Entries with hits in all (not only this page). */
    val total: Int,
    val next: String? = null,
    /** Entries the filters left to search. */
    val searched: Int,
    val notSearched: List<String>? = null,
    val degraded: List<DegradationMarker>? = null,
    /** Feature 043: the saved filters behind this page and what they hid. */
    val saved: SavedFiltersApplied? = null,
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
    /** Set when the entry comes from the archive (feature 011). */
    val archived: ArchivedMark? = null,
    /** Feature 041: ws-connection only — how the app's format rules for its URL read its messages (the daemon's). */
    val formatUse: List<FormatUse>? = null,
) {
    companion object {
        const val MAX_MESSAGES = 1000
    }
}

/** JSON part of `network_body`; the wire bytes follow as binary frames. */
@Serializable
data class NetworkBodyResult(val body: BodyInfo)

/** Result of `network.export` (feature 026): what the HAR file holds. */
@Serializable
data class NetworkExportResult(
    val file: String,
    /** HTTP exchanges and WebSocket connections written. */
    val entries: Int,
    val webSocketMessages: Int,
    /** Bodies and payloads written. */
    val bodies: Int,
    /** Bodies left out (not stored, withheld by redaction, unreadable); each says why in the file. */
    val bodiesOmitted: Int,
    /** Messages beyond what a connection's details carry, not in the file. */
    val messagesNotExported: Int = 0,
    /** Feature 043: the saved filters behind what was written. */
    val saved: SavedFiltersApplied? = null,
)

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

/**
 * Per-library capture status in the `network` capability. A library is captured through several
 * events (feature 019): [notCaptured] names those that are not (degraded or unavailable),
 * [captured] those that are when only some are (degraded). [reason] is plain text: what is not
 * captured, why, and what to do; [detail] keeps the raw causes (runtime codes) for diagnosis.
 */
@Serializable
data class LibraryStatus(
    val name: String,
    val status: CapabilityStatus.Status,
    val reason: String? = null,
    val notCaptured: List<String>? = null,
    val captured: List<String>? = null,
    val detail: String? = null,
)

/** Storage bounds stated by the `network` capability; null is no limit (the default). */
@Serializable
data class NetworkBounds(val historyBytes: Long? = null, val bodyBytes: Long? = null)

object NetworkLibraries {
    const val OKHTTP3 = "okhttp3"
    const val URLCONNECTION = "urlconnection"
    const val OKHTTP3_WS = "okhttp3-ws"
    const val NV_WEBSOCKET = "nv-websocket"
    const val JAVA_WEBSOCKET = "java-websocket"
    /** Detected only: its traffic bypasses the captured HTTP stack (feature 006). */
    const val GRPC = "grpc"
    /** Detected with its engines (feature 030): OkHttp and Android engines are captured through those stacks; CIO and Java are not. */
    const val KTOR = "ktor-client"

    /** Feature 060: read over DevTools by the daemon when the app's browser capture is on. */
    const val WEBVIEW = io.yamsergey.dta.protocol.web.Web.WEBVIEW
    const val CUSTOM_TAB = io.yamsergey.dta.protocol.web.Web.CUSTOM_TAB

    val ALL = listOf(OKHTTP3, URLCONNECTION, OKHTTP3_WS, NV_WEBSOCKET, JAVA_WEBSOCKET, GRPC, KTOR)
}
