package io.yamsergey.dta.daemon.web

import io.yamsergey.dta.protocol.web.WebPage
import io.yamsergey.dta.protocol.web.WebStep
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * Feature 060: one DevTools browser endpoint (a WebView process, or the browser). New pages are
 * held until their network is on, then resumed (≈60 ms, research E3/D5), so they are captured
 * from their first request; pages that existed before are followed and said to be partial.
 *
 * With [ours] (Custom Tabs) a page is followed only when it is the app's: an existing one by its
 * URL, a new one by its first document request. Until then what it does stays in memory; a page
 * that is not the app's is let go at once and nothing of it is kept or logged (FR-006).
 */
class BrowserSession(
    private val cdp: CdpChannel,
    private val source: String,
    private val sink: suspend (List<WebStep>) -> Unit,
    private val ours: (suspend (url: String) -> Boolean)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** [owner]: a frame or worker of that page (feature 061); its traffic is the page's, it is never listed. */
    private class Page(val session: String, val targetId: String, var url: String, var partial: Boolean, val held: Boolean, val attachedAt: Long, val translator: CdpTranslator, val owner: Page? = null) {
        var deciding = held
        val buffer = ArrayList<WebStep>()
        /** Requests missed before the last reload: a reloaded page is whole again. */
        var missedAtReload = 0
    }

    private val pages = ConcurrentHashMap<String, Page>()

    /** The pages followed (never one being decided or let go). */
    fun pages(): List<WebPage> = pages.values.filter { !it.deciding && it.owner == null }.map {
        WebPage(it.targetId, source, it.url, it.partial || it.translator.missed > it.missedAtReload, it.translator.started, it.attachedAt)
    }

    /** Reloads a followed page, so it is captured whole. */
    suspend fun reload(targetId: String): Boolean {
        val page = pages.values.firstOrNull { it.targetId == targetId && !it.deciding && it.owner == null } ?: return false
        cdp.send("Page.reload", buildJsonObject { put("ignoreCache", true) }, page.session)
        page.partial = false
        page.missedAtReload = page.translator.missed
        return true
    }

    override fun toString() = "BrowserSession($source, ${pages.size} pages)"

    suspend fun run() = coroutineScope {
        cdp.send("Target.setAutoAttach", buildJsonObject {
            put("autoAttach", true)
            put("waitForDebuggerOnStart", true)
            put("flatten", true)
        })
        for (e in cdp.events) {
            when (e.method) {
                "Target.attachedToTarget" -> launch { attached(e.params, e.sessionId) }
                "Target.detachedFromTarget" -> e.params.str("sessionId")?.let { gone -> pages.remove(gone)?.let { p -> dropChildren(p) } }
                "Target.targetInfoChanged" -> (e.params["targetInfo"] as? JsonObject)?.let { info ->
                    pages.values.firstOrNull { it.targetId == info.str("targetId") && !it.deciding }?.let { p -> info.str("url")?.let { p.url = it } }
                }
                else -> if (e.method.startsWith("Network.")) {
                    val page = e.sessionId?.let { pages[it] } ?: continue
                    network(page, e)
                }
            }
        }
    }

    private suspend fun attached(p: JsonObject, parent: String?) {
        val session = p.str("sessionId") ?: return
        val info = p["targetInfo"] as? JsonObject ?: return
        val waiting = (p["waitingForDebugger"] as? JsonPrimitive)?.booleanOrNull == true
        val type = info.str("type")
        val url = info.str("url").orEmpty()
        val ours = ours
        // Feature 061: a frame or worker of a followed page is followed as part of it.
        if (parent != null) {
            val owner = pages[parent]?.let { it.owner ?: it }
            if (owner == null || type !in CHILD_TYPES) return away(session, waiting)
            return follow(Page(session, info.str("targetId") ?: session, url, partial = !waiting, held = false, attachedAt = clock(), CdpTranslator(session.take(8), source), owner), waiting)
        }
        if (type in WORKER_TYPES) {
            // A service worker belongs to a site, not a tab: in a browser, only the app's tab's site.
            if (ours != null && pages.values.none { it.owner == null && !it.deciding && sameOrigin(it.url, url) }) return away(session, waiting)
            return follow(Page(session, info.str("targetId") ?: session, url, partial = !waiting, held = false, attachedAt = clock(), CdpTranslator(session.take(8), source), WORKERS), waiting)
        }
        if (type != "page" && type != "iframe") return away(session, waiting)
        if (ours != null && !waiting && !runCatching { ours(url) }.getOrDefault(false)) {
            letGo(session)
            return
        }
        follow(Page(session, info.str("targetId") ?: session, url, partial = !waiting, held = ours != null && waiting, attachedAt = clock(), CdpTranslator(session.take(8), source)), waiting)
    }

    /** Network on, its own frames and workers attached (held until resumed), then resumed. */
    private suspend fun follow(page: Page, waiting: Boolean) {
        val session = page.session
        pages[session] = page
        runCatching {
            cdp.send("Network.enable", buildJsonObject {
                put("maxTotalBufferSize", 100_000_000)
                put("maxResourceBufferSize", 20_000_000)
            }, session)
        }
        runCatching {
            cdp.send("Target.setAutoAttach", buildJsonObject {
                put("autoAttach", true)
                put("waitForDebuggerOnStart", true)
                put("flatten", true)
            }, session)
        }
        if (waiting) runCatching { resume(session) }
    }

    private suspend fun away(session: String, waiting: Boolean) {
        if (waiting) runCatching { resume(session) }
        letGo(session)
    }

    private suspend fun resume(session: String) = cdp.send("Runtime.runIfWaitingForDebugger", sessionId = session)

    private suspend fun letGo(session: String) {
        pages.remove(session)?.let { dropChildren(it) }
        runCatching { cdp.send("Target.detachFromTarget", buildJsonObject { put("sessionId", session) }) }
    }

    /** A page's frames and workers go with it (their sessions close with the page's). */
    private fun dropChildren(page: Page) {
        pages.entries.removeIf { it.value.owner === page }
    }

    private suspend fun network(page: Page, e: CdpEvent) {
        if (page.owner != null) {
            deliver(page, page.translator.event(e.method, e.params))
            return
        }
        if (page.deciding && e.method == "Network.requestWillBeSent" && e.params.str("type") == "Document") {
            val url = (e.params["request"] as? JsonObject)?.str("url").orEmpty()
            if (!runCatching { ours!!.invoke(url) }.getOrDefault(false)) {
                page.buffer.clear()
                letGo(page.session)
                return
            }
            page.deciding = false
            page.url = url
            deliver(page, page.translator.event(e.method, e.params))
            flush(page)
            return
        }
        // The page's own document (not an iframe's) names the page.
        if (e.method == "Network.requestWillBeSent" && e.params.str("type") == "Document") {
            val frame = e.params.str("frameId")
            if (frame == null || frame == page.targetId) (e.params["request"] as? JsonObject)?.str("url")?.let { page.url = it }
        }
        deliver(page, page.translator.event(e.method, e.params))
    }

    private suspend fun deliver(page: Page, out: CdpTranslator.Out) {
        emit(page, out.steps)
        out.fetchBody?.let { id ->
            val answer = runCatching { cdp.send("Network.getResponseBody", buildJsonObject { put("requestId", id) }, page.session) }.getOrNull()
            val body = answer?.str("body")
            val base64 = (answer?.get("base64Encoded") as? JsonPrimitive)?.booleanOrNull == true
            emit(page, page.translator.body(id, body, base64))
        }
        out.fetchPostData?.let { id ->
            val answer = runCatching { cdp.send("Network.getRequestPostData", buildJsonObject { put("requestId", id) }, page.session) }.getOrNull()
            emit(page, page.translator.postData(id, answer?.str("postData")))
        }
    }

    private suspend fun emit(page: Page, steps: List<WebStep>) {
        if (steps.isEmpty() || pages[page.session] !== page) return
        // A frame's or worker's traffic waits with its page while the page is being decided.
        val holder = page.owner?.takeIf { it !== WORKERS } ?: page
        if (holder.deciding) holder.buffer += steps else sink(steps)
    }

    private suspend fun flush(page: Page) {
        val held = page.buffer.toList()
        page.buffer.clear()
        if (held.isNotEmpty()) sink(held)
    }

    private companion object {
        fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

        /** Targets that are part of a page (feature 061). */
        val CHILD_TYPES = setOf("iframe", "worker", "service_worker", "shared_worker")
        /** Targets attached at the browser level that belong to a site. */
        val WORKER_TYPES = setOf("service_worker", "shared_worker")

        /** The owner of top-level workers: never listed, never decided. */
        val WORKERS = Page("", "", "", partial = false, held = false, attachedAt = 0, translator = CdpTranslator("workers", ""))

        fun sameOrigin(a: String, b: String): Boolean {
            fun origin(u: String) = runCatching { java.net.URI(u).let { "${it.scheme?.lowercase()}://${it.host?.lowercase()}:${it.port}" } }.getOrNull()
            return origin(a) != null && origin(a) == origin(b)
        }
    }
}
