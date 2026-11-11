package io.yamsergey.dta.cli.format

import io.yamsergey.dta.protocol.web.DevtoolsEndpoint
import io.yamsergey.dta.protocol.web.Web
import io.yamsergey.dta.protocol.web.WebPage
import io.yamsergey.dta.protocol.web.WebSourceStatus
import io.yamsergey.dta.protocol.web.WebStatus
import io.yamsergey.dta.protocol.web.WebSwitch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebTextTest {
    @Test
    fun `the switches say what is on and what the app does`() {
        assertEquals("WebView pages: on (the app's WebViews are debuggable) · Custom Tabs: off", WebText.switch(WebSwitch(webview = true, customTabs = false, debuggingEnabled = true)))
        assertTrue(WebText.switch(WebSwitch(false, true, launchHook = "the app has no androidx.browser Custom Tabs")).endsWith("(the app has no androidx.browser Custom Tabs)"))
    }

    @Test
    fun `status lists sources, partial pages with how to reload and the endpoints`() {
        val text = WebText.status(WebStatus(
            WebSourceStatus(WebSourceStatus.CAPTURING), WebSourceStatus(WebSourceStatus.OFF, Web.OFF),
            listOf(WebPage("T1", Web.WEBVIEW, "http://h/news", partial = true, requests = 4, attachedAtMs = 0)),
            listOf(DevtoolsEndpoint(Web.WEBVIEW, "http://127.0.0.1:5000", null, "webview_devtools_remote_42")),
        ))
        assertTrue("WebView pages  capturing" in text, text)
        assertTrue("T1  webview  http://h/news  4 requests  partial: loaded before DTA attached (dta web reload T1)" in text, text)
        assertTrue("chrome-devtools-mcp --browserUrl http://127.0.0.1:5000" in text, text)
    }
}
