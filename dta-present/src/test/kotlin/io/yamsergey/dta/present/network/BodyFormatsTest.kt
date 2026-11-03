package io.yamsergey.dta.present.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BodyFormatsTest {

    @Test
    fun `the content type names the format, the text decides when it does not`() {
        assertEquals(BodyFormat.JSON, BodyFormats.detect("application/json; charset=utf-8", "{}"))
        assertEquals(BodyFormat.JSON, BodyFormats.detect("application/problem+json", "{}"))
        assertEquals(BodyFormat.XML, BodyFormats.detect("application/atom+xml", "<feed/>"))
        assertEquals(BodyFormat.XML, BodyFormats.detect("text/xml", "<a/>"))
        assertEquals(BodyFormat.HTML, BodyFormats.detect("text/html", "<html></html>"))
        assertEquals(BodyFormat.FORM, BodyFormats.detect("application/x-www-form-urlencoded", "a=1&b=2"))
        assertEquals(BodyFormat.IMAGE, BodyFormats.detect("image/png", null))
        // No or a vague type: sniffed.
        assertEquals(BodyFormat.JSON, BodyFormats.detect(null, "  [1, 2]"))
        assertEquals(BodyFormat.JSON, BodyFormats.detect("text/plain", "{\"a\":1}"))
        assertEquals(BodyFormat.XML, BodyFormats.detect(null, "<?xml version=\"1.0\"?><a/>"))
        assertEquals(BodyFormat.HTML, BodyFormats.detect(null, "<!DOCTYPE html><html></html>"))
        assertEquals(BodyFormat.TEXT, BodyFormats.detect("text/plain", "hello"))
        // JSON that does not parse is text, whatever the type says.
        assertEquals(BodyFormat.TEXT, BodyFormats.detect("application/json", "{oops"))
    }

    @Test
    fun `one JSON document per line is JSON Lines, whatever the type says`() {
        val envelope = "{\"event_id\":\"9f\",\"sent_at\":\"2026-11-03\"}\n{\"type\":\"event\",\"length\":42}\r\n\n{\"level\":\"error\",\"tags\":[1,2]}\n"
        assertEquals(BodyFormat.JSON_LINES, BodyFormats.detect("application/x-sentry-envelope", envelope))
        assertEquals(BodyFormat.JSON_LINES, BodyFormats.detect("application/x-ndjson", "[1]\n[2]"))
        assertEquals(3, BodyFormats.jsonLines(envelope)!!.size)
        // One document is JSON; a line that is not JSON (a binary attachment) keeps it text.
        assertEquals(BodyFormat.JSON, BodyFormats.detect(null, "{\"a\":1}\n"))
        assertEquals(BodyFormat.TEXT, BodyFormats.detect("application/x-sentry-envelope", "{\"a\":1}\n{\"type\":\"attachment\"}\nPNG\u0001"))
        assertEquals(BodyFormat.TEXT, BodyFormats.detect(null, "1\n2"))
        assertNull(BodyFormats.jsonLines("{\"a\":1}"))
    }

    @Test
    fun `XML is re-indented, and a DOCTYPE is never parsed`() {
        assertEquals("<a>\n  <b x=\"1\">t</b>\n  <c/>\n</a>", BodyFormats.prettyXml("<a><b x=\"1\">t</b><c/></a>"))
        assertNull(BodyFormats.prettyXml("<!DOCTYPE a [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><a>&e;</a>"))
        assertNull(BodyFormats.prettyXml("<a>"))
        // Attributes keep the order they were written in.
        assertEquals("<f xmlns=\"urn:x\" lang=\"en\" a=\"1\"/>", BodyFormats.prettyXml("<f xmlns=\"urn:x\" lang=\"en\" a=\"1\"/>"))
    }

    @Test
    fun `a form reads as decoded key = value lines`() {
        assertEquals("q = hello world\ntags = a&b\nempty = ", BodyFormats.formLines("q=hello+world&tags=a%26b&empty="))
    }

    @Test
    fun `a GraphQL request offers its query and variables`() {
        val q = BodyFormats.graphql("""{"operationName":"Feed","query":"query Feed(${'$'}n: Int) {\n  feed(first: ${'$'}n) { id }\n}","variables":{"n":10}}""")!!
        assertEquals("query Feed(${'$'}n: Int) {\n  feed(first: ${'$'}n) { id }\n}", q.query)
        assertEquals("{\n  \"n\": 10\n}", q.variables)
        assertNull(BodyFormats.graphql("""{"query":42}"""))
        assertNull(BodyFormats.graphql("""[1]"""))
    }
}
