package io.yamsergey.dta.cli.format

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

/** Feature 054: a call site names its package; an unknown one is marked. */
class LayoutCallSiteFormatTest {
    private val f = Formatters(Style.PLAIN)

    @Test
    fun `call sites show their package, unknown ones a question mark`() {
        val capture = """{"screen":{"widthPx":100,"heightPx":100},"source":"sidekick","windows":[{"type":"application","zOrder":0,"root":
            {"type":"compose","id":1,"bounds":{"left":0,"top":0,"right":10,"bottom":10},"name":"Text","text":"Hi","sourceFile":"Home.kt","lineNumber":18,"packageName":"io.example.ui","children":[
              {"type":"compose","id":2,"bounds":{"left":0,"top":0,"right":10,"bottom":10},"name":"Header","sourceFile":"h.kt","lineNumber":3,"unknownOrigin":true}]}}],
            "degraded":[{"capability":"layout","reason":"compose-call-packages-unknown: 1 calls"}]}"""
        val text = f.format("layout.tree", Json.parseToJsonElement(capture))
        assertTrue("Home.kt:18 (io.example.ui)" in text, text)
        assertTrue("h.kt:3 (?)" in text, text)
        assertTrue("compose-call-packages-unknown" in text, text)
    }
}
