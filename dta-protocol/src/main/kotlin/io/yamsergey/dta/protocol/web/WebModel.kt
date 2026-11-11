package io.yamsergey.dta.protocol.web

import io.yamsergey.dta.protocol.network.Direction
import io.yamsergey.dta.protocol.network.Header
import io.yamsergey.dta.protocol.network.MessageOpcode
import kotlinx.serialization.Serializable

/**
 * Feature 060: browser capture. The app keeps two switches (off by default); the daemon reads
 * the app's WebView pages and the Custom Tabs it opens over the DevTools protocol and records
 * what it sees through the app's own network recorder ([WebStep]s, app method `web_ingest`).
 */
object Web {
    /** Network library names of captured web traffic. */
    const val WEBVIEW = "webview"
    const val CUSTOM_TAB = "custom-tab"

    // App methods.
    const val CAPTURE = "web_capture"
    const val INGEST = "web_ingest"
    const val LAUNCHES = "web_launches"

    const val OLDER_SIDEKICK = "this app's DTA predates browser capture (feature 060): rebuild it with this DTA"
    const val OFF = "off: turn it on in Settings › Tools › DTA › Browsers, or with dta web capture"
}

/**
 * The app's switches. [debuggingEnabled]: the app made its WebViews debuggable early (WebView on);
 * [launchHook]: whether Custom Tab launches are seen (`installed`, or why not); [note]: what a
 * change means for the running process.
 */
@Serializable
data class WebSwitch(
    val webview: Boolean,
    val customTabs: Boolean,
    val debuggingEnabled: Boolean = false,
    val launchHook: String? = null,
    val note: String? = null,
)

/** A Custom Tab the app opened (`CustomTabsIntent#launchUrl`), seen by the app. */
@Serializable
data class WebLaunch(val url: String, val timeMs: Long)

@Serializable
data class WebLaunches(val launches: List<WebLaunch>)

@Serializable
data class WebIngestResult(val applied: Int, val unknown: Int)

@Serializable
data class WebIngest(val steps: List<WebStep>)

/**
 * One lifecycle step of a web request or socket, as the daemon read it from DevTools. [key] is
 * the daemon's (`<session>:<requestId>`); the app maps it to its own entry id. A body comes as
 * [text] or [base64].
 */
@Serializable
data class WebStep(
    val op: String,
    val key: String,
    val library: String? = null,
    val method: String? = null,
    val url: String? = null,
    val headers: List<Header>? = null,
    val timeMs: Long? = null,
    val endMs: Long? = null,
    val status: Int? = null,
    val protocol: String? = null,
    val contentType: String? = null,
    val text: String? = null,
    val base64: String? = null,
    val error: String? = null,
    val direction: Direction? = null,
    val opcode: MessageOpcode? = null,
    val code: Int? = null,
    val reason: String? = null,
) {
    val bytes: ByteArray? get() = base64?.let { java.util.Base64.getDecoder().decode(it) } ?: text?.toByteArray(Charsets.UTF_8)

    companion object {
        const val HTTP_START = "http.start"
        const val HTTP_REQUEST_BODY = "http.request-body"
        const val HTTP_RESPONSE = "http.response"
        const val HTTP_RESPONSE_BODY = "http.response-body"
        const val HTTP_COMPLETE = "http.complete"
        const val HTTP_FAILED = "http.failed"
        const val WS_START = "ws.start"
        const val WS_OPEN = "ws.open"
        const val WS_MESSAGE = "ws.message"
        const val WS_CLOSED = "ws.closed"
        const val WS_FAILED = "ws.failed"
    }
}

/** What one source (WebView pages or Custom Tabs) is doing: off, waiting, capturing, unavailable. */
@Serializable
data class WebSourceStatus(val state: String, val reason: String? = null) {
    companion object {
        const val OFF = "off"
        const val WAITING = "waiting"
        const val CAPTURING = "capturing"
        const val UNAVAILABLE = "unavailable"
    }
}

/**
 * A page DTA follows. [partial]: it was loading before DTA attached, so its earlier requests are
 * missing ([reload] it to capture it whole).
 */
@Serializable
data class WebPage(
    val id: String,
    val source: String,
    val url: String,
    val partial: Boolean = false,
    val requests: Int = 0,
    val attachedAtMs: Long,
)

/** A DevTools endpoint forwarded to this machine, for chrome://inspect or chrome-devtools-mcp --browserUrl. */
@Serializable
data class DevtoolsEndpoint(val source: String, val browserUrl: String, val webSocketUrl: String? = null, val socket: String)

@Serializable
data class WebStatus(
    val webview: WebSourceStatus,
    val customTabs: WebSourceStatus,
    val pages: List<WebPage> = emptyList(),
    val devtools: List<DevtoolsEndpoint> = emptyList(),
    val switch: WebSwitch? = null,
)

/** `web.devtools`: the forwarded endpoints; [note] says why there is none. */
@Serializable
data class WebDevtools(val devtools: List<DevtoolsEndpoint>, val note: String? = null)
