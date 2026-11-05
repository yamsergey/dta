package io.yamsergey.dta.present.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContentLinesTest {
    private fun text(c: ContentLines) = c.lines.map { line -> line.joinToString("") { it.text } }

    @Test
    fun `JSON is pretty-printed with typed spans`() {
        val c = ContentLines.of("""{"type":"tick","price":20.7,"ok":true,"n":null}""", max = 10)
        assertEquals(listOf("{", "  \"type\": \"tick\",", "  \"price\": 20.7,", "  \"ok\": true,", "  \"n\": null", "}"), text(c))
        assertEquals(0, c.more)
        val kinds = c.lines[1].filter { it.text.isNotBlank() }.map { it.kind }
        assertEquals(listOf(SpanKind.KEY, SpanKind.PUNCT, SpanKind.STRING, SpanKind.PUNCT), kinds)
        assertEquals(SpanKind.NUMBER, c.lines[2].first { it.text == "20.7" }.kind)
        assertEquals(SpanKind.LITERAL, c.lines[3].first { it.text == "true" }.kind)
    }

    @Test
    fun `only the first lines, and how many are left`() {
        val c = ContentLines.of("""{"a":1,"b":2,"c":3}""", max = 3)
        assertEquals(listOf("{", "  \"a\": 1,", "  \"b\": 2,"), text(c))
        assertEquals(2, c.more)
    }

    @Test
    fun `a cut JSON text still formats, and what is left is unknown`() {
        val c = ContentLines.of("""{"type":"subscribed","symbols":["AC""", max = 10, cut = true)
        assertEquals(listOf("{", "  \"type\": \"subscribed\",", "  \"symbols\": [", "    \"AC"), text(c))
        assertNull(c.more)
    }

    @Test
    fun `small arrays and objects stay on one line, empty ones too`() {
        assertEquals(listOf("{", "  \"s\": [],", "  \"o\": {}", "}"), text(ContentLines.of("""{"s":[],"o":{}}""", max = 10)))
    }

    @Test
    fun `text that is not JSON keeps its lines`() {
        val c = ContentLines.of("CONNECT\naccept-version:1.2\nhost:x\n\n\u0000", max = 2)
        assertEquals(listOf("CONNECT", "accept-version:1.2"), text(c))
        assertEquals(SpanKind.PLAIN, c.lines[0].single().kind)
        assertEquals(1, c.more)
    }

    @Test
    fun `escaped quotes stay inside their string`() {
        val c = ContentLines.of("""{"m":"say \"hi\", ok"}""", max = 10)
        assertEquals("  \"m\": \"say \\\"hi\\\", ok\"", text(c)[1])
    }
}
