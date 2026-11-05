package io.yamsergey.dta.cli.format

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 040: search hits under list rows, and network search, in the terminal. */
class SearchFormatTest {
    private fun format(op: String, json: String) = Formatters(Style.PLAIN).format(op, Json.parseToJsonElement(json))

    private val hit = """{"id":"r1:2","place":"response-body","term":"token expired","start":10,"end":23,"part":"response",
        "excerpt":{"text":"{\"error\":\"Token expired\"}","start":10,"end":23}}"""
    private val header = """{"id":"r1:2","place":"request-header","term":"orderid","start":0,"end":1,"header":"X-Order-Id","excerpt":{"text":"7","start":0,"end":1}}"""

    @Test
    fun `a list row with hits shows where under it`() {
        val text = format("network.list", """{"entries":[{"type":"http","id":"r1:2","runId":"r1","timeMs":1,"library":"okhttp3","method":"POST","url":"https://api.example.test/login","state":"complete","status":401}],
            "total":1,"coverage":{"searched":1,"stored":1,"persisted":true},"hits":{"r1:2":[$hit,$header]}}""")
        val lines = text.lines()
        assertTrue(lines[0].contains("api.example.test/login"), text)
        assertEquals("      ▸ response body: {\"error\":\"Token expired\"}", lines[1])
        assertEquals("      ▸ request header X-Order-Id: 7", lines[2])
    }

    @Test
    fun `search lists entries with their hits and what was not searched`() {
        val text = format("network.search", """{"entries":[{"id":"r1:2","timeMs":1,"hits":[$hit]}],"total":1,"searched":40,
            "notSearched":["bodies stored cut, searched as far as stored (2)"]}""")
        assertTrue(text.lines()[0].startsWith("r1:2  "), text)
        assertTrue("▸ response body: {\"error\":\"Token expired\"}" in text, text)
        assertTrue("1 of 1 entries with hits, 40 searched" in text, text)
        assertTrue("not searched: bodies stored cut" in text, text)
        assertTrue(format("network.search", """{"entries":[],"total":0,"searched":3}""").startsWith("Not found."))
    }
}
