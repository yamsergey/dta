package io.yamsergey.dta.present.layout

import io.yamsergey.dta.protocol.layout.LayoutSelection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Feature 055: tree rows ↔ shared elements. */
class SelectionMapTest {
    private val capture = Json.parseToJsonElement(
        """{"screen":{"widthPx":1080,"heightPx":2400,"density":2.6,"activity":"io.x.MainActivity"},"source":"sidekick","windows":[
          {"type":"application","zOrder":0,"root":{"kind":"view","id":1,"className":"com.android.internal.policy.DecorView","bounds":{"left":0,"top":0,"right":1080,"bottom":2400},"children":[
            {"kind":"compose","id":100,"name":"Scaffold","bounds":{"left":0,"top":0,"right":1080,"bottom":2400},"children":[
              {"kind":"compose","id":160,"name":"Button","text":"Buy","bounds":{"left":40,"top":1000,"right":500,"bottom":1100}},
              {"kind":"fallback","className":"android.widget.TextView","text":"19.27","bounds":{"left":0,"top":0,"right":10,"bottom":10}}
            ]}]}}]}""",
    ).jsonObject
    private val tree = LayoutTree.of(capture)

    @Test
    fun `selected rows become elements with their path and screen, children left out`() {
        val els = SelectionMap.elements(tree, listOf(listOf(0, 0, 0), listOf(0, 0)), capturedAt = 5)
        assertEquals(listOf("DecorView", "Scaffold", "Button"), els[0].path)
        assertEquals("1080×2400 · MainActivity", els[0].screen)
        assertNull(els[1].node["children"])
        assertEquals(5L, els[1].capturedAt)
    }

    @Test
    fun `a shared selection finds its rows, by id or by path and bounds, and counts what is missing`() {
        val mine = SelectionMap.elements(tree, listOf(listOf(0, 0, 1), listOf(0, 0, 0)), 5)
        val gone = mine[1].copy(node = Json.parseToJsonElement("""{"kind":"compose","id":999,"name":"Text"}""").jsonObject)
        val (paths, missing) = SelectionMap.paths(tree, LayoutSelection("d", "a", elements = mine + gone))
        assertEquals(listOf(listOf(0, 0, 1), listOf(0, 0, 0)), paths)
        assertEquals(1, missing)
    }
}
