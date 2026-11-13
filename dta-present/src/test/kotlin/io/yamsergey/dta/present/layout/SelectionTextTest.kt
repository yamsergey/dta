package io.yamsergey.dta.present.layout

import io.yamsergey.dta.protocol.layout.LayoutSelection
import io.yamsergey.dta.protocol.layout.SelectedElement
import io.yamsergey.dta.protocol.layout.Selections
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/** Feature 055: the shared selection in words, for the plugin's line and the CLI. */
class SelectionTextTest {
    private val utc = ZoneOffset.UTC
    private fun e(json: String, now: String? = null, path: List<String> = listOf("DecorView", "Scaffold", "Button")) =
        SelectedElement(Json.parseToJsonElement(json).jsonObject, path, capturedAt = 3_600_000, screen = "1080×2400 · MainActivity", source = "sidekick", now = now)
    private val buy = """{"kind":"compose","id":160,"name":"Button","text":"Buy 1 ACME","bounds":{"left":40,"top":1000,"right":500,"bottom":1100},"sourceFile":"BrokerNav.kt","lineNumber":127}"""
    private val price = """{"kind":"view","id":7,"className":"android.widget.TextView","text":"19.27","bounds":{"left":0,"top":0,"right":10,"bottom":10}}"""
    private fun sel(by: String, vararg el: SelectedElement) = LayoutSelection("d", "a", 3, el.toList(), by, 7_200_000)

    @Test
    fun `the line says who selected and what is gone`() {
        assertEquals(null, SelectionText.line(LayoutSelection("d", "a"), utc))
        assertEquals("1 selected · agents can read it", SelectionText.line(sel("plugin", e(buy, Selections.ON_SCREEN)), utc))
        assertEquals("2 selected by an agent (mcp) at 02:00:00", SelectionText.line(sel("mcp", e(buy), e(price)), utc))
        assertEquals("2 selected · agents can read them · 1 no longer on screen: TextView \"19.27\"",
            SelectionText.line(sel("plugin", e(buy, Selections.ON_SCREEN), e(price, Selections.GONE)), utc))
        assertEquals("cleared: the app process ended", SelectionText.line(LayoutSelection("d", "a", 2, note = Selections.ENDED), utc))
    }

    @Test
    fun `the CLI lists each element with its call site, path and freshness`() {
        val text = SelectionText.text(sel("plugin", e(buy, Selections.ON_SCREEN)), utc)
        assertEquals(
            """
            1 selected by plugin at 02:00:00
              1. compose Button "Buy 1 ACME" #160 · BrokerNav.kt:127 · on screen
                 DecorView › Scaffold › Button · [40,1000 460×100] · captured 01:00:00 · 1080×2400 · MainActivity
            """.trimIndent(),
            text,
        )
        assertEquals("Nothing selected in the Layout tab.", SelectionText.text(LayoutSelection("d", "a"), utc))
        assertEquals("Nothing selected in the Layout tab (cleared: the app process ended).", SelectionText.text(LayoutSelection("d", "a", note = Selections.ENDED), utc))
    }
}
