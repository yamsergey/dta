package io.yamsergey.dta.present.web

import io.yamsergey.dta.protocol.web.Web
import io.yamsergey.dta.protocol.web.WebSourceStatus
import io.yamsergey.dta.protocol.web.WebStatus

/**
 * Feature 061: browser capture as the Network tab says it — one part per source that is on
 * (capturing N pages / waiting — why / cannot — why), then one per partial page, with the page
 * to reload. Both off: nothing.
 */
object WebStatusLine {
    enum class Tone { LIVE, WAITING, PROBLEM, MUTED }

    data class Part(val glyph: String, val text: String, val tone: Tone, val reload: String? = null, val tooltip: String? = null)

    fun of(s: WebStatus): List<Part> = buildList {
        source("WebView", Web.WEBVIEW, s.webview, s)?.let { add(it) }
        source("Custom Tab", Web.CUSTOM_TAB, s.customTabs, s)?.let { add(it) }
        s.pages.filter { it.partial }.forEach {
            add(Part("◐", "${short(it.url)} partial: loaded before DTA attached", Tone.MUTED, reload = it.id, tooltip = it.url))
        }
    }

    private fun source(name: String, source: String, st: WebSourceStatus, s: WebStatus): Part? = when (st.state) {
        WebSourceStatus.OFF -> null
        WebSourceStatus.CAPTURING -> {
            val n = s.pages.count { it.source == source }
            Part("●", if (n == 0) "$name capturing, no page open" else "$name capturing $n page${if (n == 1) "" else "s"}", Tone.LIVE)
        }
        WebSourceStatus.WAITING -> Part("◌", "$name waiting" + (st.reason?.let { " — $it" } ?: ""), Tone.WAITING)
        else -> Part("▲", "$name cannot capture" + (st.reason?.let { " — $it" } ?: ""), Tone.PROBLEM)
    }

    /** "news/1" for …/broker/news/1: the last two path segments, else the host. */
    fun short(url: String): String {
        val noQuery = url.substringBefore('#').substringBefore('?')
        val afterScheme = noQuery.substringAfter("://")
        val host = afterScheme.substringBefore('/')
        val segments = afterScheme.removePrefix(host).split('/').filter { it.isNotEmpty() }
        return if (segments.isEmpty()) host else segments.takeLast(2).let { if (it.size == 2 && segments.size > 2) it.joinToString("/") else it.last() }
    }
}
