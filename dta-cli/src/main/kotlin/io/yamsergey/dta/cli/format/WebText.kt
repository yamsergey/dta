package io.yamsergey.dta.cli.format

import io.yamsergey.dta.protocol.web.WebDevtools
import io.yamsergey.dta.protocol.web.WebSourceStatus
import io.yamsergey.dta.protocol.web.WebStatus
import io.yamsergey.dta.protocol.web.WebSwitch

/** Feature 060: browser capture as text. */
object WebText {
    fun switch(s: WebSwitch): String = buildString {
        append("WebView pages: ${onOff(s.webview)}")
        if (s.webview) append(if (s.debuggingEnabled) " (the app's WebViews are debuggable)" else " (debugging is being enabled in the app)")
        append(" · Custom Tabs: ${onOff(s.customTabs)}")
        if (s.customTabs) s.launchHook?.let { append(if (it == "installed") " (the app's launches are seen)" else " ($it)") }
        s.note?.let { append("\n$it") }
    }

    fun status(s: WebStatus): String = buildString {
        appendLine("WebView pages  ${source(s.webview)}")
        appendLine("Custom Tabs    ${source(s.customTabs)}")
        if (s.pages.isNotEmpty()) {
            appendLine()
            appendLine("Pages followed:")
            s.pages.forEach { p ->
                append("  ${p.id}  ${p.source}  ${p.url}  ${p.requests} requests")
                if (p.partial) append("  partial: loaded before DTA attached (dta web reload ${p.id})")
                appendLine()
            }
        }
        if (s.devtools.isNotEmpty()) {
            appendLine()
            append(devtools(WebDevtools(s.devtools)))
        }
    }.trimEnd()

    fun devtools(d: WebDevtools): String = if (d.devtools.isEmpty()) d.note ?: "no DevTools endpoint" else d.devtools.joinToString("\n") {
        "${it.source}  ${it.browserUrl}  (chrome-devtools-mcp --browserUrl ${it.browserUrl}; chrome://inspect › Configure › ${it.browserUrl.removePrefix("http://")})"
    }

    private fun onOff(on: Boolean) = if (on) "on" else "off"

    private fun source(s: WebSourceStatus) = s.state + (s.reason?.let { " — $it" } ?: "")
}
