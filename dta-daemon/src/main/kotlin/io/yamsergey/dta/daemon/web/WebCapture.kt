package io.yamsergey.dta.daemon.web

import io.yamsergey.dta.daemon.adb.AdbExec
import io.yamsergey.dta.daemon.connection.AppConnection
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.ErrorCodes
import io.yamsergey.dta.protocol.web.DevtoolsEndpoint
import io.yamsergey.dta.protocol.web.Web
import io.yamsergey.dta.protocol.web.WebDevtools
import io.yamsergey.dta.protocol.web.WebIngest
import io.yamsergey.dta.protocol.web.WebLaunches
import io.yamsergey.dta.protocol.web.WebSourceStatus
import io.yamsergey.dta.protocol.web.WebStatus
import io.yamsergey.dta.protocol.web.WebStep
import io.yamsergey.dta.protocol.web.WebSwitch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * Feature 060: browser capture for one app on one device, owned by the daemon (FR-003). Runs only
 * while one of the app's switches is on and the app is connected; reads the WebView process's
 * DevTools socket and, for Custom Tabs, the browser's; records through the app (`web_ingest`).
 * Started when the app connects and when a switch changes; ends when both are off.
 */
class WebCapture(
    private val serial: String,
    private val pkg: String,
    private val runAdb: (List<String>) -> AdbExec.Result,
    private val connect: suspend () -> AppConnection,
    private val scope: CoroutineScope,
    /** Feature 061: where this daemon's forwards are written down, so a crash leaves none behind. */
    private val record: ForwardRecord? = null,
) {
    @Volatile private var webview = WebSourceStatus(WebSourceStatus.OFF, Web.OFF)
    @Volatile private var tabs = WebSourceStatus(WebSourceStatus.OFF, Web.OFF)
    @Volatile private var lastSwitch: WebSwitch? = null
    private val sessions = ConcurrentHashMap<String, BrowserSession>()
    private val endpoints = ConcurrentHashMap<String, DevtoolsEndpoint>()
    /** Forwards made for `web.devtools` alone, kept until the daemon stops. */
    private val ownForwards = ConcurrentHashMap<String, Int>()
    private val lock = Mutex()
    private var job: Job? = null

    /** (Re)starts following the app's switches. */
    suspend fun start() = lock.withLock {
        job?.takeIf { it.isActive }?.cancelAndJoin()
        job = scope.launch { loop() }
    }

    suspend fun stop() = lock.withLock {
        job?.cancelAndJoin()
        job = null
        ownForwards.values.forEach { removeForward(it) }
        ownForwards.clear()
    }

    /** Reads or sets the app's switches ([webview], [customTabs] null: unchanged), then follows them. */
    suspend fun switch(webview: Boolean?, customTabs: Boolean?): WebSwitch {
        val params = buildJsonObject {
            webview?.let { put("webview", it) }
            customTabs?.let { put("customTabs", it) }
        }
        val answer = DtaJson.decodeFromJsonElement(WebSwitch.serializer(), request(connect(), Web.CAPTURE, params))
        lastSwitch = answer
        if (webview != null || customTabs != null) start()
        return answer
    }

    suspend fun status(): WebStatus {
        val sw = runCatching { switch(null, null) }.getOrNull()
        if (sw != null && (sw.webview || sw.customTabs) && job?.isActive != true) start()
        // A switch just turned on whose loop has not reported yet reads as starting, not off.
        fun shown(on: Boolean?, s: WebSourceStatus) = if (on == true && s.state == WebSourceStatus.OFF) WebSourceStatus(WebSourceStatus.WAITING, "starting") else s
        return WebStatus(shown(sw?.webview, webview), shown(sw?.customTabs, tabs), sessions.values.flatMap { it.pages() }, endpoints.values.toList(), sw)
    }

    suspend fun reload(page: String): Boolean = sessions.values.any { runCatching { it.reload(page) }.getOrDefault(false) }

    /** The forwarded endpoints; the WebView's is forwarded on demand when capture is off. */
    suspend fun devtools(): WebDevtools {
        if (endpoints.isEmpty()) {
            val socket = webviewSocket() ?: return WebDevtools(emptyList(), "no WebView DevTools socket: open a WebView in the app (a debuggable app's WebViews are debuggable)")
            val port = ownForwards[socket] ?: forward(socket).also { ownForwards[socket] = it }
            return WebDevtools(listOf(endpoint(Web.WEBVIEW, socket, port)))
        }
        return WebDevtools(endpoints.values.toList())
    }

    private suspend fun loop() {
        val sw = try {
            switch(null, null)
        } catch (e: Exception) {
            val why = if (e.isMethodNotFound()) Web.OLDER_SIDEKICK else "the app does not answer: ${e.message}"
            webview = WebSourceStatus(WebSourceStatus.UNAVAILABLE, why)
            tabs = WebSourceStatus(WebSourceStatus.UNAVAILABLE, why)
            return
        }
        webview = if (sw.webview) WebSourceStatus(WebSourceStatus.WAITING, "looking for the app's WebView") else WebSourceStatus(WebSourceStatus.OFF, Web.OFF)
        tabs = if (sw.customTabs) WebSourceStatus(WebSourceStatus.WAITING, "looking for the browser") else WebSourceStatus(WebSourceStatus.OFF, Web.OFF)
        if (!sw.webview && !sw.customTabs) return
        val batcher = Batcher()
        // Runs until a switch changes or the daemon stops: the sockets are found over adb, so an app
        // restart is followed without a client reconnecting (steps wait for the app in the batcher).
        coroutineScope {
            launch { batcher.run() }
            if (sw.webview) launch { follow(Web.WEBVIEW, batcher) }
            if (sw.customTabs) launch { follow(Web.CUSTOM_TAB, batcher) }
        }
    }

    /** One source: find its socket, attach, follow until it goes, again. */
    private suspend fun follow(source: String, batcher: Batcher) {
        fun set(s: WebSourceStatus) { if (source == Web.WEBVIEW) webview = s else tabs = s }
        while (currentCoroutineContextActive()) {
            val socket = if (source == Web.WEBVIEW) webviewSocket() else browserSocket()
            if (socket == null) {
                set(WebSourceStatus(WebSourceStatus.WAITING, when {
                    source != Web.WEBVIEW -> "the browser is not running (Chrome-based browsers only)"
                    notRunning -> "the app is not running"
                    else -> "no WebView in the app yet"
                }))
                delay(if (source == Web.WEBVIEW) pollMs() else POLL_MS)
                continue
            }
            var port: Int? = null
            var cdp: CdpSocket? = null
            try {
                port = forward(socket)
                val version = CdpSocket.version(port)
                val owner = (version["Android-Package"] as? JsonPrimitive)?.contentOrNull
                if (source == Web.WEBVIEW && owner != null && owner != pkg) error("the WebView socket belongs to $owner")
                val url = (version["webSocketDebuggerUrl"] as? JsonPrimitive)?.contentOrNull ?: error("no browser endpoint in /json/version")
                endpoints[source] = endpoint(source, socket, port, url)
                cdp = CdpSocket.connect(url)
                val since = System.currentTimeMillis()
                val ours: (suspend (String) -> Boolean)? = if (source == Web.CUSTOM_TAB) { u -> launchedByApp(u) } else null
                val session = BrowserSession(cdp, source, { batcher.add(it) }, ours)
                sessions[source] = session
                set(WebSourceStatus(WebSourceStatus.CAPTURING, null))
                session.run()
                set(WebSourceStatus(WebSourceStatus.WAITING, "the DevTools connection closed after ${(System.currentTimeMillis() - since) / 1000} s; looking again"))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                set(WebSourceStatus(WebSourceStatus.WAITING, "cannot attach yet: ${e.message}"))
            } finally {
                sessions.remove(source)
                endpoints.remove(source)
                withContext(NonCancellable) {
                    cdp?.close()
                    port?.let { removeForward(it) }
                }
            }
            delay(if (source == Web.WEBVIEW) pollMs() else POLL_MS)
        }
    }

    private suspend fun currentCoroutineContextActive() = kotlin.coroutines.coroutineContext.isActive

    /** A page is the app's Custom Tab when the app opened that URL in the last 30 s (research R3). */
    private suspend fun launchedByApp(url: String): Boolean {
        val launches = runCatching {
            DtaJson.decodeFromJsonElement(WebLaunches.serializer(), request(connect(), Web.LAUNCHES, JsonObject(emptyMap()))).launches
        }.getOrDefault(emptyList())
        if (launches.none { TabMatch.same(it.url, url) }) return false
        val now = deviceNow()
        return launches.any { TabMatch.same(it.url, url) && TabMatch.recent(it, now) }
    }

    private fun deviceNow(): Long =
        runAdb(listOf("-s", serial, "shell", "date", "+%s%3N")).stdout.trim().toLongOrNull() ?: System.currentTimeMillis()

    /** Sends steps to the app in batches (≤ 100 ms apart), in order. */
    private inner class Batcher {
        private val pending = ArrayList<WebStep>()
        private val lock = Mutex()

        suspend fun add(steps: List<WebStep>) = lock.withLock { pending += steps }

        suspend fun run() {
            while (true) {
                delay(100)
                val batch = lock.withLock { pending.toList().also { pending.clear() } }
                if (batch.isEmpty()) continue
                batch.chunked(200).forEach { part ->
                    runCatching { request(connect(), Web.INGEST, DtaJson.encodeToJsonElement(WebIngest.serializer(), WebIngest(part)) as JsonObject) }
                }
            }
        }
    }

    // ---- adb -----------------------------------------------------------------------------

    /**
     * The app's processes (`pkg`, `pkg:*`) and the abstract sockets on the device, in one adb
     * shell call (the loop polls while waiting; one call keeps adb's load low).
     */
    private fun scan(): Pair<Set<String>, List<String>> {
        val out = runAdb(listOf("-s", serial, "shell", "ps -A -o PID,NAME; echo $SPLIT; cat /proc/net/unix")).stdout
        val (ps, unix) = out.split(SPLIT, limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val pids = ps.lineSequence().map { it.trim().split(Regex("\\s+")) }
            .filter { it.size >= 2 && (it[1] == pkg || it[1].startsWith("$pkg:")) }
            .map { it[0] }.toSet()
        val sockets = unix.lineSequence()
            .mapNotNull { line -> line.trim().split(Regex("\\s+")).lastOrNull()?.takeIf { it.startsWith("@") }?.removePrefix("@") }
            .toList()
        return pids to sockets
    }

    /** A new process of the app: its first WebView page may open within a second, so look often for a while. */
    @Volatile private var knownPids: Set<String> = emptySet()
    @Volatile private var fastUntil = 0L
    @Volatile private var notRunning = false

    private fun pollMs(): Long = when {
        System.currentTimeMillis() < fastUntil -> FAST_POLL_MS
        notRunning -> START_POLL_MS
        else -> POLL_MS
    }

    private fun webviewSocket(): String? {
        val (pids, sockets) = scan()
        if (pids.isNotEmpty() && pids != knownPids) fastUntil = System.currentTimeMillis() + FAST_WINDOW_MS
        knownPids = pids
        if (pids.isEmpty()) {
            notRunning = true
            return null
        }
        notRunning = false
        return sockets.firstOrNull { s -> s.startsWith(WEBVIEW_SOCKET) && s.removePrefix(WEBVIEW_SOCKET) in pids }
    }

    private fun browserSocket(): String? = scan().second.firstOrNull { it == CHROME_SOCKET }

    private fun forward(socket: String): Int {
        val r = runAdb(listOf("-s", serial, "forward", "tcp:0", "localabstract:$socket"))
        val port = r.stdout.trim().toIntOrNull() ?: error("adb forward gave no port for $socket: ${(r.stdout + r.stderr).trim()}")
        record?.add(serial, port, socket)
        return port
    }

    private fun removeForward(port: Int) {
        runCatching { runAdb(listOf("-s", serial, "forward", "--remove", "tcp:$port")) }
        record?.remove(serial, port)
    }

    private fun endpoint(source: String, socket: String, port: Int, ws: String? = null) =
        DevtoolsEndpoint(source, "http://127.0.0.1:$port", ws, socket)

    private suspend fun request(app: AppConnection, method: String, params: JsonObject) = app.request(method, params)

    private fun Exception.isMethodNotFound() =
        this is io.yamsergey.dta.daemon.connection.SidekickRpcException && code == ErrorCodes.METHOD_NOT_FOUND

    companion object {
        const val WEBVIEW_SOCKET = "webview_devtools_remote_"
        const val CHROME_SOCKET = "chrome_devtools_remote"
        private const val SPLIT = "--dta-unix--"
        /** How often a missing socket is looked for again. */
        private const val POLL_MS = 2_000L
        /** Right after the app's process starts (or while it is not running), for [FAST_WINDOW_MS]. */
        private const val FAST_POLL_MS = 300L
        private const val FAST_WINDOW_MS = 15_000L
        /** While the app is not running: its start is caught within half a second. */
        private const val START_POLL_MS = 500L
    }
}

/** Feature 060: is a page the Custom Tab the app opened? */
object TabMatch {
    /** The same page: scheme and host case ignored, a trailing slash and the fragment ignored. */
    fun same(a: String, b: String): Boolean = norm(a) == norm(b)

    /** The launch is recent enough: the tab opens within seconds of it (a minute allowed for a cold browser). */
    fun recent(launch: io.yamsergey.dta.protocol.web.WebLaunch, nowMs: Long): Boolean = nowMs - launch.timeMs in -5_000..60_000

    private fun norm(u: String): String {
        val noFragment = u.substringBefore('#')
        val uri = runCatching { java.net.URI(noFragment) }.getOrNull() ?: return noFragment.trimEnd('/')
        val path = (uri.rawPath ?: "").ifEmpty { "/" }.trimEnd('/').ifEmpty { "/" }
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "${uri.scheme?.lowercase()}://${uri.host?.lowercase()}$port$path${uri.rawQuery?.let { "?$it" } ?: ""}"
    }
}
