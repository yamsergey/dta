package io.yamsergey.dta.present.web

import io.yamsergey.dta.protocol.web.WebPage
import io.yamsergey.dta.protocol.web.WebSourceStatus
import io.yamsergey.dta.protocol.web.WebStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/** Feature 061: what the Network tab says about browser capture. */
class WebStatusLineTest {
    private val off = WebSourceStatus(WebSourceStatus.OFF, "off")
    private fun page(id: String, source: String, url: String, partial: Boolean = false) = WebPage(id, source, url, partial, 3, 0)

    @Test
    fun `both off says nothing`() = assertEquals(emptyList(), WebStatusLine.of(WebStatus(off, off)))

    @Test
    fun `waiting says what for, in the daemon's words`() {
        val line = WebStatusLine.of(WebStatus(WebSourceStatus(WebSourceStatus.WAITING, "no WebView in the app yet"), off)).single()
        assertEquals("◌" to "WebView waiting — no WebView in the app yet", line.glyph to line.text)
        assertEquals(WebStatusLine.Tone.WAITING, line.tone)
    }

    @Test
    fun `capturing counts each source's pages, partial pages offer a reload`() {
        val s = WebStatus(
            WebSourceStatus(WebSourceStatus.CAPTURING), WebSourceStatus(WebSourceStatus.CAPTURING),
            listOf(page("A1", "webview", "http://127.0.0.1:8090/broker/news/1", partial = true), page("A2", "webview", "http://127.0.0.1:8090/broker/frame"), page("T1", "custom-tab", "https://help.example.com/faq")),
        )
        val parts = WebStatusLine.of(s)
        assertEquals(listOf("WebView capturing 2 pages", "Custom Tab capturing 1 page", "news/1 partial: loaded before DTA attached"), parts.map { it.text })
        assertEquals(listOf(null, null, "A1"), parts.map { it.reload })
        assertEquals(WebStatusLine.Tone.LIVE, parts[0].tone)
        assertEquals("◐", parts[2].glyph)
        assertEquals("http://127.0.0.1:8090/broker/news/1", parts[2].tooltip)
    }

    @Test
    fun `capturing with no page yet still says it is on`() {
        val parts = WebStatusLine.of(WebStatus(WebSourceStatus(WebSourceStatus.CAPTURING), off))
        assertEquals(listOf("WebView capturing, no page open"), parts.map { it.text })
    }

    @Test
    fun `cannot says why`() {
        val why = "the app's DTA is older: it has no browser capture (rebuild the app with this DTA)"
        val parts = WebStatusLine.of(WebStatus(WebSourceStatus(WebSourceStatus.UNAVAILABLE, why), WebSourceStatus(WebSourceStatus.UNAVAILABLE, why)))
        assertEquals(listOf("WebView cannot capture — $why", "Custom Tab cannot capture — $why"), parts.map { it.text })
        assertEquals(WebStatusLine.Tone.PROBLEM, parts[0].tone)
        assertEquals("▲", parts[0].glyph)
    }

    @Test
    fun `a page's short name is its last path segments, or its host`() {
        assertEquals("news/1", WebStatusLine.short("http://127.0.0.1:8090/broker/news/1"))
        assertEquals("help.example.com", WebStatusLine.short("https://help.example.com/"))
        assertEquals("faq", WebStatusLine.short("https://help.example.com/faq?x=1#top"))
    }
}
