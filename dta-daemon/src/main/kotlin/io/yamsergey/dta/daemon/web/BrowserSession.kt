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
    private class Page(val session: String, val targetId: String, var url: String, var partial: Boolean, val held: Boolean, val attachedAt: Long, val translator: CdpTranslator) {
        var deciding = held
        val buffer = ArrayList<WebStep>()
        /** Requests missed before the last reload: a reloaded page is whole again. */
        var missedAtReload = 0
    }

    private val pages = ConcurrentHashMap<String, Page>()

    /** The pages followed (never one being decided or let go). */
    fun pages(): List<WebPage> = pages.values.filter { !it.deciding }.map {
        WebPage(it.targetId, source, it.url, it.partial || it.translator.missed > it.missedAtReload, it.translator.started, it.attachedAt)
    }

    /** Reloads a followed page, so it is captured whole. */
    suspend fun reload(targetId: String): Boolean {
        val page = pages.values.firstOrNull { it.targetId == targetId && !it.deciding } ?: return false
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
                "Target.attachedToTarget" -> launch { attached(e.params) }
                "Target.detachedFromTarget" -> e.params.str("sessionId")?.let { pages.remove(it) }
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

    private suspend fun attached(p: JsonObject) {
        val session = p.str("sessionId") ?: return
        val info = p["targetInfo"] as? JsonObject ?: return
        val waiting = (p["waitingForDebugger"] as? JsonPrimitive)?.booleanOrNull == true
        val type = info.str("type")
        val url = info.str("url").orEmpty()
        if (type != "page" && type != "iframe") {
            if (waiting) runCatching { resume(session) }
            letGo(session)
            return
        }
        val ours = ours
        if (ours != null && !waiting && !runCatching { ours(url) }.getOrDefault(false)) {
            letGo(session)
            return
        }
        val page = Page(session, info.str("targetId") ?: session, url, partial = !waiting, held = ours != null && waiting, attachedAt = clock(), CdpTranslator(session.take(8), source))
        pages[session] = page
        runCatching {
            cdp.send("Network.enable", buildJsonObject {
                put("maxTotalBufferSize", 100_000_000)
                put("maxResourceBufferSize", 20_000_000)
            }, session)
        }
        if (waiting) runCatching { resume(session) }
    }

    private suspend fun resume(session: String) = cdp.send("Runtime.runIfWaitingForDebugger", sessionId = session)

    private suspend fun letGo(session: String) {
        pages.remove(session)
        runCatching { cdp.send("Target.detachFromTarget", buildJsonObject { put("sessionId", session) }) }
    }

    private suspend fun network(page: Page, e: CdpEvent) {
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
        if (page.deciding) page.buffer += steps else sink(steps)
    }

    private suspend fun flush(page: Page) {
        val held = page.buffer.toList()
        page.buffer.clear()
        if (held.isNotEmpty()) sink(held)
    }

    private companion object {
        fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    }
}
